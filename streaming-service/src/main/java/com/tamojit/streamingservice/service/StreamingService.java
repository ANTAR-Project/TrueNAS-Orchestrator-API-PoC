package com.tamojit.streamingservice.service;

import com.tamojit.grpc.streaming.PlaylistRequest;
import com.tamojit.grpc.streaming.PlaylistResponse;
import com.tamojit.grpc.streaming.StreamingGrpcServiceGrpc;
import com.tamojit.streamingservice.grpc.PerCallTokenAttacher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingService {
    private final StreamingGrpcServiceGrpc.StreamingGrpcServiceBlockingStub streamingGrpcStub;
    private final PerCallTokenAttacher tokenAttacher;

    /**
     * Proxies the HLS master (or variant) playlist from nas-orchestrator.
     *
     * @param path NAS-relative path to the playlist file,
     *             e.g. "encoded/{movieId}/master.m3u8" (resolved from Redis by the controller)
     * @return raw M3U8 content returned by nas-orchestrator
     */
    public String getPlaylist(String path, String token) {
        log.info("Fetching playlist from nas-orchestrator for path: {}", path);

        PlaylistResponse response = tokenAttacher.attach(streamingGrpcStub, token)
            .getRewrittenPlaylist(
                PlaylistRequest.newBuilder()
                    .setPath(path)
                    .setToken(token != null ? token : "")
                    .build()
            );

        return response.getContent();
    }
}
