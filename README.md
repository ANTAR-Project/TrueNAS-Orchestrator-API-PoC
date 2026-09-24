# TrueNAS Orchestrator API — PoC

A microservice-based video ingestion, transcoding, and adaptive-bitrate streaming platform for ANTAR (HEARTH), built on top of a **TrueNAS SCALE** box as the sole storage backend (no S3/object storage). Five Spring Boot services coordinate over **gRPC** (high-throughput streaming file transfers, playlist proxying & low-latency token validation), **Kafka** (async video pipeline), and **Redis** (auth tokens, segment cache, encoded playlist index), with **SMB/CIFS** as the actual file transport into TrueNAS and the **TrueNAS REST API** used only for one-time storage provisioning.

---

## 1. Architecture

### 1.1 Services at a glance

| Service | HTTP Port | gRPC Port | Role | Talks to |
|---|---|---|---|---|
| **auth-service** | 8085 | 4091 | Issues & validates opaque bearer tokens over REST & gRPC | Redis |
| **nas-orchestrator** | 8081 | 4092 | Single gateway to TrueNAS storage: file & workspace CRUD, HLS playlist rewriting & segment cache, gRPC file-transfer server + gRPC playlist server, one-time SMB/pool/dataset/share bootstrap | TrueNAS REST API, SMB share, Redis, Caffeine (in-proc), auth-service (gRPC 4091) |
| **video-service** | 8082 | — | Accepts raw video uploads (path-scoped multipart), streams file to nas-orchestrator over gRPC, publishes `video.uploaded` | nas-orchestrator (gRPC 4092), Kafka, auth-service |
| **encoding-service** | 8083 | — | Consumes `video.uploaded`, downloads raw file & uploads multi-bitrate HLS tree via gRPC, publishes `video.encoded` | nas-orchestrator (gRPC 4092), Kafka, auth-service, local FFmpeg binary |
| **streaming-service** | 8084 | — | Client-facing playlist proxy (`/api/v1/stream/playlist?path=…`); resolves path directly and proxies through nas-orchestrator over gRPC; also consumes `video.encoded` to cache master playlist paths in Redis | Redis, nas-orchestrator (gRPC 4092), auth-service (gRPC 4091) |

Supporting infra (from `docker-compose.yml`): **Redis** (6379), **Zookeeper** (2181, Kafka dependency), **Kafka** (9092 external / 29092 internal).

### 1.2 Diagram

```mermaid
flowchart LR
    Client([Client / Player])

    subgraph Edge
        VS[video-service\nHTTP :8082]
        SS[streaming-service\nHTTP :8084]
    end

    subgraph Core
        NAS["nas-orchestrator\nHTTP :8081 | gRPC :4092"]
        AUTH["auth-service\nHTTP :8085 | gRPC :4091"]
        ENC[encoding-service\nHTTP :8083]
    end

    subgraph Infra
        KAFKA[(Kafka)]
        REDIS[(Redis)]
        TRUENAS[("TrueNAS SCALE\nSMB share + REST API")]
    end

    Client -- "1. POST /api/v1/videos/upload?path=..." --> VS
    Client -- "4. GET /api/v1/stream?path=<nasPath>" --> SS
    Client -- "5. GET segment / playlist\n(direct, URL rewritten)" --> NAS

    VS -- "gRPC UploadFile (64KB chunks)" --> NAS
    VS -- "publish video.uploaded" --> KAFKA
    KAFKA -- "consume video.uploaded" --> ENC
    ENC -- "gRPC DownloadFile (raw video)\ngRPC UploadFile (HLS tree)" --> NAS
    ENC -- "publish video.encoded" --> KAFKA
    KAFKA -- "consume video.encoded" --> SS
    SS -- "cache nasPath → master.m3u8 path" --> REDIS
    SS -- "resolve nasPath → master.m3u8 path" --> REDIS
    SS -- "gRPC GetRewrittenPlaylist" --> NAS
    NAS -- "gRPC ValidateToken" --> AUTH
    SS -- "gRPC ValidateToken" --> AUTH
    VS -- "fetch service token" --> AUTH
    ENC -- "fetch service token" --> AUTH
    AUTH -- "token store" --> REDIS
    NAS -- "L1 Caffeine (in-proc)\nL2 Redis (shared)" --> REDIS
    NAS -- "SMB read/write stream" --> TRUENAS
    NAS -- "one-time bootstrap\n(pool/dataset/user/share via REST)" --> TRUENAS
```

