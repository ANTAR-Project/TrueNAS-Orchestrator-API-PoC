package com.tamojit.notificationservice.service;

import com.tamojit.notificationservice.dto.NotificationPayload;
import com.tamojit.notificationservice.event.PlaylistReadyEvent;
import com.tamojit.notificationservice.websocket.NotificationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class PlaylistReadyConsumer {
    private final NotificationRegistry registry;

    @KafkaListener(topics = "playlist.ready", groupId = "notification-service-group")
    public void onPlaylistReady(PlaylistReadyEvent event) {
        log.info("Consumed playlist.ready for user: {} path: {}", event.getUsername(), event.getRawPath());

        registry.sendTo(event.getUsername(), new NotificationPayload(
            "PLAYLIST_READY",
            event.getRawPath(),
            event.getPlaylistPath(),
            event.getThumbnailPath()
        ));
    }
}
