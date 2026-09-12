package com.tamojit.nasorchestrator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateWorkspaceRequest(
    @NotBlank(message = "name is required")
    @Pattern(regexp = "^[^/\\\\]+$", message = "name must be a single directory name — no '/' or '\\'")
    @Pattern(regexp = "^(?!\\.\\.$).*$", message = "name cannot be '..'")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "name may only contain letters, digits, '.', '_', '-'")
    String name
) {
}
