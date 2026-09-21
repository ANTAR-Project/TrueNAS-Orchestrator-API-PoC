package com.tamojit.encodingservice.client;

import com.google.protobuf.ByteString;
import com.tamojit.grpc.filetransfer.DownloadRequest;
import com.tamojit.grpc.filetransfer.FileChunk;
import com.tamojit.grpc.filetransfer.FileTransferGrpcServiceGrpc;
import com.tamojit.grpc.filetransfer.UploadResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

//@Component
//public class NasOrchestratorClient {
//    private final RestClient restClient;
//    private final ServiceAuthTokenProvider tokenProvider;
//
//    public NasOrchestratorClient(
//        @Value("${nas.orchestrator.base-url}") String baseUrl,
//        ServiceAuthTokenProvider tokenProvider
//    ) {
//        // Apache HC5: FileSystemResource exposes contentLength() so HC5 sends a
//        // proper Content-Length header per file — no heap buffering, no chunked framing.
//        this.restClient = RestClient.builder()
//            .baseUrl(baseUrl)
//            .requestFactory(new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()))
//            .build();
//        this.tokenProvider = tokenProvider;
//    }
//
//    public void downloadToFile(String relativePath, Path destination) throws IOException {
//        byte[] bytes = restClient.get()
//            .uri(uriBuilder -> uriBuilder
//                .path("/api/v1/nas-orchestrator/files/download")
//                .queryParam("path", relativePath)
//                .build())
//            .header("X-Auth-Token", tokenProvider.getToken())
//            .retrieve()
//            .body(byte[].class);
//
//        if (bytes == null) {
//            throw new IOException("Failed to download file: Received empty/null body from server for path " + relativePath);
//        }
//
//        Files.write(destination, bytes);
//    }
//
//    // One POST per file — mirrors how the S3 SDK worked (one PUT per object).
//    // Avoids bundling all HLS segments into a single multipart body that Tomcat
//    // would have to buffer entirely in heap before any upload could begin.
//    public void uploadFolder(String nasBasePath, File localDir) throws IOException {
//        uploadRecursively(nasBasePath, localDir, localDir);
//    }
//
//    private void uploadRecursively(String nasBasePath, File root, File dir) throws IOException {
//        File[] files = dir.listFiles();
//        if (files == null) {
//            return;
//        }
//
//        for (File file : files) {
//            if (file.isDirectory()) {
//                uploadRecursively(nasBasePath, root, file);
//                continue;
//            }
//
//            // e.g. "1080p/segment_000.ts" or "master.m3u8"
//            String destDir = getString(nasBasePath, root, file);
//
//            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
//            body.add("path", destDir);
//            body.add("file", new FileSystemResource(file)); // filename taken from File.getName()
//
//            restClient.post()
//                .uri("/api/v1/nas-orchestrator/files/upload/file")
//                .contentType(MediaType.MULTIPART_FORM_DATA)
//                .header("X-Auth-Token", tokenProvider.getToken())
//                .body(body)
//                .retrieve()
//                .toBodilessEntity();
//        }
//    }
//
//    private String getString(String nasBasePath, File root, File file) {
//        String relativePath = root.toPath()
//            .relativize(file.toPath())
//            .toString()
//            .replace("\\", "/");
//
//        // Resolve destination directory on the NAS:
//        //   "1080p/segment_000.ts" → destDir = nasBasePath + "/1080p"
//        //   "master.m3u8"          → destDir = nasBasePath
//        int lastSlash = relativePath.lastIndexOf('/');
//        String destDir = lastSlash >= 0
//            ? nasBasePath + "/" + relativePath.substring(0, lastSlash)
//            : nasBasePath;
//        return destDir;
//    }
//}

@Component
public class NasOrchestratorClient {
    private static final Logger log = LoggerFactory.getLogger(NasOrchestratorClient.class);

    private final FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferGrpcAsyncStub;
    private final FileTransferGrpcServiceGrpc.FileTransferGrpcServiceBlockingStub fileTransferGrpcBlockingStub;

    public NasOrchestratorClient(
        FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferGrpcAsyncStub,
        FileTransferGrpcServiceGrpc.FileTransferGrpcServiceBlockingStub fileTransferGrpcBlockingStub
    ) {
        this.fileTransferGrpcAsyncStub = fileTransferGrpcAsyncStub;
        this.fileTransferGrpcBlockingStub = fileTransferGrpcBlockingStub;
    }

