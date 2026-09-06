package com.tamojit.streamingservice.controller;

import com.tamojit.streamingservice.security.TokenValidationFilter;
import com.tamojit.streamingservice.service.StreamingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/stream")
public class StreamingController {
    private final StreamingService streamingService;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String MASTER_PLAYLIST_KEY_PREFIX = "streaming:playlist:";

    /**
     * Resolves the movie's NAS-relative playlist path from Redis, then proxies
     * the raw M3U8 content through from nas-orchestrator.
     *
     * Segment requests are NOT routed through streaming-service — the playlist
     * returned by nas-orchestrator already rewrites segment URIs to point
     * directly at /api/v1/nas-orchestrator/stream/segment, so the client
     * fetches segments without touching this service again.
     */
    @GetMapping("/{movieId}")
    public ResponseEntity<String> getPlaylist(@PathVariable String movieId, HttpServletRequest request) {
        log.info("Playlist request for movieId: {}", movieId);

        String playlistPath = redisTemplate.opsForValue().get(MASTER_PLAYLIST_KEY_PREFIX + movieId);
        if (playlistPath == null) {
            log.warn("No playlist registered for movieId: {}", movieId);
            return ResponseEntity.notFound().build();
        }

        String token = (String) request.getAttribute(TokenValidationFilter.TOKEN_ATTRIBUTE);

        log.info("Proxying playlist for movieId: {} at path: {}", movieId, playlistPath);
        return ResponseEntity.ok()
            .header("Content-Type", "application/x-mpegURL")
            .body(streamingService.getPlaylist(playlistPath, token));
    }
}
