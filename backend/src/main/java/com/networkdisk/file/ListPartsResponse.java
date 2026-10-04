package com.networkdisk.file;

import java.util.List;

public record ListPartsResponse(String identifier, int totalParts, List<UploadedChunk> uploadedChunks,
                                boolean truncated, int nextPartNumberMarker) {
    public record UploadedChunk(int partNumber, long size, String etag) { }
}
