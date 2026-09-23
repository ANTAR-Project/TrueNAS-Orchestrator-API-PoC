package com.tamojit.videoservice.controller;

import com.tamojit.videoservice.service.VideoService;
import com.tamojit.videoservice.util.ScopeWorkspaceByUsername;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/videos")
@Slf4j
@RequiredArgsConstructor
@Validated
public class VideoController {
    private static final String NO_TRAVERSAL_REGEX = "^(?!.*\\.\\.).*$";
    private static final String NO_TRAVERSAL_MSG = "Path cannot contain '..' segments";
    private static final String NO_BACKSLASH_REGEX = "^[^\\\\]*$";
    private static final String NO_BACKSLASH_MSG = "Path cannot contain backslashes";
    private static final String NO_LEADING_SLASH_REGEX = "^(?!/).*$";
    private static final String NO_LEADING_SLASH_MSG = "Path must be relative — do not start with '/'";

    private final VideoService videoService;

    // upload multipart file (video)
    @PostMapping("/upload")
    public ResponseEntity<String> uploadVideo(
        HttpServletRequest request,
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path,
        @RequestParam("file") MultipartFile file
    ) throws IOException {
        log.info("Video upload request of size = {} MB", file.getSize() / (1024 * 1024));

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("File is empty");
        }

        String videoKey = videoService.uploadVideo(path, file, request);

        return ResponseEntity.ok("video uploaded successfully! Key = " + videoKey + " : Encoding started automatically via Kafka");
    }
}
