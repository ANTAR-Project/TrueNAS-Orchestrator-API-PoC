package com.tamojit.encodingservice.security;

import com.tamojit.encodingservice.dto.TokenRequest;
import com.tamojit.encodingservice.dto.TokenResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ServiceAuthTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(ServiceAuthTokenProvider.class);

    private final RestClient authServiceRestClient;
    private final String serviceUsername;
    private volatile String token;

    public ServiceAuthTokenProvider(
        RestClient authServiceRestClient,
        @Value("${service.auth.username}") String serviceUsername
    ) {
        this.authServiceRestClient = authServiceRestClient;
        this.serviceUsername = serviceUsername;
    }

    @PostConstruct
    public void registerAtStartup() {
        try {
            fetchAndCacheToken();
        } catch (Exception e) {
            log.warn("Could not obtain auth token at startup for '{}' — will retry lazily on first NAS call: {}", serviceUsername, e.getMessage());
        }
    }

    public String getToken() {
        if (token == null) {
            fetchAndCacheToken();
        }
        return token;
    }

    private synchronized void fetchAndCacheToken() {
        if (token != null) {
            return;
        }

        TokenResponse response = authServiceRestClient.post()
            .uri("/api/v1/auth-service/tokens")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new TokenRequest(serviceUsername))
            .retrieve()
            .body(TokenResponse.class);

        this.token = response != null ? response.token() : null;
        log.info("Registered service identity '{}' with auth-service", serviceUsername);
    }
}

