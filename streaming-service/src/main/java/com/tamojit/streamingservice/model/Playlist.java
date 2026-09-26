package com.tamojit.streamingservice.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "playlists")
@Getter
@Setter
@NoArgsConstructor
public class Playlist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "raw_path", nullable = false, unique = true, length = 255)
    private String rawPath;

    @Column(name = "playlist_path", nullable = false, unique = true, length = 255)
    private String playlistPath;

    @Column(name = "thumbnail_path", length = 255)
    private String thumbnailPath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Playlist(String username, String rawPath, String playlistPath, String thumbnailPath) {
        this.username = username;
        this.rawPath = rawPath;
        this.playlistPath = playlistPath;
        this.thumbnailPath = thumbnailPath;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
