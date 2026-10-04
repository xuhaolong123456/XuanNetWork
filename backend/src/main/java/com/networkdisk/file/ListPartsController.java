package com.networkdisk.file;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ListPartsController {
    private final ListPartsService parts;

    public ListPartsController(ListPartsService parts) { this.parts = parts; }

    @GetMapping("/api/v1/files/file/chunk-upload")
    public org.springframework.http.ResponseEntity<ChunkUploadResult<ListPartsResponse>> list(@AuthenticationPrincipal Long userId,
            @RequestParam String identifier, @RequestParam(defaultValue = "100") int maxParts,
            @RequestParam(defaultValue = "0") int partNumberMarker) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ChunkUploadResult<>(200, "success", parts.list(userId, identifier, maxParts, partNumberMarker)));
    }
}
