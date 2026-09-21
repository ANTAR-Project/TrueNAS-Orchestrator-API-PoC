package com.tamojit.nasorchestrator.grpc;

import com.google.protobuf.ByteString;
import com.tamojit.grpc.filetransfer.DownloadRequest;
import com.tamojit.grpc.filetransfer.FileChunk;
import com.tamojit.grpc.filetransfer.FileTransferGrpcServiceGrpc;
import com.tamojit.grpc.filetransfer.UploadResponse;
import com.tamojit.nasorchestrator.client.SmbFileClient;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Service
public class FileTransferGrpcServer extends FileTransferGrpcServiceGrpc.FileTransferGrpcServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(FileTransferGrpcServer.class);
    
    private final SmbFileClient smbFileClient;

    public FileTransferGrpcServer(SmbFileClient smbFileClient) {
        this.smbFileClient = smbFileClient;
    }

    @Override
    public StreamObserver<FileChunk> uploadFile(StreamObserver<UploadResponse> responseObserver) {
        return new StreamObserver<>() {
            private String destinationPath;
            private OutputStream outputStream;
            private int chunkCount = 0;
            private long totalBytes = 0;

            // Streaming chunks directly to SMB, instead of storing in memory
            @Override
            public void onNext(FileChunk fileChunk) {
                try {
                    if (outputStream == null) {
                        if (fileChunk.getDestinationPath().isEmpty()) {
                            log.warn("Upload chunk rejected: First chunk missing destination_path");
                            responseObserver.onError(
                                Status.INVALID_ARGUMENT
                                    .withDescription("First chunk must carry destination_path")
                                    .asRuntimeException()
                            );

                            return;
                        }

                        destinationPath = fileChunk.getDestinationPath();
                        log.info("Receiving gRPC upload stream for destination: {}", destinationPath);
                        outputStream = smbFileClient.openForWrite(destinationPath);
                    }

                    int chunkSize = fileChunk.getContent().size();
                    fileChunk.getContent().writeTo(outputStream);
                    chunkCount++;
                    totalBytes += chunkSize;
                    log.info("Received upload chunk #{} ({} bytes) for destination: {}", chunkCount, chunkSize, destinationPath);
                } catch (IOException e) {
                    log.error("Error writing chunk for destination {}: {}", destinationPath, e.getMessage());
                    closeQuietly();
                    responseObserver.onError(
                        Status.INTERNAL
                            .withDescription("Failed to write chunk: " + e.getMessage())
                            .asRuntimeException()
                    );
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error("gRPC upload stream error for destination {}: {}", destinationPath, t.getMessage());
                closeQuietly();
            }

            @Override
            public void onCompleted() {
                if (outputStream == null) {
                    log.warn("Upload stream completed without receiving any chunks");
                    responseObserver.onError(
                        Status.INVALID_ARGUMENT
                            .withDescription("No chunks received")
                            .asRuntimeException()
                    );

                    return;
                }

                try {
                    outputStream.close();
                    log.info("Completed receiving gRPC upload for destination: {}, totalChunks={}, totalBytes={}", destinationPath, chunkCount, totalBytes);
                    responseObserver.onNext(
                        UploadResponse.newBuilder()
                            .setPath(destinationPath)
                            .setMessage("uploaded")
                            .build()
                    );

                    responseObserver.onCompleted();
                } catch (IOException e) {
                    log.error("Failed to finalize upload for destination {}: {}", destinationPath, e.getMessage());
                    responseObserver.onError(
                        Status.INTERNAL
                            .withDescription("Failed to finalize upload: " + e.getMessage())
                            .asRuntimeException()
                    );
                }
            }

            private void closeQuietly() {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        };
    }

    @Override
    public void downloadFile(DownloadRequest request, StreamObserver<FileChunk> responseObserver) {
        log.info("Handling gRPC download stream request for path: {}", request.getPath());
        int chunkCount = 0;
        long totalBytes = 0;
        try (InputStream inputStream = smbFileClient.preview(request.getPath())) {
            byte[] buffer = new byte[64 * 1024]; // 64KB per chunk
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                chunkCount++;
                totalBytes += bytesRead;
                log.info("Sending download chunk #{} ({} bytes) for path: {}", chunkCount, bytesRead, request.getPath());
                responseObserver.onNext(
                    FileChunk.newBuilder()
                        .setContent(ByteString.copyFrom(buffer, 0, bytesRead))
                        .build()
                );
            }

            responseObserver.onCompleted();
            log.info("Completed sending gRPC download stream for path: {}, totalChunks={}, totalBytes={}", request.getPath(), chunkCount, totalBytes);
        } catch (FileNotFoundException e) {
            log.warn("gRPC download file not found: path={}, error={}", request.getPath(), e.getMessage());
            responseObserver.onError(Status.NOT_FOUND
                .withDescription(e.getMessage())
                .asRuntimeException());
        } catch (IOException e) {
            log.error("gRPC download stream error for path {}: {}", request.getPath(), e.getMessage());
            responseObserver.onError(Status.INTERNAL
                .withDescription(e.getMessage())
                .asRuntimeException());
        }
    }
}
