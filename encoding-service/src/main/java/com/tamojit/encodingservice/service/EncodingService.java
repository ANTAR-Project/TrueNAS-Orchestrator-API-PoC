package com.tamojit.encodingservice.service;

import com.tamojit.encodingservice.client.NasOrchestratorClient;
import com.tamojit.encodingservice.event.VideoEncodedEvent;
import com.tamojit.encodingservice.event.VideoUploadedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class EncodingService {
    private final KafkaTemplate<String, VideoEncodedEvent> kafkaTemplate;
    private final NasOrchestratorClient nasOrchestratorClient;

    @Value("${ffmpeg.path}")
    private String ffmpegPath;

    @Value("${encoding.base-path}")
    private String basePath;

    private static final String VIDEO_ENCODED_TOPIC = "video.encoded";

    // Video qualities to encode
    // format: resolution, bitrate, height
    private static final List<int[]> VIDEO_QUALITIES = Arrays.asList(
        new int[]{1920, 5000, 1080}, // 1920px resolution, 1080p, 5000 kbps bitrate
        new int[]{1280, 2800, 720}, // 720p
        new int[]{854, 1200, 480}, // 480p
        new int[]{640, 800, 360} // 360p
    );

    public void encodeVideo(VideoUploadedEvent event) {
        log.info("Video encoded event received: {}", event.getNasPath());

        // creating unique path for temp download of video
        String jobPath = basePath + "/" + event.getOriginalFileName();

        try {
            // creating temp directories
            Files.createDirectories(Paths.get(jobPath));
            Files.createDirectories(Paths.get(jobPath + "/encoded"));

            // S1: downloading raw file from NAS
            String localVideoPath = jobPath + "/raw_video.mp4";
            nasOrchestratorClient.downloadToFile(event.getNasPath(), Path.of(localVideoPath));
            log.info("Raw Video downloaded to: {}", localVideoPath);

            // S2, S3: Encoding to multiple qualities & generating HLS playlist
            for (int[] qualities : VIDEO_QUALITIES) {
                int width = qualities[0];
                int bitrate = qualities[1];
                int height = qualities[2];

                String qualityDir = jobPath + "/encoded/" + height + "p";
                Files.createDirectories(Paths.get(qualityDir));

                encodeToHls(localVideoPath, qualityDir, width, height, bitrate);
                log.info("Encoded {}p successfully", height);
            }

            // S4: generating master playlist
            String localMasterPlaylistPath = jobPath + "/encoded/master.m3u8";
            generateMasterPlaylist(localMasterPlaylistPath);
            log.info("Master playlist generated successfully");

            // S5: uploading all encoded files to NAS in one folder upload
            String encodedBasePath = buildEncodedBasePath(event);
            nasOrchestratorClient.uploadFolder(encodedBasePath, new File(jobPath + "/encoded"));
            log.info("All encoded files uploaded to NAS successfully");

            // S6: publishing video.encoded event
            String masterPlaylistPath = encodedBasePath + "/master.m3u8";

            VideoEncodedEvent encodedEvent = new VideoEncodedEvent(
                event.getNasPath(),
                masterPlaylistPath,
                true,
                null
            );

            kafkaTemplate.send(VIDEO_ENCODED_TOPIC, event.getNasPath(), encodedEvent);
            log.info("Video encoded event published for movie: {}", event.getNasPath());
        } catch (Exception e) {
            log.error("Encoding failed for movie: {} - {}", event.getNasPath(), e.getMessage());

            // publishing failure event (Fixed to 4 args)
            VideoEncodedEvent failureEvent = new VideoEncodedEvent(
                event.getNasPath(),
                null,
                false,
                e.getMessage()
            );

            kafkaTemplate.send(VIDEO_ENCODED_TOPIC, event.getNasPath(), failureEvent);
        } finally {
            // cleanup job
            cleanupTempFiles(jobPath);
        }
    }

    // encoding raw file to separate qualities with ffmpeg
    private void encodeToHls(String inputPath, String outputDir, int width, int height, int bitrate) throws IOException, InterruptedException {
        String playlistPath = outputDir + "/playlist.m3u8";
        String segmentPattern = outputDir + "/segment_%03d.ts";

        // ffmpeg cmd for HLS encoding
        List<String> command = Arrays.asList(
            ffmpegPath,
            "-i", inputPath, // input file
            "-vf", "scale=" + width + ":" + height, // scale to resolution
            "-c:v", "libx264", // video codec
            "-b:v", bitrate + "k", // video bitrate
            "-c:a", "aac", // audio codec
            "-b:a", "128k", // audio bitrate
            "-hls_time", "10", // 10 second segments
            "-hls_list_size", "0", // keep all segments
            "-hls_segment_filename", segmentPattern, // segment naming
            "-f", "hls", // output format - HLS
            playlistPath // output playlist path
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("ffmpeg Encoding failed for playlist: " + playlistPath + " with exit code: " + exitCode);
        }
    }

    // generating master playlist
    private void generateMasterPlaylist(String masterPlaylistPath) throws IOException {
        StringBuilder master = new StringBuilder();
        master.append("#EXTM3U\n"); // extended m3us8 playlist
        master.append("EXT-X-VERSION:3\n\n");

        // adding each quality to master playlist
        int[][] qualities = {
            {1920, 5000, 1080},
            {1280, 2800, 720},
            {854, 1200, 480},
            {640, 800, 360}
        };

        for (int[] q : qualities) {
            int width = q[0];
            int bitrate = q[1];
            int height = q[2];

            master.append("#EXT-X-STREAM-INF:BANDWIDTH=")
                .append(bitrate * 1000)
                .append(", RESOLUTION=").append(width).append("x").append(height)
                .append(",CODECS=\"avc1.42e01e,mp4a.40.2\"\n");
            master.append(height).append("p/playlist.m3u8\n\n");
        }

        Files.writeString(Paths.get(masterPlaylistPath), master.toString());
    }

    private String stripExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(0, lastDot) : filename;
    }

    private String buildEncodedBasePath(VideoUploadedEvent event) {
        String pathSegment = (event.getPath() == null || event.getPath().isBlank())
            ? ""
            : event.getPath() + "/";

        return event.getWorkspaceRoot() + "/encoded/" + pathSegment + stripExtension(event.getOriginalFileName());
    }

    // cleanup job after encoding
    private void cleanupTempFiles(String jobPath) {
        Path dirPath = Paths.get(jobPath);
        if (!Files.exists(dirPath)) {
            return;
        }

        // Fixed Stream closure & File delete warning
        try (Stream<Path> pathStream = Files.walk(dirPath)) {
            pathStream
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    if (!file.delete()) {
                        log.warn("Could not delete temp file: {}", file.getAbsolutePath());
                    }
                });

            log.info("Cleaned up temp files at path: {}", jobPath);
        } catch (IOException e) {
            log.warn("Failed to clean up temp files at path: {} - {}", jobPath, e.getMessage());
        }
    }
}