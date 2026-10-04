package com.networkdisk.file;

import java.time.LocalDateTime;

public record FileItemResponse(Long id, String name, FileNodeType type, long sizeBytes,
                               String mimeType, LocalDateTime updatedAt,
                               boolean downloadAllowed, boolean previewAllowed) {
    public FileItemResponse(Long id, String name, FileNodeType type, long sizeBytes,
                            String mimeType, LocalDateTime updatedAt) {
        this(id, name, type, sizeBytes, mimeType, updatedAt, true, true);
    }
}
