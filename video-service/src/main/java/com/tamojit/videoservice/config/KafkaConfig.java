package com.tamojit.videoservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
    // published when video is uploaded to S3
    // encoding-service consumes this topic
    @Bean
    public NewTopic videoUploadedTopic() {
        return TopicBuilder.name("video.uploaded")
            .partitions(3)
            .replicas(1)
            .build();
    }

    // published when video encoding is complete
    @Bean
    public NewTopic videoEncodedTopic() {
        return TopicBuilder.name("video.encoded")
            .partitions(3)
            .replicas(1)
            .build();
    }

    // published by streaming-service after the playlist row is durable committed to Postgres
    // notification-service consumes this topic
    @Bean
    public NewTopic playlistReadyTopic() {
        return TopicBuilder.name("playlist.ready")
            .partitions(3)
            .replicas(1)
            .build();
    }
}
