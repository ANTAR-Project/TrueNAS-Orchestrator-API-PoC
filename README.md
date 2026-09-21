# TrueNAS Orchestrator API — PoC

A microservice-based video ingestion, transcoding, and adaptive-bitrate streaming platform for ANTAR (HEARTH), built on top of a **TrueNAS SCALE** box as the sole storage backend (no S3/object storage). Five Spring Boot services coordinate over **gRPC** (high-throughput streaming file transfers & low-latency token validation), **Kafka** (async video pipeline), and **Redis** (auth tokens, segment cache, playlist index), with **SMB/CIFS** as the actual file transport into TrueNAS and the **TrueNAS REST API** used only for one-time storage provisioning.

---

## 1. Architecture

### 1.1 Services at a glance

| Service | HTTP Port | gRPC Port | Role | Talks to |
|---|---|---|---|---|
| **auth-service** | 8085 | 4091 | Issues & validates opaque bearer tokens over REST & gRPC | Redis |
| **nas-orchestrator** | 8081 | 4092 | Single gateway to TrueNAS storage: file & workspace CRUD, HLS playlist rewriting, segment cache, gRPC file stream server, one-time SMB/pool/dataset/share bootstrap | TrueNAS REST API, SMB share, Redis, Caffeine (in-proc), auth-service (gRPC 4091) |
| **video-service** | 8082 | — | Accepts raw video uploads, streams file to nas-orchestrator over gRPC, publishes `video.uploaded` | nas-orchestrator (gRPC 4092), Kafka, auth-service |
| **encoding-service** | 8083 | — | Consumes `video.uploaded`, downloads raw file & uploads multi-bitrate HLS tree via gRPC, publishes `video.encoded` | nas-orchestrator (gRPC 4092), Kafka, auth-service, local FFmpeg binary |
| **streaming-service** | 8084 | — | Client-facing playlist entry point (`/api/v1/stream/{movieId}`); resolves movie → master playlist path from Redis, proxies through nas-orchestrator | Redis, nas-orchestrator, auth-service (gRPC 4091) |

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
        NAS[nas-orchestrator\nHTTP :8081 | gRPC :4092]
        AUTH[auth-service\nHTTP :8085 | gRPC :4091]
        ENC[encoding-service\nHTTP :8083]
    end

    subgraph Infra
        KAFKA[(Kafka)]
        REDIS[(Redis)]
        TRUENAS[(TrueNAS SCALE\nSMB share + REST API)]
    end

    Client -- "1. POST /api/v1/videos/upload/{movieId}" --> VS
    Client -- "4. GET /api/v1/stream/{movieId}" --> SS
    Client -- "5. GET segment / playlist\n(direct, URL rewritten)" --> NAS

    VS -- "gRPC UploadFile (64KB chunks)" --> NAS
    VS -- "publish video.uploaded" --> KAFKA
    KAFKA -- "consume video.uploaded" --> ENC
    ENC -- "gRPC DownloadFile (raw video)\ngRPC UploadFile (HLS tree)" --> NAS
    ENC -- "publish video.encoded" --> KAFKA
    KAFKA -- "consume video.encoded" --> SS
    SS -- "cache movieId -> master.m3u8 path" --> REDIS
    SS -- "resolve path" --> REDIS
    SS -- "proxy playlist" --> NAS

    VS -- "fetch service token" --> AUTH
    ENC -- "fetch service token" --> AUTH
    NAS -- "gRPC ValidateToken" --> AUTH
    SS -- "gRPC ValidateToken" --> AUTH
    AUTH -- "token store" --> REDIS

    NAS -- "L1 Caffeine (in-proc)\nL2 Redis (shared)" --> REDIS
    NAS -- "SMB read/write stream" --> TRUENAS
    NAS -- "one-time bootstrap\n(pool/dataset/user/share via REST)" --> TRUENAS
