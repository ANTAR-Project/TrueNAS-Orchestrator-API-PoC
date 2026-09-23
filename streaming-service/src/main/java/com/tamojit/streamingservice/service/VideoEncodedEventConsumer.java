package com.tamojit.streamingservice.service;

import com.tamojit.streamingservice.event.VideoEncodedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class VideoEncodedEventConsumer {
    private final RedisTemplate<String, String> redisTemplate;
    private static final String MASTER_PLAYLIST_KEY_PREFIX = "streaming:playlist:";

    /*
     * Listens on video.encoded topic in Kafka
     * Stores master playlist key in Redis when encoding is complete
     * This allows the streaming-service for faster access of playlist key
     */
    @KafkaListener(
        topics = "video.encoded",
        groupId = "streaming-service-group"
    )
    public void consumeVideoEncodedEvent(VideoEncodedEvent event) {
        log.info("Consumed video.encoded event for movie: {} - success: {}", event.getNasPath(), event.isSuccess());

        if (event.isSuccess()) {
            // storing master playlist in redis
            String cacheKey = MASTER_PLAYLIST_KEY_PREFIX + event.getNasPath();
            redisTemplate.opsForValue().set(cacheKey, event.getMasterPlaylistPath());
            log.info("Successfully cached playlist path for movie: {} → {}", event.getNasPath(), event.getMasterPlaylistPath());
        } else {
            log.error("Encoding failed for movie: {} - {}", event.getNasPath(), event.getErrorMessage());
        }
    }
}
