package com.networkdisk.file;

import java.time.LocalDateTime;

public record FileItemResponse(Long id, String name, FileNodeType type, long sizeBytes,
                               String mimeType, LocalDateTime updatedAt) {
}
