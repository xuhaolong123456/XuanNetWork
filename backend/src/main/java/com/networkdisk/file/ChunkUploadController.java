package com.networkdisk.file;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ChunkUploadController {
    private final ChunkUploadService chunks;

    public ChunkUploadController(ChunkUploadService chunks) { this.chunks = chunks; }

    @PostMapping(value = "/api/v1/files/file/chunk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChunkUploadResult<ChunkUploadResponse> upload(@AuthenticationPrincipal Long userId,
            @RequestParam String name, @RequestParam("node_type") String nodeType,
            @RequestParam("size_bytes") long sizeBytes, @RequestParam String uploadId,
            @RequestParam int partNumber, @RequestParam String fileMd5,
            @RequestPart MultipartFile chunk) {
        return new ChunkUploadResult<>(200, "分片上传成功",
                chunks.upload(userId, name, nodeType, sizeBytes, uploadId, partNumber, fileMd5, chunk));
    }
}
