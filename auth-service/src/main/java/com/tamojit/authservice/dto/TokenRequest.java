package com.tamojit.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TokenRequest(
    @NotBlank(message = "username is required")
    @Pattern(regexp = "^[a-zA-Z0-9._-]{1,64}$", message = "username may only contain letters, digits, '.', '_', '-'")
    String username
) {
}
