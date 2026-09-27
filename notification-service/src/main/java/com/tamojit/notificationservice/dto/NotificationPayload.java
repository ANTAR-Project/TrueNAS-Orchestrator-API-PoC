package com.tamojit.notificationservice.dto;

/**
 * The client-facing WebSocket message shape — deliberately a separate type
 * from PlaylistReadyEvent, so the internal Kafka event contract can evolve
 * independently of what's actually sent over the wire to the client.
 */
public record NotificationPayload(
    String type,
    String rawPath,
    String playlistPath,
    String thumbnailPath
) {
}
