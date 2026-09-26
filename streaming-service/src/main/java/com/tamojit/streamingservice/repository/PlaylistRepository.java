package com.tamojit.streamingservice.repository;

import com.tamojit.streamingservice.model.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PlaylistRepository extends JpaRepository<Playlist, Long> {
    List<Playlist> findByUsername(String username);

    // Atomic upsert (single-statement, DB-enforced) — not check-then-save.
    // Kafka's at-least-once delivery can redeliver this event on rebalance/retry;
    // a check-then-insert here would race between two overlapping deliveries
    // and violate the raw_path UNIQUE constraint. ON CONFLICT closes that window.
    @Modifying
    @Query(value = """
            INSERT INTO playlists (username, raw_path, playlist_path, thumbnail_path, created_at, updated_at)
            VALUES (:username, :rawPath, :playlistPath, :thumbnailPath, now(), now())
            ON CONFLICT (raw_path)
            DO UPDATE SET playlist_path = EXCLUDED.playlist_path, thumbnail_path = EXCLUDED.thumbnail_path, updated_at = now()
        """, nativeQuery = true)
    void upsertPlaylist(
        @Param("username") String username,
        @Param("rawPath") String rawPath,
        @Param("playlistPath") String playlistPath,
        @Param("thumbnailPath") String thumbnailPath
    );
}
