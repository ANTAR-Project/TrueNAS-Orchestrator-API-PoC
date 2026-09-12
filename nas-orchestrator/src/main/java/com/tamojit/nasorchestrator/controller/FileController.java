package com.tamojit.nasorchestrator.controller;

import com.tamojit.nasorchestrator.dto.FileListResponse;
import com.tamojit.nasorchestrator.dto.FileUploadResponse;
import com.tamojit.nasorchestrator.dto.FolderUploadResponse;
import com.tamojit.nasorchestrator.security.TokenValidationFilter;
import com.tamojit.nasorchestrator.service.FileService;
import com.tamojit.nasorchestrator.util.MimeTypeResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/api/v1/nas-orchestrator/files")
@Validated
public class FileController {
    private static final String NO_TRAVERSAL_REGEX = "^(?!.*\\.\\.).*$";
    private static final String NO_TRAVERSAL_MSG = "Path cannot contain '..' segments";
    private static final String NO_BACKSLASH_REGEX = "^[^\\\\]*$";
    private static final String NO_BACKSLASH_MSG = "Path cannot contain backslashes";
    private static final String NO_LEADING_SLASH_REGEX = "^(?!/).*$";
    private static final String NO_LEADING_SLASH_MSG = "Path must be relative — do not start with '/'";

    private final FileService fileService;
    private final MimeTypeResolver mimeTypeResolver;

    public FileController(FileService fileService, MimeTypeResolver mimeTypeResolver) {
        this.fileService = fileService;
        this.mimeTypeResolver = mimeTypeResolver;
    }

    // Workspace scoping from token attribute (username)
    private String scopedPath(HttpServletRequest request, String rawPath) {
        String username = (String) request.getAttribute(TokenValidationFilter.USERNAME_ATTRIBUTE);

        if (username == null || username.isBlank()) {
            throw new IllegalStateException("No authenticated username on request");
        }

        String cleanRaw = rawPath == null ? "" : rawPath;
        if (cleanRaw.isEmpty()) {
            return username;
        }

        return username + "/" + cleanRaw;
    }

    @PostMapping("/upload/file")
    public ResponseEntity<FileUploadResponse> uploadFile(
        HttpServletRequest request,
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path,
        @RequestParam("file") MultipartFile file
    ) throws IOException {
        return ResponseEntity.ok(fileService.upload(scopedPath(request, path), file));
    }

    @PostMapping("/upload/folder")
    public ResponseEntity<FolderUploadResponse> uploadFolder(
        HttpServletRequest request,
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path,
        @RequestParam("files") MultipartFile[] files,
        @RequestParam("relativePaths") String[] relativePaths
    ) throws IOException {
        return ResponseEntity.ok(fileService.uploadFolder(scopedPath(request, path), files, relativePaths));
    }

    @GetMapping("/download")
    public void download(
        HttpServletRequest request,
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path,
        HttpServletResponse response
    ) throws IOException {
        fileService.download(scopedPath(request, path), response);
    }

    @GetMapping("/preview")
    public ResponseEntity<InputStreamResource> preview(
        HttpServletRequest request,
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path
    ) throws IOException {
        String scoped = scopedPath(request, path);
        String filename = scoped.substring(scoped.lastIndexOf('/') + 1);

        if (!mimeTypeResolver.isPreviewable(filename)) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }

        InputStream inputStream = fileService.preview(scoped);
        MediaType mediaType = mimeTypeResolver.resolve(filename);

        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(filename).build().toString())
            .body(new InputStreamResource(inputStream));
    }

    @GetMapping("/list")
    public ResponseEntity<FileListResponse> list(
        HttpServletRequest request,
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path
    ) throws IOException {
        return ResponseEntity.ok(fileService.list(scopedPath(request, path)));
    }

    @DeleteMapping("/delete")
    public ResponseEntity<Void> delete(
        HttpServletRequest request,
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path
    ) throws IOException {
        fileService.delete(scopedPath(request, path));
        return ResponseEntity.noContent().build();
    }
}
