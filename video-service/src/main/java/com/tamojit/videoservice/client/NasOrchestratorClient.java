package com.tamojit.videoservice.client;

import com.google.protobuf.ByteString;
import com.tamojit.grpc.filetransfer.FileChunk;
import com.tamojit.grpc.filetransfer.FileTransferGrpcServiceGrpc;
import com.tamojit.grpc.filetransfer.UploadResponse;
import com.tamojit.videoservice.security.ServiceAuthTokenProvider;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class NasOrchestratorClient {
    private static final Logger log = LoggerFactory.getLogger(NasOrchestratorClient.class);

    private final RestClient restClient;
    private final ServiceAuthTokenProvider tokenProvider;
    private final FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferGrpcStub;

    public NasOrchestratorClient(
        @Value("${nas.orchestrator.base-url}") String baseUrl,
        ServiceAuthTokenProvider tokenProvider,
        FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferGrpcStub
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
        this.fileTransferGrpcStub = fileTransferGrpcStub;
    }

    // returns the relative NAS path the file was stored under: {dirPath}/{originalFilename}
//    public String uploadFile(String dirPath, MultipartFile file) throws IOException {
//        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
//        body.add("path", dirPath);
//        body.add("file", new ByteArrayResource(file.getBytes()) {
//            @Override
//            public String getFilename() {
//                return file.getOriginalFilename();
//            }
//        });
//
//        restClient.post()
//            .uri("/api/v1/nas-orchestrator/files/upload/file")
//            .contentType(MediaType.MULTIPART_FORM_DATA)
//            .header("X-Auth-Token", tokenProvider.getToken())
//            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
//            .body(body)
//            .retrieve()
//            .toBodilessEntity();
//
//        return dirPath + "/" + file.getOriginalFilename();
//    }

    // Streaming file as 64KB chunks over gRPC
    public String uploadFile(String dirPath, MultipartFile file) throws IOException {
        String destinationPath = dirPath + "/" + file.getOriginalFilename();
        log.info("Starting gRPC file upload to NAS: destination={}, size={} bytes", destinationPath, file.getSize());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<FileChunk> requestObserver = fileTransferGrpcStub.uploadFile(new StreamObserver<>() {
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

        try (InputStream inputStream = file.getInputStream()) {
            byte[] buffer = new byte[64 * 1024];
            boolean first = true;
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                FileChunk.Builder fileChunkBuilder = FileChunk.newBuilder()
                    .setContent(ByteString.copyFrom(buffer, 0, bytesRead));

                if (first) {
                    first = false;
                    fileChunkBuilder.setDestinationPath(destinationPath);
                }

                chunkCount++;
                totalBytes += bytesRead;
                log.info("Uploading chunk #{} ({} bytes) for destination: {}", chunkCount, bytesRead, destinationPath);
                requestObserver.onNext(fileChunkBuilder.build());
            }

            requestObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to stream upload chunks for destination {}: {}", destinationPath, e.getMessage());
            requestObserver.onError(e);
            throw new IOException("Upload failed: " + e.getMessage(), e);
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload interrupted", e);
        }

        if (error.get() != null) {
            throw new IOException("Upload failed: " + error.get().getMessage(), error.get());
        }

        log.info("Successfully uploaded file to NAS: destination={}, totalChunks={}, totalBytes={}", destinationPath, chunkCount, totalBytes);
        return destinationPath;
    }
}
