package com.networkdisk.file;

public record FilePreviewResponse(Long fileId, String name, String format, String content,
                                  boolean downloadAllowed) {
    public FilePreviewResponse(Long fileId, String name, String format, String content) {
        this(fileId, name, format, content, true);
    }
}
