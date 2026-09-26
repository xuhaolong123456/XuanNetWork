package com.networkdisk.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateDirectoryRequest(
        @Positive(message = "父目录 ID 必须为正数")
        Long parentId,
        @NotBlank(message = "请输入文件夹名称")
        @Size(max = 255, message = "文件夹名称不能超过 255 个字符")
        String folderName) {
}
