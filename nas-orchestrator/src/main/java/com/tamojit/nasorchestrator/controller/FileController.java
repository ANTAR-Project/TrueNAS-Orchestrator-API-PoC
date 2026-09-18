package com.tamojit.nasorchestrator.controller;

import com.tamojit.nasorchestrator.dto.FileListResponse;
import com.tamojit.nasorchestrator.dto.FileUploadResponse;
import com.tamojit.nasorchestrator.dto.FolderUploadResponse;
import com.tamojit.nasorchestrator.service.FileService;
import com.tamojit.nasorchestrator.util.MimeTypeResolver;
import com.tamojit.nasorchestrator.util.ScopeWorkspaceByUsername;
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
    private final ScopeWorkspaceByUsername scopeWorkspaceByUsername;

    public FileController(
        FileService fileService,
        MimeTypeResolver mimeTypeResolver,
        ScopeWorkspaceByUsername scopeWorkspaceByUsername
    ) {
        this.fileService = fileService;
        this.mimeTypeResolver = mimeTypeResolver;
        this.scopeWorkspaceByUsername = scopeWorkspaceByUsername;
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
        return ResponseEntity.ok(fileService.upload(scopeWorkspaceByUsername.scopedPath(request, path), file));
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
        return ResponseEntity.ok(fileService.uploadFolder(scopeWorkspaceByUsername.scopedPath(request, path), files, relativePaths));
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
        fileService.download(scopeWorkspaceByUsername.scopedPath(request, path), response);
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
        String scoped = scopeWorkspaceByUsername.scopedPath(request, path);
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
        return ResponseEntity.ok(fileService.list(scopeWorkspaceByUsername.scopedPath(request, path)));
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
        fileService.delete(scopeWorkspaceByUsername.scopedPath(request, path));
        return ResponseEntity.noContent().build();
    }
}
