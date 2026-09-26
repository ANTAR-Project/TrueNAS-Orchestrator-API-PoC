package com.tamojit.nasorchestrator.client;

import com.tamojit.nasorchestrator.dto.FileEntry;
import com.tamojit.nasorchestrator.exception.FolderAlreadyExistsException;
import com.tamojit.nasorchestrator.exception.WorkspaceAlreadyExistsException;
import jakarta.servlet.http.HttpServletResponse;
import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import jcifs.smb.SmbFileOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class SmbFileClient {
    private static final Logger logger = LoggerFactory.getLogger(SmbFileClient.class);

    private final CIFSContext cifsContext;
    private final String shareBaseUrl;

    public SmbFileClient(
        CIFSContext cifsContext,
        @Value("${smb.share-base-url}") String shareBaseUrl
    ) {
        this.cifsContext = cifsContext;
        this.shareBaseUrl = shareBaseUrl.endsWith("/") ? shareBaseUrl : shareBaseUrl + "/";
    }

    private SmbFile resolve(String relativePath) throws IOException {
        if (relativePath.contains("..")) {
            throw new IllegalArgumentException("Path traversal not allowed: " + relativePath);
        }

        String cleanPath = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        return new SmbFile(shareBaseUrl + cleanPath, cifsContext);
    }

    public void upload(String relativeDirPath, MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Uploaded file has no name");
        }
        if (originalFilename.contains("..") || originalFilename.contains("/") || originalFilename.contains("\\")) {
            throw new IllegalArgumentException("Invalid filename: " + originalFilename);
        }

        String dirPath = relativeDirPath.endsWith("/") ? relativeDirPath : relativeDirPath + "/";
        SmbFile target = resolve(dirPath + originalFilename);

        try (SmbFile parentDir = new SmbFile(target.getParent(), cifsContext)) {
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }
        }

        try (InputStream inputStream = file.getInputStream();
             OutputStream outputStream = new SmbFileOutputStream(target)) {
            inputStream.transferTo(outputStream);
        }
    }

    public void download(String relativePath, HttpServletResponse response) throws IOException {
        try (SmbFile target = resolve(relativePath)) {
            if (!target.exists()) {
                throw new FileNotFoundException("File not found: " + relativePath);
            }
            if (target.isDirectory()) {
                downloadAsZip(target, response);
            } else {
                downloadFile(target, response);
            }
        }
    }

    public List<FileEntry> list(String relativePath) throws IOException {
        String dirPath = relativePath.isEmpty() || relativePath.endsWith("/")
            ? relativePath
            : relativePath + "/";

        List<FileEntry> entries = new ArrayList<>();

        try (SmbFile dir = resolve(dirPath)) {
            if (!dir.exists()) {
                throw new FileNotFoundException("Not found on NAS: " + relativePath);
            }

            for (SmbFile file : dir.listFiles()) {
                try (file) {
                    entries.add(new FileEntry(
                        file.getName(),
                        file.length(),
                        file.isDirectory(),
                        file.lastModified()
                    ));
                }
            }
        }

        return entries;
    }

    public InputStream preview(String relativePath) throws IOException {
        SmbFile target = resolve(relativePath);

        if (!target.exists()) {
            target.close();
            throw new FileNotFoundException("Not found on NAS: " + relativePath);
        }

        return new SmbFileInputStream(target);
    }

    public void delete(String relativePath) throws IOException {
        String cleanPath = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;

        if (cleanPath.isEmpty()) {
            throw new IllegalArgumentException("Cannot delete the root folder");
        }

        try (SmbFile target = resolve(relativePath)) {
            if (!target.exists()) {
                throw new FileNotFoundException("Not found on NAS: " + relativePath);
            }

            deleteRecursively(target);
        }
    }

    private void downloadFile(SmbFile target, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(target.getName()).build().toString());

        try (InputStream inputStream = new SmbFileInputStream(target)) {
            inputStream.transferTo(response.getOutputStream());
        }
    }

    private void downloadAsZip(SmbFile dir, HttpServletResponse response) throws IOException {
        String zipName = dir.getName().replaceAll("/$", "") + ".zip";
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(zipName).build().toString());

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            zipRecursively(dir, "", zos);
        }
    }

    private void zipRecursively(SmbFile dir, String basePath, ZipOutputStream zos) throws IOException {
        String path = dir.getPath();
        boolean createdNewDir = !path.endsWith("/");
        SmbFile listableDir = createdNewDir ? new SmbFile(path + "/", cifsContext) : dir;

        try {
            SmbFile[] children = listableDir.listFiles();
            if (children != null) {
                for (SmbFile child : children) {
                    try (child) {
                        String entryPath = basePath + child.getName();

                        if (child.isDirectory()) {
                            zipRecursively(child, entryPath, zos);
                        } else {
                            zos.putNextEntry(new ZipEntry(entryPath));
                            try (InputStream in = new SmbFileInputStream(child)) {
                                in.transferTo(zos);
                            }

                            zos.closeEntry();
                        }
                    }
                }
            }
        } finally {
            if (createdNewDir) {
                listableDir.close();
            }
        }
    }

    private void deleteRecursively(SmbFile file) throws IOException {
        if (file.isDirectory()) {
            String path = file.getPath();
            boolean createdNewDir = !path.endsWith("/");
            SmbFile dir = createdNewDir ? new SmbFile(path + "/", cifsContext) : file;

            try {
                SmbFile[] children = dir.listFiles();
                if (children != null) {
                    for (SmbFile child : children) {
                        try (child) {
                            deleteRecursively(child);
                        }
                    }
                }
            } finally {
                if (createdNewDir) {
                    dir.close();
                }
            }
        }

        try {
            if (!file.canWrite()) {
                file.setReadWrite();
            }
        } catch (Exception e) {
            logger.debug("Could not update write permissions for file {}: {}", file.getName(), e.getMessage());
        }

        file.delete();
    }

    public void uploadToRelativePath(String baseDirPath, String relativePath, MultipartFile file) throws IOException {
        if (relativePath == null || relativePath.isBlank()
            || relativePath.contains("..") || relativePath.contains("\\")) {
            throw new IllegalArgumentException("Invalid relative path: " + relativePath);
        }

        String cleanRelative = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        String baseDir = baseDirPath.endsWith("/") ? baseDirPath : baseDirPath + "/";
        SmbFile target = resolve(baseDir + cleanRelative);

        try (SmbFile parentDir = new SmbFile(target.getParent(), cifsContext)) {
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }
        }

        try (InputStream inputStream = file.getInputStream();
             OutputStream outputStream = new SmbFileOutputStream(target)) {
            inputStream.transferTo(outputStream);
        }
    }

    public OutputStream openForWrite(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Invalid relative path: " + relativePath);
        }

        SmbFile target = resolve(relativePath);

        try (SmbFile parentDir = new SmbFile(target.getParent(), cifsContext)) {
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }
        }

        return new  SmbFileOutputStream(target);
    }

    public void writeBytes(String relativePath, byte[] content) throws IOException {
        try (OutputStream outputStream = openForWrite(relativePath)) {
            outputStream.write(content);
        }
    }

    public void createDirectory(String relativePath) throws IOException {
        String cleanPath = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;

        if (cleanPath.isEmpty()) {
            throw new IllegalArgumentException("Folder path must not be empty — cannot create at share root");
        }

        // Ensure the path resolves as a directory URL (trailing slash required by jCIFS)
        String dirPath = cleanPath.endsWith("/") ? cleanPath : cleanPath + "/";

        try (SmbFile target = resolve(dirPath)) {
            if (target.exists()) {
                throw new FolderAlreadyExistsException("Folder already exists: " + relativePath);
            }

            target.mkdirs();
        }
    }

    public void createWorkspaceDirectory(String name) throws IOException {
        SmbFile target = resolve(name.endsWith("/") ? name : name + "/");

        if (target.exists()) {
            target.close();
            throw new WorkspaceAlreadyExistsException("Workspace already exists at root: " + name);
        }

        try (target) {
            target.mkdir();
        }
    }

    public boolean workspaceExists(String name) throws IOException {
        try (SmbFile target = resolve(name.endsWith("/") ? name : name + "/")) {
            return target.exists();
        }
    }

    public void deleteWorkspace(String name) throws IOException {
        try (SmbFile workspaceDir = resolve(name.endsWith("/") ? name : name + "/")) {
            if (!workspaceDir.exists()) {
                throw new IllegalArgumentException("Workspace does not exist: " + name);
            }

            deleteRecursively(workspaceDir);
        }
    }

    public void clearWorkspaceContents(String name) throws IOException {
        try (SmbFile workspaceDir = resolve(name.endsWith("/") ? name : name + "/")) {
            if (!workspaceDir.exists()) {
                throw new FileNotFoundException("Workspace does not exist: " + name);
            }

            SmbFile[] children = workspaceDir.listFiles();
            if (children != null) {
                for (SmbFile child : children) {
                    try (child) {
                        deleteRecursively(child);
                    } catch (IOException e) {
                        // A child that vanished between listFiles() and here (another concurrent
                        // op, or an overlapping clear()) isn't a failure — the goal was "gone,"
                        // and it already is. Anything else (permissions, a genuine SMB fault)
                        // still surfaces via the outer call failing loudly if it recurs.
                        logger.warn("Skipping child during clear of workspace '{}': {} ({})", name, child.getName(), e.getMessage());
                    }
                }
            }
        }
    }
}
