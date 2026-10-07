package com.networkdisk.file;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DownloadRequest(
        @NotEmpty(message = "请选择需要下载的文件或文件夹")
        @Size(max = 1000, message = "单次最多选择 1000 项")
        List<@NotNull(message = "资源 ID 不能为空") @Positive(message = "资源 ID 必须为正数") Long> ids,
        @Size(max = 120, message = "压缩包名称不能超过 120 个字符") String downloadName) { }
