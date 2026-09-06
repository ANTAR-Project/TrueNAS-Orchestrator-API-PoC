package com.tamojit.authservice.service;

import com.tamojit.authservice.exception.InvalidTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String TOKEN_KEY_PREFIX = "auth:token:";
    private static final String USER_KEY_PREFIX = "auth:user:";

    private final RedisTemplate<String, String> redisTemplate;
    private final long ttlHours;

    public AuthService(
        RedisTemplate<String, String> redisTemplate,
        @Value("${auth.token.ttl-hours:0}") long ttlHours
    ) {
        this.redisTemplate = redisTemplate;
        this.ttlHours = ttlHours;
    }

    public String issueToken(String username) {
        String previousToken = redisTemplate.opsForValue().get(USER_KEY_PREFIX + username);
        if (previousToken != null) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + previousToken);
            log.info("Reissuing token for username: {} (previous token invalidated)", username);
        }

        String token = UUID.randomUUID().toString().replace("-", "");

        if (ttlHours > 0) {
            Duration ttl = Duration.ofHours(ttlHours);
            redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token, username, ttl);
            redisTemplate.opsForValue().set(USER_KEY_PREFIX + username, token, ttl);
        } else {
            redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token, username);
            redisTemplate.opsForValue().set(USER_KEY_PREFIX + username, token);
        }

        log.info("Issued token for username: {}", username);
        return token;
    }

    public String validate(String token) {
        String username = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);
        if (username == null) {
            throw new InvalidTokenException("Token is invalid, expired, or was revoked");
        }

        return username;
    }
}
