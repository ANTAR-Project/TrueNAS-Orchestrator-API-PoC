package com.tamojit.streamingservice.service;

import com.tamojit.streamingservice.dto.PlaylistDto;
import com.tamojit.streamingservice.repository.PlaylistRepository;
import com.tamojit.streamingservice.util.NormalizeWorkspacePath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlaylistService {
    private final PlaylistRepository playlistRepository;

    public List<PlaylistDto> getPlaylistsForUser(String username) {
        return playlistRepository.findByUsername(username).stream()
            .map(p -> NormalizeWorkspacePath.toClientView(
                username,
                p.getRawPath(),
                p.getThumbnailPath()
            ))
            .toList();
    }

    @Transactional
    public void savePlaylist(String username, String rawPath, String playlistPath, String thumbnailPath) {
        playlistRepository.upsertPlaylist(username, rawPath, playlistPath, thumbnailPath);
    }
}
