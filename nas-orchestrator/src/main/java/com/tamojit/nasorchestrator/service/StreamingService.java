package com.tamojit.nasorchestrator.service;

import com.tamojit.nasorchestrator.cache.CaffeineSegmentCache;
import com.tamojit.nasorchestrator.cache.RedisSegmentCache;
import com.tamojit.nasorchestrator.client.SmbFileClient;
import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StreamingService {
    private static final Logger log = LoggerFactory.getLogger(StreamingService.class);
    private final CaffeineSegmentCache l1;
    private final RedisSegmentCache l2;
    private final CIFSContext cifsContext;
    private final String shareBaseUrl;
    private final SmbFileClient smbFileClient;

    // separate, bounded pool for SMB reads — keeps a burst of cache misses from starving the HTTP request-handling threads (production doc §7)
    private final ExecutorService smbExecutor = Executors.newFixedThreadPool(8);
    // prefetch runs on its own, smaller pool so on-demand reads never queue behind it
    private final ExecutorService prefetchExecutor = Executors.newFixedThreadPool(3);

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("segment_(\\d+)\\.ts$");

    public StreamingService(
        CaffeineSegmentCache l1,
        RedisSegmentCache l2,
        CIFSContext cifsContext,
        @Value("${smb.share-base-url}") String shareBaseUrl,
        SmbFileClient smbFileClient
    ) {
        this.l1 = l1;
        this.l2 = l2;
        this.cifsContext = cifsContext;
        this.shareBaseUrl = shareBaseUrl.endsWith("/") ? shareBaseUrl : shareBaseUrl + "/";
        this.smbFileClient = smbFileClient;
    }

    // relativePath - "encoded/{movieId}/720p/segment_004.ts"
    public byte[] getSegment(String relativePath) {
        byte[] l1hit = l1.get(relativePath);
        if (l1hit != null) {
            log.info("L1 hit: {}", relativePath);
            firePrefetch(relativePath);
            return l1hit;
        }

        byte[] l2hit = l2.get(relativePath);
        if (l2hit != null) {
            log.info("L2 hit: {}", relativePath);
            l1.put(relativePath, l2hit); // promote to L1
            firePrefetch(relativePath);
            return l2hit;
        }

        // L1 & L2 miss -> SMB fetch
        log.info("Cache miss, reading SMB: {}", relativePath);
        byte[] data = readFromSmb(relativePath);
        l1.put(relativePath, data);
        l2.put(relativePath, data);
        firePrefetch(relativePath);
        return data;
    }

    // relativePath - "encoded/{movieId}/master.m3u8" or ".../720p/playlist.m3u8"
    public String getRewrittenPlaylist(String relativePath, String token) throws IOException {
        String basePath = relativePath.substring(0, relativePath.lastIndexOf("/") + 1);
        String tokenSuffix = (token != null && !token.isBlank())
            ? "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
            : "";

        String raw;
        try (InputStream inputStream = smbFileClient.preview(relativePath)) {
            raw = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        StringBuilder rewritten = new StringBuilder();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                rewritten.append(line).append("\n");
                continue;
            }

            // Variant playlist references (e.g. 360p/playlist.m3u8 in a master playlist)
            // must be routed to /stream/playlist so they get recursively rewritten.
            // Segment files (.ts) go to /stream/segment for the cache+SMB read path.
            String fullPath = basePath + trimmed;
            if (trimmed.endsWith(".m3u8")) {
                rewritten.append("/api/v1/nas-orchestrator/stream/playlist?path=").append(fullPath).append(tokenSuffix).append("\n");
            } else {
                rewritten.append("/api/v1/nas-orchestrator/stream/segment?path=").append(fullPath).append(tokenSuffix).append("\n");
            }
        }

        return rewritten.toString();
    }

    private byte[] readFromSmb(String relativePath) {
        try (SmbFile smbFile = new SmbFile(shareBaseUrl + relativePath, cifsContext);
             SmbFileInputStream inputStream = new SmbFileInputStream(smbFile)) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            inputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("SMB read failed for " + relativePath, e);
        }
    }

    // fire-and-forget N+1 prefetch: parse the segment index out of the filename,
    // computing segment N+1 in the same quality dir, warm L1 (and L2) if not already cached
    private void firePrefetch(String relativePath) {
        Matcher matcher = SEGMENT_PATTERN.matcher(relativePath);
        if (!matcher.find()) return; // not a numbered segment (e.g. a playlist) — nothing to prefetch

        int index = Integer.parseInt(matcher.group(1));
        String nextPath = relativePath.replace(
            String.format("segment_%03d.ts", index),
            String.format("segment_%03d.ts", index + 1)
        );

        if (l1.get(nextPath) != null) return;

        prefetchExecutor.submit(() -> {
            try {
                if (l1.get(nextPath) != null) return;

                byte[] data = readFromSmb(nextPath);
                l1.put(nextPath, data);
                l2.put(nextPath, data);
                log.debug("Prefetched: {}", nextPath);
            } catch (Exception e) {
                // next segment may simply not exist yet (last segment of the ladder) — not an error
                log.debug("Prefetch skipped for {}: {}", nextPath, e.getMessage());
            }
        });
    }
}
