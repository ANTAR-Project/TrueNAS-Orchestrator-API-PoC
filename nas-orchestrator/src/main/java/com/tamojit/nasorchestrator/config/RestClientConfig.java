package com.tamojit.nasorchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {
    @Bean
    public RestClient trueNasRestClient(
        @Value("${truenas.base-url}") String baseUrl,
        @Value("${truenas.api-key}") String apiKey
    ) {
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }

    @Bean
    public RestClient authServiceRestClient(
        @Value("${auth.service.base-url}") String baseUrl
    ) {
        return RestClient.builder()
            .baseUrl(baseUrl)
            .build();
    }
}
