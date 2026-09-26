package com.tamojit.streamingservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Consumed from Kafka topic: video.encoded
 * Published by encoding-service after ffmpeg encoding.
 *
 * Field names MUST match encoding-service's VideoEncodedEvent exactly —
 * Jackson deserializes by name across the Kafka wire.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoEncodedEvent {
    private String workspaceRoot;
    private String nasPath;
    private String masterPlaylistPath;
    private String thumbnailPath;
    private boolean success;
    private String errorMessage;
}
