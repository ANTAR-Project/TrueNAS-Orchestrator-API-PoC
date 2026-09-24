package com.tamojit.encodingservice.service;

import com.tamojit.encodingservice.event.VideoUploadedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class VideoEventConsumer {
    private final EncodingService encodingService;
    private final KafkaListenerEndpointRegistry registry;

    // Single-thread executor: ensures only one encoding job runs at a time per consumer instance.
    // The container is paused while the job runs, so Kafka never delivers a second message
    // until this executor is free and the container is resumed.
    private final ExecutorService encodingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "encoding-worker");
        t.setDaemon(true);
        return t;
    });

    // Container ID matches the @KafkaListener id below — used to pause/resume the container
    // from the encoding-worker thread via the registry (thread-safe by design in Spring Kafka).
    private static final String CONTAINER_ID = "videoUploadedListener";

    public VideoEventConsumer(EncodingService encodingService, KafkaListenerEndpointRegistry registry) {
        this.encodingService = encodingService;
        this.registry = registry;
    }

    /*
     * Listens to 'video.uploaded' Kafka topic.
     *
     * Uses pause/resume + manual ack to decouple the Kafka listener thread from
     * the (potentially hours-long) FFmpeg encoding job:
     *
     *  1. Pause the container  → no new messages delivered while encoding is running.
     *  2. Return immediately   → listener thread keeps calling poll(); Kafka never
     *                            evicts the consumer regardless of encoding duration.
     *  3. Encode on async thread.
     *  4. Commit the offset    → acked AFTER the result event is published, so a JVM
     *                            crash or OOM causes redelivery rather than silent loss.
     *                            Spring Kafka MANUAL mode allows acknowledge() from any
     *                            thread while the container is paused.
     *  5. Resume the container → ready for next message.
     *
     * Delivery guarantee: at-least-once. encodeVideo() must therefore be idempotent
     * FLOW:
     * video-service → NAS upload → Kafka (video.uploaded) → encoding-service
     *   → ffmpeg HLS encoding → NAS encoded upload → Kafka (video.encoded)
     */
    @KafkaListener(
        id = CONTAINER_ID,
        topics = "video.uploaded",
        groupId = "encoding-service-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeVideoUploadedEvent(
        VideoUploadedEvent event,
        Acknowledgment acknowledgment
    ) {
        log.info("Consumed VideoUploadedEvent for movie: {}, file: {}", event.getNasPath(), event.getOriginalFileName());

        // Step 1: Pausing the container — thread-safe via Spring Kafka's registry.
        // Applied on the next poll() cycle by the listener thread itself.
        MessageListenerContainer container = registry.getListenerContainer(CONTAINER_ID);
        assert container != null;
        container.pause();
        log.info("Paused container '{}' for movie: {}", CONTAINER_ID, event.getNasPath());

        // Step 2: Offloading encoding to the dedicated worker thread and return immediately.
        // The listener thread is now free to poll() Kafka on schedule.
        encodingExecutor.submit(() -> {
            try {
                encodingService.encodeVideo(event);
            } catch (Exception e) {
                log.error("Encoding executor failed for movie: {} - {}", event.getNasPath(), e.getMessage());
            } finally {
                // Step 3: Commiting the offset AFTER encoding (success or failure).
                // A JVM kill before this point causes redelivery — intentional at-least-once semantics.
                // Spring Kafka allows acknowledge() from a non-listener thread in MANUAL mode.
                acknowledgment.acknowledge();
                log.info("Kafka offset committed for movie: {}", event.getNasPath());

                // Step 4: Resume the container — thread-safe via Spring Kafka's registry.
                container.resume();
                log.info("Resumed container '{}' after encoding movie: {}", CONTAINER_ID, event.getNasPath());
            }
        });
    }
}
