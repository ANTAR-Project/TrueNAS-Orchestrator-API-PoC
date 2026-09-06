package com.tamojit.nasorchestrator.controller;

import com.tamojit.nasorchestrator.service.StreamingService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/nas-orchestrator/stream")
public class StreamingController {
    private static final String NO_TRAVERSAL_REGEX = "^(?!.*\\.\\.).*$";
    private static final String NO_TRAVERSAL_MSG = "Path cannot contain '..' segments";
    private static final String NO_BACKSLASH_REGEX = "^[^\\\\]*$";
    private static final String NO_BACKSLASH_MSG = "Path cannot contain backslashes";

    private final StreamingService streamingService;

    public StreamingController(StreamingService streamingService) {
        this.streamingService = streamingService;
    }

    @GetMapping("/playlist")
    public ResponseEntity<String> playlist(
        @RequestParam("path")
        @NotBlank(message = "Path is required")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        String path,
        @RequestParam(value = "token", required = false) String token
    ) throws IOException {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/x-mpegURL")
            .body(streamingService.getRewrittenPlaylist(path, token));
    }

    @GetMapping("/segment")
    public ResponseEntity<byte[]> segment(
        @RequestParam("path")
        @NotBlank(message = "Path is required")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        String path
    ) throws IOException {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "video/MP2T")
            .body(streamingService.getSegment(path));
    }
}