### 1.3 Why this shape

- **nas-orchestrator is the single gateway to TrueNAS.** Every other service reaches storage through gRPC file transfer or the gRPC playlist API — keeping SMB credentials and TrueNAS API keys confined to one process and letting that process own all caching/perf concerns (segment cache, streamed download, gRPC chunked file streaming directly to SMB).
- **High-Performance gRPC File Pipelines (`filetransfer.proto`):** File transfers between microservices (`video-service` / `encoding-service` and `nas-orchestrator`) utilise gRPC streaming over port `4092`. Files are streamed as 64KB binary `FileChunk` messages directly to SMB output streams — no buffering in HTTP multipart bodies.
- **gRPC Playlist Proxy (`streaming.proto`):** `streaming-service` proxies HLS playlist requests to `nas-orchestrator` over a dedicated unary gRPC call (`StreamingGrpcService.GetRewrittenPlaylist` on port `4092`). The caller supplies the raw NAS-relative path — no movieId indirection.
- **Low-Latency gRPC Auth Pipeline (`auth.proto`):** Protected requests to `nas-orchestrator` and `streaming-service` validate opaque tokens via high-performance binary gRPC calls (`AuthGrpcService.ValidateToken` on port `4091`) against `auth-service`, eliminating REST HTTP serialisation overhead.
- **auth-service is a minimal, Redis-backed opaque token issuer** — `POST /tokens` mints a token and evicts any previous token for that identity (single active token per username); `ValidateToken` (gRPC) or `GET /tokens/validate` (HTTP) is called by downstream services on protected requests via a shared `TokenValidationFilter`/`AuthClient` pattern.
- **video-service and encoding-service authenticate to nas-orchestrator as service identities** (`video-service`, `encoding-service`), fetching and caching their token from auth-service at startup (`ServiceAuthTokenProvider`).
- **Upload is plain multipart — no movieId in the URL.** `video-service` accepts `POST /api/v1/videos/upload?path=<optional-sub-path>` and scopes the destination automatically to `{username}/{path}/{filename}` on the NAS. The Kafka event carries the full NAS path so downstream services never need to re-derive it.
- **Kafka decouples upload from encoding.** `encoding-service`'s FFmpeg job can run for a long time; the consumer pauses its own container, acks the offset immediately (at-least-once, no redelivery), and processes on a dedicated worker thread so `poll()` keeps the consumer alive without a timeout risk.
- **Workspace-scoped NAS paths.** All file operations in `video-service` and `nas-orchestrator` prepend the authenticated username so users are isolated in their own directory tree (`{username}/...`). Encoded output lands under `{workspaceRoot}/encoded/{path}/{fileBaseName}/`.
- **Two-tier segment cache in nas-orchestrator**: Caffeine (L1, in-process, weight-bounded ~256 MB, 30 min expiry-after-access) in front of Redis (L2, 6 h TTL). On every segment fetch (hit or miss) it fires an async **N+1 prefetch** of the next `.ts` segment in the same quality ladder, using a separate bounded executor so prefetch never competes with request-serving threads.
- **Playlist URL rewriting happens once, centrally.** `nas-orchestrator` rewrites every line of every playlist it serves so variant `.m3u8` references route back to `/api/v1/nas-orchestrator/stream/playlist` and `.ts` segment references route to `/api/v1/nas-orchestrator/stream/segment` — both carrying `?token=` — so **players never talk to streaming-service more than once** after the initial request.

---

## 2. Workflow

### 2.1 Upload → Encode → Stream (end to end)

