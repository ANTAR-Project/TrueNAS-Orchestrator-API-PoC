package com.tamojit.nasorchestrator.util;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class MimeTypeResolver {
    private static final Map<String, MediaType> EXTENSION_TO_MEDIA_TYPE = Map.ofEntries(
        Map.entry("jpg", MediaType.IMAGE_JPEG),
        Map.entry("jpeg", MediaType.IMAGE_JPEG),
        Map.entry("png", MediaType.IMAGE_PNG),
        Map.entry("gif", MediaType.IMAGE_GIF),
        Map.entry("webp", MediaType.parseMediaType("image/webp")),
        Map.entry("svg", MediaType.parseMediaType("image/svg+xml")),
        Map.entry("pdf", MediaType.APPLICATION_PDF),
        Map.entry("en", MediaType.APPLICATION_PDF),
        Map.entry("txt", MediaType.TEXT_PLAIN),
        Map.entry("md", MediaType.parseMediaType("text/markdown")),
        Map.entry("csv", MediaType.parseMediaType("text/csv")),
        Map.entry("json", MediaType.APPLICATION_JSON)
    );

    private static final Set<String> PREVIEWABLE_EXTENSIONS = EXTENSION_TO_MEDIA_TYPE.keySet();

    public boolean isPreviewable(String filename) {
        return PREVIEWABLE_EXTENSIONS.contains(extensionOf(filename));
    }

    public MediaType resolve(String filename) {
        return EXTENSION_TO_MEDIA_TYPE.getOrDefault(extensionOf(filename), MediaType.APPLICATION_OCTET_STREAM);
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot == -1 ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
