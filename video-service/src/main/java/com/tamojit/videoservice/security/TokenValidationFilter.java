package com.tamojit.videoservice.security;

import com.tamojit.videoservice.dto.ValidationOutcome;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class TokenValidationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TokenValidationFilter.class);
    public static final String USERNAME_ATTRIBUTE = "authUsername";
    public static final String TOKEN_ATTRIBUTE = "authToken";
    private static final String USERNAME_MDC_KEY = "username";

    private final AuthClient authClient;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.endsWith("/health") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token == null || token.isBlank()) {
            writeError(response, request, HttpServletResponse.SC_UNAUTHORIZED, "Missing token (X-Auth-Token header or ?token= query param)");
            return;
        }

        ValidationOutcome outcome = authClient.validate(token);

        if (!outcome.reachable()) {
            writeError(response, request, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "auth-service is unreachable");
            return;
        }

        if (!outcome.valid()) {
            writeError(response, request, HttpServletResponse.SC_UNAUTHORIZED, "Token is invalid, expired, or was revoked");
            return;
        }

        request.setAttribute(USERNAME_ATTRIBUTE, outcome.username());
        request.setAttribute(TOKEN_ATTRIBUTE, token); // needed so the controller can forward it
        MDC.put(USERNAME_MDC_KEY, outcome.username());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(USERNAME_MDC_KEY);
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("X-Auth-Token");
        if (header != null && !header.isBlank()) {
            return header;
        }
        return request.getParameter("token");
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request, int status, String message) throws IOException {
        log.warn("Rejecting request to {}: {}", request.getRequestURI(), message);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
        // Flush immediately — without this, Tomcat buffers the response and waits for the full
        // multipart request body to be consumed before sending it, causing a silent hang on large uploads
        response.flushBuffer();
    }
}
