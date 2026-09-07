package com.tamojit.videoservice.client;

import com.tamojit.videoservice.security.ServiceAuthTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Component
public class NasOrchestratorClient {
    private final RestClient restClient;
    private final ServiceAuthTokenProvider tokenProvider;

    public NasOrchestratorClient(
        @Value("${nas.orchestrator.base-url}") String baseUrl,
        ServiceAuthTokenProvider tokenProvider
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
    }

    // returns the relative NAS path the file was stored under: {dirPath}/{originalFilename}
    public String uploadFile(String dirPath, MultipartFile file) throws IOException {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("path", dirPath);
        body.add("file", new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        });

        restClient.post()
            .uri("/api/v1/nas-orchestrator/files/upload/file")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .header("X-Auth-Token", tokenProvider.getToken())
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .retrieve()
            .toBodilessEntity();

        return dirPath + "/" + file.getOriginalFilename();
    }
}