1. **Upload** — Client calls `video-service POST /api/v1/videos/upload?path=<optional-sub-path>` (multipart `file`). `video-service` scopes the destination to `{username}/{path}/{filename}` and streams the raw video to `nas-orchestrator` over **gRPC (`FileTransferGrpcService.UploadFile`)** in 64 KB chunks. It then publishes a `VideoUploadedEvent` on Kafka topic **`video.uploaded`** (key = NAS path) carrying `workspaceRoot`, `path`, `nasPath`, `originalFileName`, and `fileSizeBytes`.
2. **Encode** — `encoding-service` consumes `video.uploaded`, pauses its Kafka container, acks, and hands the job to a single-threaded encoding worker:
   - Downloads the raw video from `nas-orchestrator` over **gRPC (`FileTransferGrpcService.DownloadFile`)** to a local temp directory.
   - Runs FFmpeg per rendition — **1080p/5000 kbps, 720p/2800 kbps, 480p/1200 kbps, 360p/800 kbps** — each to its own HLS playlist + `.ts` segments (10 s segment target, `libx264` video, `aac` 128 kbps audio).
   - Generates a `master.m3u8` referencing all four variant playlists.
   - Streams the entire `encoded/` tree back to `nas-orchestrator` file-by-file over **gRPC (`FileTransferGrpcService.UploadFile`)**, landing at `{workspaceRoot}/encoded/{path}/{fileBaseName}/`.
   - Publishes `VideoEncodedEvent` (success/failure) on **`video.encoded`**, then cleans up its temp directory regardless of outcome.
3. **Index** — `streaming-service` consumes `video.encoded` and, on success, caches `nasPath → {workspaceRoot}/encoded/{path}/{fileBaseName}/master.m3u8` in Redis (`streaming:playlist:{nasPath}`).
4. **Play** — Client calls `streaming-service GET /api/v1/stream?path=<nasPath>` supplying the raw file's NAS path (e.g. `alice/sample.mp4` — the same key published in `video.uploaded`). `streaming-service` scopes it to `{username}/{path}`, resolves the master playlist path from Redis (`streaming:playlist:{scopedPath}` → `alice/encoded/sample/master.m3u8`), then forwards that resolved path to `nas-orchestrator` via **gRPC (`StreamingGrpcService.GetRewrittenPlaylist`)**, attaching the caller's token per-call.
5. **Direct playback** — `nas-orchestrator` rewrites every line of every playlist it serves: variant `.m3u8` references route back to `/api/v1/nas-orchestrator/stream/playlist`, `.ts` segment references route to `/api/v1/nas-orchestrator/stream/segment` — both carrying `?token=` — so the player fetches all subsequent playlists and segments **directly from nas-orchestrator**, hitting the L1/L2 segment cache (with N+1 prefetch) on every `.ts` read.

### 2.2 Auth flow

- **Human/service identity registration**: `POST /api/v1/auth-service/tokens {username}` → Redis stores `auth:token:{token} → username` and `auth:user:{username} → token` (re-issuing invalidates the previous token for that identity). TTL is configurable (`AUTH_TOKEN_TTL_HOURS`, default `0` = no expiry).
- **Every protected request** to nas-orchestrator / streaming-service carries `X-Auth-Token` (header) or `?token=` (query parameter). A shared `TokenValidationFilter` intercepts the request and validates the token via **gRPC (`AuthGrpcService.ValidateToken`)** against `auth-service:4091`.
- **video-service / encoding-service** fetch and cache their fixed service identity tokens (`video-service`, `encoding-service`) at startup via `ServiceAuthTokenProvider`.

### 2.3 Storage bootstrap (one-time, nas-orchestrator startup)

If `SMB_BOOTSTRAP_ENABLED=true` (default), `StorageBoostrapService` runs on nas-orchestrator startup via TrueNAS REST API:

1. Creates SMB user (`smb.username`/`smb.password`) if missing.
2. Creates ZFS pool (`antarpool`) on configured disk if missing.
3. Creates dataset (`antarpool/antar-dataset`) if missing.
4. Sets permissive (`777`) recursive permissions.
5. Creates SMB share (`antar-share`) and starts the `cifs` service.

---