    public void downloadToFile(String relativePath, Path destination) throws IOException {
        log.info("Starting gRPC file download from NAS: path={}, destination={}", relativePath, destination);
        DownloadRequest request = DownloadRequest.newBuilder()
            .setPath(relativePath)
            .build();

        int chunkCount = 0;
        long totalBytes = 0;

        // blocking call for downloading chunks stream
        try (OutputStream outputStream = Files.newOutputStream(destination)) {
            Iterator<FileChunk> fileChunks = fileTransferGrpcBlockingStub.downloadFile(request);

            while (fileChunks.hasNext()) {
                FileChunk fileChunk = fileChunks.next();
                int chunkSize = fileChunk.getContent().size();
                fileChunk.getContent().writeTo(outputStream);
                chunkCount++;
                totalBytes += chunkSize;
                log.info("Received download chunk #{} ({} bytes) for path: {}", chunkCount, chunkSize, relativePath);
            }

            log.info("Successfully downloaded file from NAS: path={}, totalChunks={}, totalBytes={}", relativePath, chunkCount, totalBytes);
        } catch (StatusRuntimeException e) {
            log.error("gRPC download stream error for path {}: status={}, message={}", relativePath, e.getStatus().getCode(), e.getMessage());
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new FileNotFoundException("Not found on NAS: " + relativePath);
            }
            throw new IOException("Download failed: " + e.getMessage(), e);
        }
    }

    // One client-streaming call per file
    // avoids bundling every HLS segment into one giant request that has to be fully assembled before any upload begins.
    public void uploadFolder(String nasBasePath, File localDir) throws IOException {
        log.info("Starting gRPC folder upload to NAS: nasBasePath={}, localDir={}", nasBasePath, localDir.getAbsolutePath());
        uploadRecursively(nasBasePath, localDir, localDir);
        log.info("Completed gRPC folder upload to NAS: nasBasePath={}", nasBasePath);
    }

    private void uploadRecursively(String nasBasePath, File root, File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                uploadRecursively(nasBasePath, root, file);
                continue;
            }

            // e.g. "1080p/segment_000.ts" or "master.m3u8"
            String destDir = resolveDestDir(nasBasePath, root, file);
            String destinationPath = destDir + "/" + file.getName();

            uploadSingleFile(destinationPath, file);
        }
    }

    public void uploadSingleFile(String destinationPath, File file) throws IOException {
        log.info("Starting gRPC file upload to NAS: destination={}, localPath={}, size={} bytes", destinationPath, file.getAbsolutePath(), file.length());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        // async streaming call for uploading chunks stream
        StreamObserver<FileChunk> requestObserver = fileTransferGrpcAsyncStub.uploadFile(new StreamObserver<>() {
            @Override
            public void onNext(UploadResponse value) {
                log.debug("Received upload response for destination {}: {}", destinationPath, value.getMessage());
            }

            @Override
            public void onError(Throwable t) {
                log.error("gRPC upload stream error for destination {}: {}", destinationPath, t.getMessage());
                error.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                log.debug("gRPC upload stream completed by server for destination: {}", destinationPath);
                latch.countDown();
            }
        });

        int chunkCount = 0;
        long totalBytes = 0;

        try (InputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            boolean first = true;
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                FileChunk.Builder chunkBuilder = FileChunk.newBuilder()
                    .setContent(ByteString.copyFrom(buffer, 0, bytesRead));

                if (first) {
                    first = false;
                    chunkBuilder.setDestinationPath(destinationPath);
                }

                chunkCount++;
                totalBytes += bytesRead;
                log.info("Uploading chunk #{} ({} bytes) for destination: {}", chunkCount, bytesRead, destinationPath);
                requestObserver.onNext(chunkBuilder.build());
            }

            // An empty file never enters the loop above, so destination_path would
            // never be sent — cover that case with a final metadata-only chunk.
            if (first) {
                requestObserver.onNext(FileChunk.newBuilder()
                    .setDestinationPath(destinationPath)
                    .build());
                chunkCount++;
            }

            requestObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to stream upload chunks for destination {}: {}", destinationPath, e.getMessage());
            requestObserver.onError(e);
            throw new IOException("Upload failed for " + destinationPath + ": " + e.getMessage(), e);
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload interrupted for " + destinationPath, e);
        }

        if (error.get() != null) {
            throw new IOException("Upload failed for " + destinationPath + ": " + error.get().getMessage(), error.get());
        }

        log.info("Successfully uploaded file to NAS: destination={}, totalChunks={}, totalBytes={}", destinationPath, chunkCount, totalBytes);
    }

    private String resolveDestDir(String nasBasePath, File root, File file) {
        String relativePath = root.toPath()
            .relativize(file.toPath())
            .toString()
            .replace("\\", "/");

        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash >= 0
            ? nasBasePath + "/" + relativePath.substring(0, lastSlash)
            : nasBasePath;
    }
}