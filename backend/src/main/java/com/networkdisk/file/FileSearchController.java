package com.networkdisk.file;

import com.networkdisk.common.Result;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
public class FileSearchController {
    private final FileSearchService search;

    public FileSearchController(FileSearchService search) { this.search = search; }

    @GetMapping("/search")
    public Result<FileSearchResponse> search(@AuthenticationPrincipal Long userId,
            @RequestParam String q, @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(search.search(userId, q, parentId, page, size));
    }
}
