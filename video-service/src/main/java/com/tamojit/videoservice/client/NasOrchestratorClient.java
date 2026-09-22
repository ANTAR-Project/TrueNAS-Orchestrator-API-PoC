package com.tamojit.videoservice.client;

import com.google.protobuf.ByteString;
import com.tamojit.grpc.filetransfer.FileChunk;
import com.tamojit.grpc.filetransfer.FileTransferGrpcServiceGrpc;
import com.tamojit.grpc.filetransfer.UploadResponse;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class NasOrchestratorClient {
    private static final Logger log = LoggerFactory.getLogger(NasOrchestratorClient.class);

    private final FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferGrpcStub;

    public NasOrchestratorClient(
        FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferGrpcStub
    ) {
        this.fileTransferGrpcStub = fileTransferGrpcStub;
    }

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
