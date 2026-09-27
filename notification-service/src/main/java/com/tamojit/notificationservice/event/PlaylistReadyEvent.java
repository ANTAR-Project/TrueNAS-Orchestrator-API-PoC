package com.tamojit.notificationservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PlaylistReadyEvent {
    private String username;
    private String rawPath;
    private String playlistPath;
    private String thumbnailPath;
}
