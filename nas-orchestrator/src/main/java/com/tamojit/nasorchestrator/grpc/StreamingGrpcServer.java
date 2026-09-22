package com.tamojit.nasorchestrator.grpc;

import com.tamojit.grpc.streaming.PlaylistRequest;
import com.tamojit.grpc.streaming.PlaylistResponse;
import com.tamojit.grpc.streaming.StreamingGrpcServiceGrpc;
import com.tamojit.nasorchestrator.service.StreamingService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;

@Service
public class StreamingGrpcServer extends StreamingGrpcServiceGrpc.StreamingGrpcServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(StreamingGrpcServer.class);

    private final StreamingService streamingService;

    public StreamingGrpcServer(StreamingService streamingService) {
        this.streamingService = streamingService;
    }

    @Override
    public void getRewrittenPlaylist(PlaylistRequest request, StreamObserver<PlaylistResponse> responseObserver) {
        try {
            log.info("Rewriting Playlist for request: {}", request);

            String content = streamingService.getRewrittenPlaylist(request.getPath(), request.getToken());

            responseObserver.onNext(PlaylistResponse.newBuilder().setContent(content).build());
            responseObserver.onCompleted();
        } catch (FileNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND
                .withDescription(e.getMessage())
                .asRuntimeException());
        } catch (IOException e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription(e.getMessage())
                .asRuntimeException());
        }
    }
}
