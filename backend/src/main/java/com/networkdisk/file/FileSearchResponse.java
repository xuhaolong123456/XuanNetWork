package com.networkdisk.file;

import java.time.LocalDateTime;
import java.util.List;

public record FileSearchResponse(List<SearchItem> items, FilePageResponse page) {
    public record SearchItem(Long id, String name, FileNodeType type, long sizeBytes, String mimeType,
                             LocalDateTime updatedAt, boolean downloadAllowed, boolean previewAllowed,
                             List<HighlightSegment> highlight) { }
    public record HighlightSegment(String text, boolean matched) { }
}
