package com.tamojit.nasorchestrator.dto;

public record FileUploadResult(
    String relativePath,
    boolean success,
    String message
) {
}
