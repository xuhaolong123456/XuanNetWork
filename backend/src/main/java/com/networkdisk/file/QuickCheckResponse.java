package com.networkdisk.file;

public record QuickCheckResponse(boolean exist, Long fileId, String uploadId, Long chunkSize, Integer totalParts) {
    public QuickCheckResponse(boolean exist, Long fileId) {
        this(exist, fileId, null, null, null);
    }
}
