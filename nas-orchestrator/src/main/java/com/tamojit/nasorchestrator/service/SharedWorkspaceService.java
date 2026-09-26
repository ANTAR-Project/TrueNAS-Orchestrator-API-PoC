package com.tamojit.nasorchestrator.service;

import com.tamojit.nasorchestrator.client.SmbFileClient;
import com.tamojit.nasorchestrator.dto.CreateFolderResponse;
import com.tamojit.nasorchestrator.dto.FileListResponse;
import com.tamojit.nasorchestrator.dto.FileUploadResponse;
import com.tamojit.nasorchestrator.dto.FileUploadResult;
import com.tamojit.nasorchestrator.dto.FolderUploadResponse;
import com.tamojit.nasorchestrator.exception.EncodedFolderAccessException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class SharedWorkspaceService {
    private static final String SHARED_WORKSPACE = "shared";
    private final SmbFileClient smbFileClient;

    public SharedWorkspaceService(SmbFileClient smbFileClient) {
        this.smbFileClient = smbFileClient;
    }

    public FileListResponse listSharedWorkspace(String path) throws IOException {
        assertNotEncodedPath(path);
        String scopedPath = scopeSharedWorkspacePath(path);

        return new FileListResponse(
            scopedPath,
            smbFileClient.list(scopedPath).stream()
                .filter(e -> !e.name().replaceAll("/$", "").equals("encoded"))
                .toList()
        );
    }

    public FileUploadResponse uploadToSharedWorkspace(String path, MultipartFile file) throws IOException {
        assertNotEncodedPath(path);
        String scopedPath = scopeSharedWorkspacePath(path);

        smbFileClient.upload(scopedPath, file);
        String dirPath = scopedPath.endsWith("/") ? scopedPath : scopedPath + "/";
        String storedPath = dirPath + file.getOriginalFilename();

        return new FileUploadResponse(
            storedPath,
            file.getSize(),
            "uploaded"
        );
    }

    public void downloadFromSharedWorkspace(String path, HttpServletResponse response) throws IOException {
        assertNotEncodedPath(path);
        String scopedPath = scopeSharedWorkspacePath(path);
        smbFileClient.download(scopedPath, response);
    }

    public InputStream previewFileInSharedWorkspace(String path) throws IOException {
        assertNotEncodedPath(path);
        String scopedPath = scopeSharedWorkspacePath(path);
        return smbFileClient.preview(scopedPath);
    }

    public void deleteFromSharedWorkspace(String path) throws IOException {
        assertNotEncodedPath(path);
        String scopedPath = scopeSharedWorkspacePath(path);

        if (scopedPath.equals(SHARED_WORKSPACE)) {
            throw new IllegalArgumentException("Cannot delete the shared workspace root");
        }

        smbFileClient.delete(scopedPath);
    }

    public FolderUploadResponse uploadFolderToSharedWorkspace(String path, MultipartFile[] files, String[] relativePaths) {
        assertNotEncodedPath(path);
        if (files.length != relativePaths.length) {
            throw new IllegalArgumentException("files and relativePaths must be the same length");
        }

        String scopedPath = scopeSharedWorkspacePath(path);
        List<FileUploadResult> results = new ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < files.length; i++) {
            String relPath = relativePaths[i];

            try {
                smbFileClient.uploadToRelativePath(scopedPath, relPath, files[i]);
                results.add(new FileUploadResult(relPath, true, "uploaded"));
                successCount++;
            } catch (Exception e) {
                results.add(new FileUploadResult(relPath, false, e.getMessage()));
            }
        }

        return new FolderUploadResponse(
            scopedPath,
            files.length,
            successCount,
            files.length - successCount,
            results
        );
    }

    public CreateFolderResponse createFolderInSharedWorkspace(String path) throws IOException {
        assertNotEncodedPath(path);
        String scopedPath = scopeSharedWorkspacePath(path);

        smbFileClient.createDirectory(scopedPath);
        return new CreateFolderResponse(scopedPath, "created");
    }

    public void clearSharedWorkspace() throws IOException {
        smbFileClient.clearWorkspaceContents(SHARED_WORKSPACE);
    }

    private String scopeSharedWorkspacePath(String path) {
        return path.isBlank() ? SHARED_WORKSPACE : SHARED_WORKSPACE + "/" + path;
    }

    private void assertNotEncodedPath(String path) {
        String normalised = path.replace('\\', '/').replaceAll("^/+", "");
        if (normalised.equals("encoded")
            || normalised.startsWith("encoded/")
            || normalised.contains("/encoded/")
            || normalised.endsWith("/encoded")) {
            throw new EncodedFolderAccessException(
                "Access to the 'encoded' directory is not permitted. " +
                    "It is reserved for internal HLS streaming use.");
        }
    }
}
