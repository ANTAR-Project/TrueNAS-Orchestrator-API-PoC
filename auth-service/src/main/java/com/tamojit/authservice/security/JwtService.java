package com.tamojit.authservice.security;

import com.tamojit.authservice.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtService {
    private final SecretKey signingKey;
    private final long expirationMinutes;

    public JwtService(
        @Value("${auth.jwt.secret}") String base64Secret,
        @Value("${auth.jwt.expiration-minutes}") long expirationMinutes
    ) {
        this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
        this.expirationMinutes = expirationMinutes;
    }

    public String issueToken(String username, String accountType) {
        Instant now = Instant.now();

        return Jwts.builder()
            .subject(username)
            .claim("accountType", accountType)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(
                Duration.ofMinutes(expirationMinutes)
            )))
            .signWith(signingKey)
            .compact();
    }

    public String verifyAndGetUsername(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Token is invalid, expired, or malformed");
        }
    }
}
