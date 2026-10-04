package com.networkdisk.file;

import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListPartsService {
    private final ChunkUploadSessionRepository sessions;
    private final ChunkUploadPartRepository parts;

    public ListPartsService(ChunkUploadSessionRepository sessions, ChunkUploadPartRepository parts) {
        this.sessions = sessions;
        this.parts = parts;
    }

    @Transactional(readOnly = true)
    public ListPartsResponse list(Long userId, String identifier, int maxParts, int marker) {
        if (userId == null) throw new FileBusinessException("401", "用户未登录，请重新登录", 401);
        if (identifier == null || !identifier.matches("upload-[0-9a-f-]{36}")) throw missing();
        if (maxParts < 1 || maxParts > 1000 || marker < 0) {
            throw new FileBusinessException("400", "maxParts必须在1到1000之间，partNumberMarker不能小于0", 400);
        }
        // 普通只读查询不获取写锁、不续期；归属验证必须早于读取任何分片信息。
        ChunkUploadSession session = sessions.findById(identifier).orElseThrow(ListPartsService::missing);
        if (session.getOwnerId() != userId) throw new FileBusinessException("40301", "无权限访问该分片会话", 403);
        if (!session.getExpiresAt().isAfter(Instant.now())) throw missing();
        // 多取一条判断是否截断，不计数全表、不载入整个会话的分片集合。
        // 续传列表只展示有效分片，过滤逻辑删除和已过期的记录。
        var rows = parts.findPage(identifier, marker, Instant.now(), PageRequest.of(0, maxParts + 1));
        boolean truncated = rows.size() > maxParts;
        var uploaded = rows.stream().limit(maxParts)
                .map(part -> new ListPartsResponse.UploadedChunk(part.getPartNumber(), part.getSize(), part.getEtag())).toList();
        int nextMarker = truncated ? uploaded.getLast().partNumber() : 0;
        return new ListPartsResponse(identifier, session.getTotalParts(), uploaded, truncated, nextMarker);
    }

    private static FileBusinessException missing() {
        return new FileBusinessException("40001", "分片会话不存在或者已经过期", 400);
    }
}
