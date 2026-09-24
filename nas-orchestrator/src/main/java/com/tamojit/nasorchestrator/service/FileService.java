package com.tamojit.nasorchestrator.service;

import com.tamojit.nasorchestrator.client.SmbFileClient;
import com.tamojit.nasorchestrator.dto.CreateFolderResponse;
import com.tamojit.nasorchestrator.dto.FileListResponse;
import com.tamojit.nasorchestrator.dto.FileUploadResponse;
import com.tamojit.nasorchestrator.dto.FileUploadResult;
import com.tamojit.nasorchestrator.dto.FolderUploadResponse;
import com.tamojit.nasorchestrator.exception.EncodedFolderAccessException;
import com.tamojit.nasorchestrator.exception.WorkspaceNotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class FileService {
    private final SmbFileClient smbFileClient;

    public FileService(SmbFileClient smbFileClient) {
        this.smbFileClient = smbFileClient;
    }

    public FileUploadResponse upload(String path, MultipartFile file) throws IOException {
        assertNotEncodedPath(path);
        smbFileClient.upload(path, file);
        String dirPath = path.endsWith("/") ? path : path + "/";
        String storedPath = dirPath + file.getOriginalFilename();

        return new FileUploadResponse(
            storedPath,
            file.getSize(),
            "uploaded"
        );
    }

    public void download(String path, HttpServletResponse response) throws IOException {
        assertNotEncodedPath(path);
        smbFileClient.download(path, response);
    }

    public InputStream preview(String path) throws IOException {
        assertNotEncodedPath(path);
        return smbFileClient.preview(path);
    }

    public FileListResponse list(String path, String username) throws IOException {
        assertNotEncodedPath(path);
        if (path.equals(username) && !smbFileClient.workspaceExists(username)) {
            throw new WorkspaceNotFoundException("Workspace does not exist for user: " + username);
        }

        return new FileListResponse(
            path,
            smbFileClient.list(path).stream()
                .filter(e -> !e.name().replaceAll("/$", "").equals("encoded"))
                .toList()
        );
    }

    public void delete(String path, String username) throws IOException {
        assertNotEncodedPath(path);
        if (path.equals(username)) {
            throw new IllegalArgumentException("Cannot delete the workspace root by this function");
        }

        smbFileClient.delete(path);
    }

    public CreateFolderResponse createFolder(String path, String username) throws IOException {
        assertNotEncodedPath(path);
        if (path.equals(username)) {
            throw new IllegalArgumentException("Cannot create a new root — provide a non-empty folder name inside your workspace");
        }

        smbFileClient.createDirectory(path);
        // HTML-encoding the echoed path to prevent reflected-XSS (tainted user input
        // must not be reflected verbatim in the response body).
        return new CreateFolderResponse(HtmlUtils.htmlEscape(path), "created");
    }

    public FolderUploadResponse uploadFolder(String path, MultipartFile[] files, String[] relativePaths) {
        assertNotEncodedPath(path);
        if (files.length != relativePaths.length) {
            throw new IllegalArgumentException("files and relativePaths must be the same length");
        }

        List<FileUploadResult> results = new ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < files.length; i++) {
            String relPath = relativePaths[i];

            try {
                smbFileClient.uploadToRelativePath(path, relPath, files[i]);
                results.add(new FileUploadResult(relPath, true, "uploaded"));
                successCount++;
            } catch (Exception e) {
                results.add(new FileUploadResult(relPath, false, e.getMessage()));
            }
        }

        return new FolderUploadResponse(
            path,
            files.length,
            successCount,
            files.length - successCount,
            results
        );
    }

    private void assertNotEncodedPath(String path) {
        // Normalizing to forward-slashes and strip a leading slash if present.
        String normalised = path.replace('\\', '/').replaceAll("^/+", "");
        // Matching the segment name exactly so that e.g. "encoded-extra/" is NOT blocked.
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