```

### 1.3 Why this shape

- **nas-orchestrator is the single gateway to TrueNAS.** Every other service reaches storage through gRPC file transfer or HTTP APIs — keeping SMB credentials and TrueNAS API keys confined to one process and letting that process own caching/perf concerns (segment cache, streamed zip/download, gRPC chunked file streaming directly to SMB).
- **High-Performance gRPC File Pipelines (`filetransfer.proto`):** File transfers between microservices (`video-service` / `encoding-service` and `nas-orchestrator`) utilize gRPC streaming over port `4092`. Instead of buffering large video files or HLS segment trees in HTTP multipart request bodies, files are streamed as 64KB binary `FileChunk` messages directly to SMB output streams.
- **Low-Latency gRPC Auth Pipeline (`auth.proto`):** Protected requests to `nas-orchestrator` and `streaming-service` validate opaque tokens via high-performance binary gRPC calls (`AuthGrpcService.ValidateToken` on port `4091`) against `auth-service`, eliminating REST HTTP serialization overhead.
- **auth-service is a minimal, Redis-backed opaque token issuer** — `POST /tokens` mints a token and evicts any previous token for that identity (single active token per username); `ValidateToken` (gRPC) or `GET /tokens/validate` (HTTP) is called by downstream services on protected requests via a shared `TokenValidationFilter`/`AuthClient` pattern.
- **video-service and encoding-service authenticate to nas-orchestrator as service identities** (`video-service`, `encoding-service`), fetching and caching their token from auth-service at startup (`ServiceAuthTokenProvider`).
- **Kafka decouples upload from encoding.** `encoding-service`'s FFmpeg job can run for a long time; the consumer pauses its own container, acks the offset immediately (at-least-once, no redelivery), and processes on a dedicated worker thread so `poll()` keeps the consumer alive without a timeout risk.
- **Two-tier segment cache in nas-orchestrator**: Caffeine (L1, in-process, weight-bounded ~256MB, 30 min expiry-after-access) in front of Redis (L2, 6h TTL). On every segment fetch (hit or miss) it fires an async **N+1 prefetch** of the next `.ts` segment in the same quality ladder, using a separate bounded executor so prefetch never competes with request-serving threads.
- **Playlist URL rewriting happens once, centrally.** `nas-orchestrator` rewrites `.m3u8` files it serves so that every playlist and segment reference points back at its own `/stream/playlist` and `/stream/segment` endpoints (carrying the auth token as a query param). This means **players never talk to streaming-service more than once** — the first playlist fetch resolves everything else directly against nas-orchestrator.

---

## 2. Workflow

### 2.1 Upload → Encode → Stream (end to end)

1. **Upload** — Client calls `video-service POST /api/v1/videos/upload/{movieId}` (multipart). `video-service` streams the raw video file to `nas-orchestrator` over **gRPC (`FileTransferGrpcService.UploadFile`)** in 64KB chunks under `raw/{movieId}/{originalFilename}`, then publishes a `VideoUploadedEvent` on Kafka topic **`video.uploaded`** (key = movieId).
2. **Encode** — `encoding-service` consumes `video.uploaded`, pauses its Kafka container, acks, and hands the job to a single-threaded encoding worker:
   - Downloads the raw video file from `nas-orchestrator` over **gRPC (`FileTransferGrpcService.DownloadFile`)** to local temp storage.
   - Runs FFmpeg per rendition — **1080p/5000kbps, 720p/2800kbps, 480p/1200kbps, 360p/800kbps** — each to its own HLS playlist + `.ts` segments (10s segment target).
   - Generates a `master.m3u8` referencing all four variant playlists.
   - Streams the encoded `encoded/{movieId}/` tree back to `nas-orchestrator` file-by-file over **gRPC (`FileTransferGrpcService.UploadFile`)**.
   - Publishes `VideoEncodedEvent` (success/failure) on **`video.encoded`**, then cleans up its temp directory regardless of outcome.
3. **Index** — `streaming-service` consumes `video.encoded` and, on success, caches `movieId → encoded/{movieId}/master.m3u8` in Redis (`streaming:playlist:{movieId}`).
4. **Play** — Client calls `streaming-service GET /api/v1/stream/{movieId}`. It resolves the master playlist path from Redis and proxies the raw M3U8 from `nas-orchestrator GET /api/v1/nas-orchestrator/stream/playlist`.
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
| `POST` | `/upload/file` | `path` (query), `file` (multipart) | Upload a single file into the user's scoped workspace |
| `POST` | `/upload/folder` | `path` (query), `files` (multipart array), `relativePaths` (string array) | Upload a directory hierarchy into the user's workspace |
| `GET` | `/download` | `path` (query) | Stream download a file from the user's workspace |
| `GET` | `/preview` | `path` (query) | Stream preview an inline media or text file |
| `GET` | `/list` | `path` (query) | List files and folders in the user's workspace directory |
| `DELETE` | `/delete` | `path` (query) | Delete a file or directory from the user's workspace |

#### Shared Workspace API (`/api/v1/nas-orchestrator/shared`) — *Protected*
| Method | Endpoint | Query / Body Params | Description |
|---|---|---|---|
| `GET` | `/list` | `path` (query) | List files and folders in the shared workspace |
| `POST` | `/upload/file` | `path` (query), `file` (multipart) | Upload a single file into the shared workspace |
| `POST` | `/upload/folder` | `path` (query), `files` (multipart array), `relativePaths` (string array) | Upload a folder hierarchy into the shared workspace |
| `GET` | `/download` | `path` (query) | Stream download a file from the shared workspace |
| `GET` | `/preview` | `path` (query) | Preview an inline file in the shared workspace |
| `DELETE` | `/delete` | `path` (query) | Delete a file or folder from the shared workspace |
| `DELETE` | `/clear` | — | Purge all files in the shared workspace |

#### Workspace Management API (`/api/v1/nas-orchestrator/workspace`) — *Protected*
| Method | Endpoint | Body Params | Description |
|---|---|---|---|
| `POST` | `/create` | JSON `{ "name": "workspace_name" }` | Provision an isolated workspace folder for a user |
| `DELETE` | `/delete` | — | Delete the calling user's root workspace directory |
| `DELETE` | `/clear` | — | Clear all contents inside the calling user's workspace directory |

#### HLS Streaming API (`/api/v1/nas-orchestrator/stream`)
| Method | Endpoint | Query Params | Description |
|---|---|---|---|
| `GET` | `/playlist` | `path` (required), `token` (optional) | Fetch and rewrite HLS playlist (`.m3u8`) with self-referencing absolute URLs |
| `GET` | `/segment` | `path` (required) | Stream `.ts` HLS video segment backed by L1 Caffeine / L2 Redis cache + async N+1 prefetch |

#### System & Health
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/nas-orchestrator/health` | Health check endpoint |

