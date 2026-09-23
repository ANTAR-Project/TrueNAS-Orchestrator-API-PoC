package com.tamojit.encodingservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoEncodedEvent {
    private String nasPath;
    private String masterPlaylistPath;
    private boolean success;
    private String errorMessage;
}
