package com.tamojit.nasorchestrator.dto;

import java.util.List;

public record FolderUploadResponse(
    String basePath,
    int totalFiles,
    int successCount,
    int failureCount,
    List<FileUploadResult> results
) {
}