#### gRPC Services (`FileTransferGrpcService` on port `:4092`)
| RPC Method | Type | Description |
|---|---|---|
| `UploadFile` | Client Streaming (`stream FileChunk → UploadResponse`) | Streams binary 64KB file chunks directly into SMB destination |
| `DownloadFile` | Server Streaming (`DownloadRequest → stream FileChunk`) | Streams binary 64KB file chunks directly from SMB storage |

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
| `ValidateToken` | Unary (`ValidateTokenRequest → ValidateTokenResponse`) | Validates opaque token over low-latency binary gRPC |

---

### 3.3 `video-service` (`:8082` HTTP)

| Method | Endpoint | Request Form | Description |
|---|---|---|---|
| `POST` | `/api/v1/videos/upload/{movieId}` | `file` (multipart) | Upload raw video for movie ID; streams to NAS via gRPC and triggers Kafka `video.uploaded` event |

---

### 3.4 `streaming-service` (`:8084` HTTP)

| Method | Endpoint | Path / Headers | Description |
|---|---|---|---|
| `GET` | `/api/v1/stream/{movieId}` | `movieId` (path), `X-Auth-Token` (header) / `token` (query) | Resolve master playlist from Redis and proxy rewritten playlist from `nas-orchestrator` |

---

## 4. Repository layout

```
TrueNAS-Orchestrator-API-PoC/
├── docker-compose.yml        # redis, zookeeper, kafka + all 5 microservices
├── proto/                    # Protobuf definitions (auth.proto, filetransfer.proto)
├── auth-service/             # token issue/validate REST & gRPC server (port 8085 / 4091)
├── nas-orchestrator/         # SMB/TrueNAS gateway, HLS proxy, file CRUD, gRPC server (port 8081 / 4092)
├── video-service/            # upload intake, gRPC client, kafka producer (port 8082)
├── encoding-service/         # kafka consumer, ffmpeg HLS encode, gRPC client (port 8083)
└── streaming-service/        # kafka consumer (index), playlist proxy (port 8084)
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

curl -X POST "http://localhost:8082/api/v1/videos/upload/movie-001" \
  -H "X-Auth-Token: $TOKEN" \
  -F "file=@/path/to/sample.mp4"
```

Once `video.encoded` finishes on Kafka:
```bash
curl "http://localhost:8084/api/v1/stream/movie-001?token=$TOKEN"
```

---

## 6. Points to Consider

- **No TLS anywhere** — inter-service REST/gRPC calls, SMB, and TrueNAS API calls are plaintext in this PoC.
- **Tokens don't expire by default** (`AUTH_TOKEN_TTL_HOURS=0`) and are stored in Redis without revocation UI.
- **`depends_on` in compose is startup-order only** — retries are implemented within clients for Redis/Kafka/gRPC.
- **No horizontal-scaling story for encoding-service** — one encode at a time *per instance* (single-thread executor + paused consumer), and FFmpeg jobs are unbounded in duration with no timeout/kill logic.