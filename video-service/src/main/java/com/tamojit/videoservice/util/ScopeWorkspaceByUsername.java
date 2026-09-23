package com.tamojit.videoservice.util;

import com.tamojit.videoservice.security.TokenValidationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ScopeWorkspaceByUsername {

    // Workspace scoping from token attribute (username)
    public String scopedPath(HttpServletRequest request, String rawPath) {
        String username = extractUsername(request);

        String cleanRaw = rawPath == null ? "" : rawPath;
        if (cleanRaw.isEmpty()) {
            return username;
        }

        return username + "/" + cleanRaw;
    }

    // Returns exactly the caller's workspace root — nothing else. No path argument exists on this method at all, by design
    public String workspaceRoot(HttpServletRequest request) {
        return extractUsername(request);
    }

    private String extractUsername(HttpServletRequest request) {
        String username = (String) request.getAttribute(TokenValidationFilter.USERNAME_ATTRIBUTE);

        if (username == null || username.isBlank()) {
            throw new IllegalStateException("No authenticated username on request");
        }

        return username;
    }
}
