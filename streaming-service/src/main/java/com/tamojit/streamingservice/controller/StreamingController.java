package com.tamojit.streamingservice.controller;

import com.tamojit.streamingservice.dto.PlaylistDto;
import com.tamojit.streamingservice.security.TokenValidationFilter;
import com.tamojit.streamingservice.service.PlaylistService;
import com.tamojit.streamingservice.service.StreamingService;
import com.tamojit.streamingservice.util.ScopeWorkspaceByUsername;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
@Validated
@RequestMapping("/api/v1/stream")
public class StreamingController {
    private static final String NO_TRAVERSAL_REGEX = "^(?!.*\\.\\.).*$";
    private static final String NO_TRAVERSAL_MSG = "Path cannot contain '..' segments";
    private static final String NO_BACKSLASH_REGEX = "^[^\\\\]*$";
    private static final String NO_BACKSLASH_MSG = "Path cannot contain backslashes";
    private static final String NO_LEADING_SLASH_REGEX = "^(?!/).*$";
    private static final String NO_LEADING_SLASH_MSG = "Path must be relative — do not start with '/'";
    private static final String MASTER_PLAYLIST_KEY_PREFIX = "streaming:playlist:";

    private final StreamingService streamingService;
    private final PlaylistService playlistService;
    private final ScopeWorkspaceByUsername scopeWorkspaceByUsername;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Resolves the movie's NAS-relative playlist path from Redis, then proxies the raw M3U8 content through from nas-orchestrator.
     * Segment requests are NOT routed through streaming-service — the playlist
     * returned by nas-orchestrator already rewrites segment URIs to point
     * directly at /api/v1/nas-orchestrator/stream/segment, so the client
     * fetches segments without touching this service again.
     */
    @GetMapping("")
    public ResponseEntity<String> getPlaylist(
        HttpServletRequest request,
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path
    ) {
        String scopedPath = scopeWorkspaceByUsername.scopedPath(request, path);
        log.info("Playlist request for movieId: {}", scopedPath);

        String playlistPath = redisTemplate.opsForValue().get(MASTER_PLAYLIST_KEY_PREFIX + scopedPath);
        if (playlistPath == null) {
            log.warn("No playlist registered for movieId: {}", scopedPath);
            return ResponseEntity.notFound().build();
        }

        String token = (String) request.getAttribute(TokenValidationFilter.TOKEN_ATTRIBUTE);

        log.info("Proxying playlist for movieId: {} at path: {}", scopedPath, playlistPath);
        return ResponseEntity.ok()
            .header("Content-Type", "application/x-mpegURL")
            .body(streamingService.getPlaylist(playlistPath, token));
    }

    @GetMapping("/playlists")
    public ResponseEntity<List<PlaylistDto>> getUserPlaylists(HttpServletRequest request) {
        String username = scopeWorkspaceByUsername.workspaceRoot(request);
        List<PlaylistDto> playLists = playlistService.getPlaylistsForUser(username);

        return ResponseEntity.ok(playLists);
    }
}
