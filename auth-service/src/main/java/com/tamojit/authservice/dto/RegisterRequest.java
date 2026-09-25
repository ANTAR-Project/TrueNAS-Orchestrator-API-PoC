package com.tamojit.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "username is required")
    @Pattern(regexp = "^[a-zA-Z0-9._-]{1,64}$", message = "username may only contain letters, digits, '.', '_', '-'")
    String username,

    @NotBlank(message = "password is required")
    @Size(min = 12, message = "password must be at least 12 characters")
    String password,

    @Pattern(regexp = "^(USER|SERVICE)$", message = "accountType must be USER or SERVICE")
    String accountType
) {
    public RegisterRequest {
        if (accountType == null || accountType.isBlank()) accountType = "USER";
    }
}
