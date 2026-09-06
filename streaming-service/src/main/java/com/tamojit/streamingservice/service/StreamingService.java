package com.tamojit.streamingservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingService {
    private final RestClient nasOrchestratorRestClient; // bean, baseUrl = nas.orchestrator.base-url

    /**
     * Proxies the HLS master (or variant) playlist from nas-orchestrator.
     *
     * @param path NAS-relative path to the playlist file,
     *             e.g. "encoded/{movieId}/master.m3u8" (resolved from Redis by the controller)
     * @return raw M3U8 content returned by nas-orchestrator
     */
    public String getPlaylist(String path, String token) {
        log.info("Fetching playlist from nas-orchestrator for path: {}", path);
        return nasOrchestratorRestClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/v1/nas-orchestrator/stream/playlist")
                .queryParam("path", path)
                .queryParamIfPresent("token", Optional.ofNullable(token))
                .build())
            .retrieve()
            .body(String.class);
    }
}
