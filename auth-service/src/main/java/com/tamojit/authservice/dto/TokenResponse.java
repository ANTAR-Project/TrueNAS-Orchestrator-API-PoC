package com.tamojit.authservice.dto;

public record TokenResponse(
    String username,
    String token
) {
}
