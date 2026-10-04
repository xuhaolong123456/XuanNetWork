package com.networkdisk.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record QuickCheckRequest(
        @NotBlank(message = "fileHash不能为空")
        String fileHash,
        @NotNull(message = "fileSize不能为空")
        @PositiveOrZero(message = "fileSize不能小于0")
        Long fileSize,
        @NotBlank(message = "fileName不能为空")
        @Size(max = 255, message = "fileName不能超过255个字符")
        String fileName,
        Long parentFolderId,
        String uploadId) {
    public QuickCheckRequest(String fileHash, Long fileSize, String fileName, Long parentFolderId) {
        this(fileHash, fileSize, fileName, parentFolderId, null);
    }
}
