package com.tamojit.streamingservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    @Value("${nas.orchestrator.base-url}")
    private String nasOrchestratorBaseUrl;

    @Value("${auth.service.base-url}")
    private String authServiceBaseUrl;

    /**
     * RestClient wired to nas-orchestrator — used by StreamingService to proxy
     * playlist requests.
     */
    @Bean
    public RestClient nasOrchestratorRestClient() {
        return RestClient.builder()
            .baseUrl(nasOrchestratorBaseUrl)
            .build();
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();

        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());

        return redisTemplate;
    }

    @Bean
    public RestClient authServiceRestClient() {
        return RestClient.builder()
            .baseUrl(authServiceBaseUrl)
            .build();
    }
}