## 3. API Endpoints Reference

### 3.1 `nas-orchestrator` (`:8081` HTTP / `:4092` gRPC)

#### User Files API (`/api/v1/nas-orchestrator/files`) — *Protected*
| Method | Endpoint | Query / Body Params | Description |
|---|---|---|---|
| `POST` | `/upload/file` | `path` (query, optional), `file` (multipart) | Upload a single file into the caller's scoped workspace (`{username}/{path}/{filename}`) |
| `POST` | `/upload/folder` | `path` (query, optional), `files` (multipart array), `relativePaths` (string array) | Upload a directory hierarchy into the caller's workspace |
| `GET` | `/download` | `path` (query) | Stream-download a file from the caller's workspace |
| `GET` | `/preview` | `path` (query) | Stream-preview an inline media or text file from the caller's workspace |
| `GET` | `/list` | `path` (query, optional) | List files and folders in the caller's workspace directory |
| `DELETE` | `/delete` | `path` (query) | Delete a file or directory from the caller's workspace |

#### Shared Workspace API (`/api/v1/nas-orchestrator/shared`) — *Protected*
| Method | Endpoint | Query / Body Params | Description |
|---|---|---|---|
| `GET` | `/list` | `path` (query, optional) | List files and folders in the shared workspace |
| `POST` | `/upload/file` | `path` (query, optional), `file` (multipart) | Upload a single file into the shared workspace |
| `POST` | `/upload/folder` | `path` (query, optional), `files` (multipart array), `relativePaths` (string array) | Upload a folder hierarchy into the shared workspace |
| `GET` | `/download` | `path` (query) | Stream-download a file from the shared workspace |
| `GET` | `/preview` | `path` (query) | Preview an inline file in the shared workspace |
| `DELETE` | `/delete` | `path` (query) | Delete a file or folder from the shared workspace |
| `DELETE` | `/clear` | — | Purge all files in the shared workspace |

#### Workspace Management API (`/api/v1/nas-orchestrator/workspace`) — *Protected*
| Method | Endpoint | Body Params | Description |
|---|---|---|---|
| `POST` | `/create` | JSON `{ "name": "workspace_name" }` | Provision an isolated workspace folder for a user |
| `DELETE` | `/delete` | — | Delete the calling user's root workspace directory |
| `DELETE` | `/clear` | — | Clear all contents inside the calling user's workspace directory |

#### HLS Streaming API (`/api/v1/nas-orchestrator/stream`) — *Token via query param*
| Method | Endpoint | Query Params | Description |
|---|---|---|---|
| `GET` | `/playlist` | `path` (required), `token` (optional) | Fetch and rewrite HLS playlist (`.m3u8`) with self-referencing absolute URLs pointing back to `/stream/playlist` and `/stream/segment` |
| `GET` | `/segment` | `path` (required) | Stream `.ts` HLS video segment backed by L1 Caffeine / L2 Redis cache + async N+1 prefetch |

#### System & Health
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/nas-orchestrator/health` | Health check endpoint |

#### gRPC Services (port `:4092`)
| Service | RPC Method | Type | Description |
|---|---|---|---|
| `FileTransferGrpcService` | `UploadFile` | Client Streaming (`stream FileChunk → UploadResponse`) | Streams binary 64 KB file chunks directly into SMB destination; first chunk carries `destination_path` |
| `FileTransferGrpcService` | `DownloadFile` | Server Streaming (`DownloadRequest → stream FileChunk`) | Streams binary 64 KB file chunks from SMB storage |
| `StreamingGrpcService` | `GetRewrittenPlaylist` | Unary (`PlaylistRequest → PlaylistResponse`) | Reads `.m3u8` from SMB, rewrites all playlist/segment URIs to self-referencing nas-orchestrator URLs, returns raw M3U8 string |

---

### 3.2 `auth-service` (`:8085` HTTP / `:4091` gRPC)

| Method | Endpoint | Request Body / Query | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth-service/tokens` | JSON `{ "username": "user" }` | Mint or re-issue an opaque auth token |
| `GET` | `/api/v1/auth-service/tokens/validate` | `token` (query) | Validate token over HTTP REST |
| `GET` | `/api/v1/auth-service/health` | — | Health check endpoint |

