package com.tamojit.nasorchestrator.controller;

import com.tamojit.nasorchestrator.dto.CreateFolderResponse;
import com.tamojit.nasorchestrator.dto.FileListResponse;
import com.tamojit.nasorchestrator.dto.FileUploadResponse;
import com.tamojit.nasorchestrator.dto.FolderUploadResponse;
import com.tamojit.nasorchestrator.service.SharedWorkspaceService;
import com.tamojit.nasorchestrator.util.MimeTypeResolver;
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
@RequestMapping("/api/v1/nas-orchestrator/shared")
@Validated
public class SharedWorkspaceController {
    private static final String NO_TRAVERSAL_REGEX = "^(?!.*\\.\\.).*$";
    private static final String NO_TRAVERSAL_MSG = "Path cannot contain '..' segments";
    private static final String NO_BACKSLASH_REGEX = "^[^\\\\]*$";
    private static final String NO_BACKSLASH_MSG = "Path cannot contain backslashes";
    private static final String NO_LEADING_SLASH_REGEX = "^(?!/).*$";
    private static final String NO_LEADING_SLASH_MSG = "Path must be relative — do not start with '/'";

    private final SharedWorkspaceService sharedWorkspaceService;
    private final MimeTypeResolver mimeTypeResolver;

    public SharedWorkspaceController(
        SharedWorkspaceService sharedWorkspaceService,
        MimeTypeResolver mimeTypeResolver
    ) {
        this.sharedWorkspaceService = sharedWorkspaceService;
        this.mimeTypeResolver = mimeTypeResolver;
    }

    @GetMapping("/list")
    public ResponseEntity<FileListResponse> list(
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path
    ) throws IOException {
        return ResponseEntity.ok(sharedWorkspaceService.listSharedWorkspace(path));
    }

    @PostMapping("/upload/file")
    public ResponseEntity<FileUploadResponse> uploadFile(
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path,
        @RequestParam("file") MultipartFile file
    ) throws IOException {
        return ResponseEntity.ok(sharedWorkspaceService.uploadToSharedWorkspace(path, file));
    }

    @PostMapping("/upload/folder")
    public ResponseEntity<FolderUploadResponse> uploadFolder(
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path,
        @RequestParam("files") MultipartFile[] files,
        @RequestParam("relativePaths") String[] relativePaths
    ) throws IOException {
        return ResponseEntity.ok(sharedWorkspaceService.uploadFolderToSharedWorkspace(path, files, relativePaths));
    }

    @GetMapping("/download")
    public void download(
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path,
        HttpServletResponse response
    ) throws IOException {
        sharedWorkspaceService.downloadFromSharedWorkspace(path, response);
    }

    @GetMapping("/preview")
    public ResponseEntity<InputStreamResource> preview(
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path
    ) throws IOException {
        String filename = path.substring(path.lastIndexOf('/') + 1);

        if (!mimeTypeResolver.isPreviewable(filename)) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }

        InputStream inputStream = sharedWorkspaceService.previewFileInSharedWorkspace(path);
        MediaType mediaType = mimeTypeResolver.resolve(filename);

        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(filename).build().toString())
            .body(new InputStreamResource(inputStream));
    }

    @DeleteMapping("/delete")
    public ResponseEntity<Void> delete(
        @RequestParam(value = "path", defaultValue = "")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path
    ) throws IOException {
        sharedWorkspaceService.deleteFromSharedWorkspace(path);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mkdir")
    public ResponseEntity<CreateFolderResponse> mkdir(
        @RequestParam("path")
        @Pattern(regexp = NO_TRAVERSAL_REGEX, message = NO_TRAVERSAL_MSG)
        @Pattern(regexp = NO_BACKSLASH_REGEX, message = NO_BACKSLASH_MSG)
        @Pattern(regexp = NO_LEADING_SLASH_REGEX, message = NO_LEADING_SLASH_MSG)
        String path
    ) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(sharedWorkspaceService.createFolderInSharedWorkspace(path));
    }

    @DeleteMapping("/clear")
    public ResponseEntity<Void> clear() throws IOException {
        sharedWorkspaceService.clearSharedWorkspace();
        return ResponseEntity.noContent().build();
    }
}
