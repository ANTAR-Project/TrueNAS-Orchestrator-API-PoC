package com.tamojit.videoservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka Topic: video.uploaded
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoUploadedEvent {
    private String workspaceRoot;
    private String path;
    private String nasPath;
    private String originalFileName;
    private long fileSizeBytes;
}
