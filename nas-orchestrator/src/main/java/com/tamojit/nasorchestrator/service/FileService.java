package com.tamojit.nasorchestrator.service;

import com.tamojit.nasorchestrator.client.SmbFileClient;
import com.tamojit.nasorchestrator.dto.FileListResponse;
import com.tamojit.nasorchestrator.dto.FileUploadResponse;
import com.tamojit.nasorchestrator.dto.FileUploadResult;
import com.tamojit.nasorchestrator.dto.FolderUploadResponse;
import com.tamojit.nasorchestrator.exception.WorkspaceNotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
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
        smbFileClient.download(path, response);
    }

    public InputStream preview(String path) throws IOException {
        return smbFileClient.preview(path);
    }

    public FileListResponse list(String path, String username) throws IOException {
        if (path.equals(username) && !smbFileClient.workspaceExists(username)) {
            throw new WorkspaceNotFoundException("Workspace does not exist for user: " + username);
        }

        return new FileListResponse(
            path,
            smbFileClient.list(path)
        );
    }

    public void delete(String path, String username) throws IOException {
        if (path.equals(username)) {
            throw new IllegalArgumentException("Cannot delete the workspace root by this function");
        }

        smbFileClient.delete(path);
    }

    public FolderUploadResponse uploadFolder(String path, MultipartFile[] files, String[] relativePaths) {
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
}
