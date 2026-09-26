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
}
