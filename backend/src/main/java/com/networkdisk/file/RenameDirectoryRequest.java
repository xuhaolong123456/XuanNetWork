package com.networkdisk.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameDirectoryRequest(
        @NotBlank(message = "文件名为空")
        @Size(max = 255, message = "文件夹名称不能超过 255 个字符")
        String folderName) {
}