#### gRPC Services (`AuthGrpcService` on port `:4091`)
| RPC Method | Type | Description |
|---|---|---|
| `ValidateToken` | Unary (`ValidateTokenRequest → ValidateTokenResponse`) | Validates opaque token; returns `valid` flag + `username` |

---

### 3.3 `video-service` (`:8082` HTTP) — *Protected*

| Method | Endpoint | Request Params | Description |
|---|---|---|---|
| `POST` | `/api/v1/videos/upload` | `path` (query, optional), `file` (multipart) | Upload raw video; destination scoped to `{username}/{path}/{filename}` on NAS via gRPC; triggers Kafka `video.uploaded` event |

**Response body:** `"video uploaded successfully! Key = {nasPath} : Encoding started automatically via Kafka"`

**Kafka event published** (`video.uploaded`, key = `nasPath`):

| Field | Type | Description |
|---|---|---|
| `workspaceRoot` | String | Authenticated username (workspace root directory) |
| `path` | String | Optional sub-path supplied by client |
| `nasPath` | String | Full NAS-relative path the file was written to (`{username}/{path}/{filename}`) |
| `originalFileName` | String | Original filename from the multipart upload |
| `fileSizeBytes` | long | File size in bytes |

---

### 3.4 `encoding-service` (`:8083` HTTP) — *No HTTP endpoints*

`encoding-service` exposes no HTTP API. It operates purely as a Kafka consumer.

**Kafka event consumed** (`video.uploaded`, group `encoding-service-group`):

Encoding pipeline per consumed event:
1. Downloads raw file from NAS via gRPC `DownloadFile` to local temp dir
2. FFmpeg encodes to **4 renditions**: 1080p/5000 kbps, 720p/2800 kbps, 480p/1200 kbps, 360p/800 kbps (H.264 + AAC 128 kbps, 10 s HLS segments)
3. Generates `master.m3u8` referencing all four variant playlists
4. Uploads entire encoded tree to NAS via gRPC `UploadFile` under `{workspaceRoot}/encoded/{path}/{fileBaseName}/`
5. Publishes `VideoEncodedEvent` on `video.encoded`
6. Cleans up local temp directory

**Kafka event published** (`video.encoded`, key = `nasPath`):

| Field | Type | Description |
|---|---|---|
| `nasPath` | String | NAS path of the original raw file (Kafka message key) |
| `masterPlaylistPath` | String | NAS-relative path to `master.m3u8` (null on failure) |
| `success` | boolean | Whether encoding completed successfully |
| `errorMessage` | String | Error description (null on success) |

---

### 3.5 `streaming-service` (`:8084` HTTP) — *Protected*

| Method | Endpoint | Query / Header Params | Description |
|---|---|---|---|
| `GET` | `/api/v1/stream` | `path` (required), `X-Auth-Token` (header) or `token` (query) | Resolve master playlist from Redis by scoped NAS path, then proxy the rewritten M3U8 through nas-orchestrator via gRPC |

**Request flow:**
1. Client supplies `?path=<nasPath>` — the raw NAS path of the uploaded file (e.g. `sample.mp4`, the same value used as the Kafka key in `video.uploaded`).
2. Controller scopes it: `{username}/{path}` → e.g. `alice/sample.mp4`.
3. Looks up `streaming:playlist:alice/sample.mp4` in Redis → resolves to the master playlist path e.g. `alice/encoded/sample/master.m3u8`.
4. Returns `404` if no playlist is registered yet (encoding not complete).
5. Calls `nas-orchestrator` via **gRPC `StreamingGrpcService.GetRewrittenPlaylist`**, forwarding the resolved path and the caller's token per-call via gRPC metadata (`x-auth-token`).
6. Returns the rewritten M3U8 — all segment and sub-playlist URLs point directly at `nas-orchestrator`, so the player never hits `streaming-service` again.

- Path is validated: no `..` traversal, no backslashes, no leading `/`.

