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
import java.util.concurrent.TimeUnit;
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

    // HLS segment duration in seconds — must match -force_key_frames and -hls_time below.
    private static final int HLS_SEGMENT_SECONDS = 6;

    // Video qualities to encode.
    // format: { bitrate-kbps, height, maxrate-kbps, declaredWidth }
    //
    //  bitrate      — target average bitrate passed to ffmpeg -b:v
    //  height       — target height; ffmpeg uses scale=-2:h so the actual encoded width
    //                 is source-dependent (preserves SAR, never upscales, even width guaranteed).
    //  maxrate      — peak bitrate cap (≈ bitrate × 1.07); used for -maxrate and as the
    //                 BANDWIDTH upper bound in the master playlist (Apple HLS spec requirement).
    //  declaredWidth — standard 16:9 width written into the RESOLUTION manifest attribute.
    //                 This is a player display hint, not a codec contract — RFC 8216 §4.3.4.2
    //                 defines RESOLUTION as informational. Netflix/YouTube use the same approach:
    //                 declare the canonical 16:9 size so the quality picker shows "1080p" etc.,
    //                 while the actual stream geometry is determined by the encoded bitstream.
    //
    // CODECS strings per H.264 Annex A — High profile (0x64), level by height:
    //   1080p → level 4.0 (avc1.640028), 720p → level 3.1 (avc1.64001f), 480p/360p → level 3.0 (avc1.64001e)
    private static final List<int[]> VIDEO_QUALITIES = Arrays.asList(
        new int[]{5000, 1080, 5350, 1920}, // 1080p — avc1.640028
        new int[]{2800,  720, 2996, 1280}, // 720p  — avc1.64001f
        new int[]{1200,  480, 1284,  854}, // 480p  — avc1.64001e
        new int[]{ 800,  360,  856,  640}  // 360p  — avc1.64001e
    );

    // Encode timeout: 30 minutes per rendition (generous for a long movie rung).
    private static final long ENCODE_TIMEOUT_MINUTES = 30;

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
                int bitrate = qualities[0];
                int height = qualities[1];
                int maxrate = qualities[2];

                String qualityDir = jobPath + "/encoded/" + height + "p";
                Files.createDirectories(Paths.get(qualityDir));

                encodeToHls(localVideoPath, qualityDir, height, bitrate, maxrate);
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

    private void encodeToHls(String inputPath, String outputDir, int height, int bitrate, int maxrate) throws IOException, InterruptedException {
        String playlistPath = outputDir + "/playlist.m3u8";
        String segmentPattern = outputDir + "/segment_%03d.ts";
        String segStr = String.valueOf(HLS_SEGMENT_SECONDS);

        // ffmpeg cmd for HLS encoding
        List<String> command = Arrays.asList(
            ffmpegPath,
            "-i", inputPath,                                              // input file
            "-vf", "scale=-2:" + height,                                  // aspect-correct scale, no upscale
            "-c:v", "libx264",                                            // video codec
            "-profile:v", "high",                                         // H.264 High profile → avc1.64xxxx
            "-pix_fmt", "yuv420p",                                        // broadest decoder compat
            "-b:v", bitrate + "k",                                        // target video bitrate
            "-maxrate", maxrate + "k",                                    // peak bitrate cap (≥ BANDWIDTH declared)
            "-bufsize", (bitrate * 2) + "k",                              // VBV buffer = 2× target
            "-sc_threshold", "0",                                         // disable scene-cut keyframes
            "-force_key_frames", "expr:gte(t,n_forced*" + segStr + ")",   // IDR every segment boundary
            "-c:a", "aac",                                                // audio codec
            "-b:a", "128k",                                               // audio bitrate
            "-hls_time", segStr,                                          // target segment duration
            "-hls_playlist_type", "vod",                                  // VOD playlist (EXT-X-ENDLIST)
            "-hls_flags", "independent_segments",                         // allow mid-stream rung switches
            "-hls_list_size", "0",                                        // keep all segments in playlist
            "-hls_segment_filename", segmentPattern,                      // segment naming
            "-f", "hls",                                                  // output format
            playlistPath                                                  // output playlist path
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        // Waiting with a hard timeout so a stuck ffmpeg process doesn't block the consumer thread forever.
        boolean finished = process.waitFor(ENCODE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("ffmpeg timed out after " + ENCODE_TIMEOUT_MINUTES + " minutes for: " + playlistPath);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("ffmpeg encoding failed for playlist: " + playlistPath + " with exit code: " + exitCode);
        }
    }

    private void generateMasterPlaylist(String masterPlaylistPath) throws IOException {
        StringBuilder master = new StringBuilder();
        master.append("#EXTM3U\n");        // extended m3u8 playlist
        master.append("#EXT-X-VERSION:3\n");

        for (int[] q : VIDEO_QUALITIES) {
            int bitrate       = q[0];
            int height        = q[1];
            int maxrate       = q[2]; // BANDWIDTH upper bound, per Apple HLS spec
            int declaredWidth = q[3]; // standard 16:9 width — manifest display hint for quality picker

            // H.264 High profile CODECS string (avc1.PPCCLL):
            //   PP = 0x64 (High profile), CC = 0x00 (constraint flags), LL = level
            String codecs = switch (height) {
                case 1080 -> "avc1.640028"; // High, level 4.0
                case 720  -> "avc1.64001f"; // High, level 3.1
                default   -> "avc1.64001e"; // High, level 3.0 (480p and 360p)
            };

            master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(maxrate * 1000)
                  .append(",RESOLUTION=").append(declaredWidth).append("x").append(height)
                  .append(",CODECS=\"").append(codecs).append(",mp4a.40.2\"")
                  .append("\n");
            master.append(height).append("p/playlist.m3u8\n");
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