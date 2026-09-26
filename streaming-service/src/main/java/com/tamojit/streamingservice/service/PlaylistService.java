package com.tamojit.streamingservice.service;

import com.tamojit.streamingservice.dto.PlaylistDto;
import com.tamojit.streamingservice.repository.PlaylistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlaylistService {
    private final PlaylistRepository playlistRepository;

    public List<PlaylistDto> getPlaylistsForUser(String username) {
        String prefix = username + "/";
        return playlistRepository.findByUsername(username).stream()
            .map(p -> {
                String path = p.getRawPath();
                if (path.startsWith(prefix)) {
                    path = path.substring(prefix.length());
                }

                String thumbnail = p.getThumbnailPath();
                if (thumbnail != null && thumbnail.startsWith(prefix)) {
                    thumbnail = thumbnail.substring(prefix.length());
                }

                return new PlaylistDto(path, thumbnail);
            })
            .toList();
    }

    @Transactional
    public void savePlaylist(String username, String rawPath, String playlistPath, String thumbnailPath) {
        playlistRepository.upsertPlaylist(username, rawPath, playlistPath, thumbnailPath);
    }
}