**Redis index** (written by Kafka consumer on `video.encoded`, consumed by the HTTP endpoint):
- Key: `streaming:playlist:{workspaceRoot}/{path}` (e.g. `streaming:playlist:alice/sample.mp4`)
- Value: NAS-relative path to `master.m3u8` (e.g. `alice/encoded/sample/master.m3u8`)
- Written when a successful `video.encoded` event is consumed from Kafka topic `video.encoded` (group `streaming-service-group`)

---

## 4. Repository layout

```
TrueNAS-Orchestrator-API-PoC/
├── docker-compose.yml        # redis, zookeeper, kafka + all 5 microservices
├── proto/                    # Protobuf definitions (auth.proto, filetransfer.proto, streaming.proto)
├── auth-service/             # token issue/validate REST & gRPC server (port 8085 / 4091)
├── nas-orchestrator/         # SMB/TrueNAS gateway, HLS proxy, file CRUD, gRPC server (port 8081 / 4092)
├── video-service/            # upload intake, gRPC client, kafka producer (port 8082)
├── encoding-service/         # kafka consumer, ffmpeg HLS encode, gRPC client (port 8083)
└── streaming-service/        # kafka consumer (index), gRPC playlist proxy (port 8084)
```

---

## 5. Bootstrap steps

### 5.1 Prerequisites

- Docker + Docker Compose v2
- A reachable **TrueNAS SCALE** instance with:
  - An unused/blank disk for ZFS pool creation (or pre-provisioned share with `SMB_BOOTSTRAP_ENABLED=false`).
  - TrueNAS API key with pool/dataset/share/user permissions.
- Ports free on host: `6379, 9092, 4091, 4092, 8081-8085`.

### 5.2 Clone & Configure `.env`

```bash
git clone https://github.com/ANTAR-Project/TrueNAS-Orchestrator-API-PoC.git
cd TrueNAS-Orchestrator-API-PoC
```

Ensure `./<service>/.env` files exist for all services before starting.

### 5.3 Bring the stack up

```bash
docker compose up --build -d
```

### 5.4 Verify

```bash
curl http://localhost:8085/api/v1/auth-service/health
curl http://localhost:8081/api/v1/nas-orchestrator/health
```

Issue a token and test upload:
```bash
TOKEN=$(curl -s -X POST http://localhost:8085/api/v1/auth-service/tokens \
  -H 'Content-Type: application/json' -d '{"username":"tester"}' | jq -r .token)

# Upload a raw video (no movieId — path is optional, scoped to your workspace automatically)
curl -X POST "http://localhost:8082/api/v1/videos/upload" \
  -H "X-Auth-Token: $TOKEN" \
  -F "file=@/path/to/sample.mp4"
# → returns: "video uploaded successfully! Key = tester/sample.mp4 : Encoding started automatically via Kafka"
```

Once `video.encoded` finishes on Kafka (master playlist path is stored in Redis), stream directly by path:
```bash
# Path is the nasPath returned by the upload (same as the Kafka key)
curl "http://localhost:8084/api/v1/stream?path=tester/sample.mp4&token=$TOKEN"
```

---

## 6. Points to Consider

- **No TLS anywhere** — inter-service REST/gRPC calls, SMB, and TrueNAS API calls are plaintext in this PoC.
- **Tokens don't expire by default** (`AUTH_TOKEN_TTL_HOURS=0`) and are stored in Redis without revocation UI.
- **`depends_on` in compose is startup-order only** — retries are implemented within clients for Redis/Kafka/gRPC.
- **No horizontal-scaling story for encoding-service** — one encode at a time *per instance* (single-thread executor + paused consumer), and FFmpeg jobs are unbounded in duration with no timeout/kill logic.
- **Redis playlist index is required for playback** — `streaming-service` resolves `streaming:playlist:{scopedNasPath}` from Redis on every `GET /api/v1/stream` request. If the key is missing (encoding not yet complete or failed), the endpoint returns `404`. Playback is only possible after a successful `video.encoded` Kafka event has been consumed and the key written.
