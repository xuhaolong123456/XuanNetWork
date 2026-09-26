package com.networkdisk.file;

import java.time.LocalDateTime;

public record TrashItemResponse(Long id, String name, FileNodeType type, long sizeBytes,
                                LocalDateTime deletedAt, Long parentId, String parentName) {
}
