package com.tamojit.encodingservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Consumed from Kafka Topic: video.uploaded
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
