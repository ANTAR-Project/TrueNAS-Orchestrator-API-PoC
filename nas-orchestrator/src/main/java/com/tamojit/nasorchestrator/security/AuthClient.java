package com.tamojit.nasorchestrator.security;

import com.tamojit.nasorchestrator.dto.ValidateResponse;
import com.tamojit.nasorchestrator.dto.ValidationOutcome;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class AuthClient {
    private final RestClient authServiceRestClient;

    public AuthClient(RestClient authServiceRestClient) {
        this.authServiceRestClient = authServiceRestClient;
    }

    public ValidationOutcome validate(String token) {
        try {
            ValidateResponse response = authServiceRestClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/v1/auth-service/tokens/validate")
                    .queryParam("token", token)
                    .build())
                .retrieve()
                .body(ValidateResponse.class);

            return new ValidationOutcome(
                true,
                true,
                response != null ? response.username() : null
            );
        } catch (RestClientResponseException e) {
            // auth-service reachable, token itself rejected (401/400)
            return new ValidationOutcome(false, true, null);
        } catch (Exception e) {
            // auth-service unreachable
            return new ValidationOutcome(false, false, null);
        }
    }
}
