package com.networkdisk.file;

import com.networkdisk.common.Result;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService files;

    public FileController(FileService files) { this.files = files; }

    @GetMapping
    public Result<FileListResponse> list(@AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Result.success(files.list(userId, parentId, page, size));
    }
}
