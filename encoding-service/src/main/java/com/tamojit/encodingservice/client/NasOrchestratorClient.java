package com.tamojit.encodingservice.client;

import com.tamojit.encodingservice.security.ServiceAuthTokenProvider;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class NasOrchestratorClient {
    private final RestClient restClient;
    private final ServiceAuthTokenProvider tokenProvider;

    public NasOrchestratorClient(
        @Value("${nas.orchestrator.base-url}") String baseUrl,
        ServiceAuthTokenProvider tokenProvider
    ) {
        // Apache HC5: FileSystemResource exposes contentLength() so HC5 sends a
        // proper Content-Length header per file — no heap buffering, no chunked framing.
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()))
            .build();
        this.tokenProvider = tokenProvider;
    }

    public void downloadToFile(String relativePath, Path destination) throws IOException {
        byte[] bytes = restClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/v1/nas-orchestrator/files/download")
                .queryParam("path", relativePath)
                .build())
            .header("X-Auth-Token", tokenProvider.getToken())
            .retrieve()
            .body(byte[].class);

        if (bytes == null) {
            throw new IOException("Failed to download file: Received empty/null body from server for path " + relativePath);
        }

        Files.write(destination, bytes);
    }

    // One POST per file — mirrors how the S3 SDK worked (one PUT per object).
    // Avoids bundling all HLS segments into a single multipart body that Tomcat
    // would have to buffer entirely in heap before any upload could begin.
    public void uploadFolder(String nasBasePath, File localDir) throws IOException {
        uploadRecursively(nasBasePath, localDir, localDir);
    }

    private void uploadRecursively(String nasBasePath, File root, File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                uploadRecursively(nasBasePath, root, file);
                continue;
            }

            // e.g. "1080p/segment_000.ts" or "master.m3u8"
            String destDir = getString(nasBasePath, root, file);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("path", destDir);
            body.add("file", new FileSystemResource(file)); // filename taken from File.getName()

            restClient.post()
                .uri("/api/v1/nas-orchestrator/files/upload/file")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("X-Auth-Token", tokenProvider.getToken())
                .body(body)
                .retrieve()
                .toBodilessEntity();
        }
    }

    private String getString(String nasBasePath, File root, File file) {
        String relativePath = root.toPath()
            .relativize(file.toPath())
            .toString()
            .replace("\\", "/");

        // Resolve destination directory on the NAS:
        //   "1080p/segment_000.ts" → destDir = nasBasePath + "/1080p"
        //   "master.m3u8"          → destDir = nasBasePath
        int lastSlash = relativePath.lastIndexOf('/');
        String destDir = lastSlash >= 0
            ? nasBasePath + "/" + relativePath.substring(0, lastSlash)
            : nasBasePath;
        return destDir;
    }
}
