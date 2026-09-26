package com.tamojit.nasorchestrator.dto;

public record FileUploadResponse(
    String path,
    long size,
    String message
) {
}
