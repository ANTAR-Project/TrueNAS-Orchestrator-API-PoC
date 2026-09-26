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