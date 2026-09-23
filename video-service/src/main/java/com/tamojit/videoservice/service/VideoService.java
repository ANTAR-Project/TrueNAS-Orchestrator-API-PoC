package com.tamojit.videoservice.service;

import com.tamojit.videoservice.client.NasOrchestratorClient;
import com.tamojit.videoservice.event.VideoUploadedEvent;
import com.tamojit.videoservice.util.ScopeWorkspaceByUsername;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@Slf4j
@RequiredArgsConstructor
public class VideoService {
    private final KafkaTemplate<String, VideoUploadedEvent> kafkaTemplate;
    private final NasOrchestratorClient nasOrchestratorClient;
    private final ScopeWorkspaceByUsername scopeWorkspaceByUsername;

    private static final String VIDEO_UPLOADED_TOPIC = "video.uploaded"; // kafka topic

    public String uploadVideo(String path, MultipartFile file, HttpServletRequest request) throws IOException {
        String scopedPath = scopeWorkspaceByUsername.scopedPath(request, path);
        String workspaceRoot = scopeWorkspaceByUsername.workspaceRoot(request);

        log.info("Uploading video {} to NAS : file - {}", scopedPath, file.getOriginalFilename());

        String nasPath = nasOrchestratorClient.uploadFile(scopedPath, file);
        log.info("Video uploaded to NAS successfully: path = {}", nasPath);

        // Publishing upload event to kafka - for encoding-service to start ffmpeg encoding
        VideoUploadedEvent videoUploadedEvent = new VideoUploadedEvent(
            workspaceRoot,
            path,
            nasPath,
            file.getOriginalFilename(),
            file.getSize()
        );

        kafkaTemplate.send(VIDEO_UPLOADED_TOPIC, nasPath, videoUploadedEvent);
        log.info("Video uploaded event published to Kafka: key = {}", nasPath);

        return nasPath;
    }
}
