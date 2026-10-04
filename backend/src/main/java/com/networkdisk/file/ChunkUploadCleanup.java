package com.networkdisk.file;

import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class ChunkUploadCleanup {
    private final ChunkUploadSessionRepository sessions;
    private final ChunkUploadService chunks;

    public ChunkUploadCleanup(ChunkUploadSessionRepository sessions, ChunkUploadService chunks) {
        this.sessions = sessions;
        this.chunks = chunks;
    }

    @Scheduled(fixedDelayString = "${app.storage.chunk-cleanup-delay-ms:3600000}")
    public void cleanExpired() {
        for (String id : sessions.findExpiredIds(Instant.now())) {
            try { chunks.cleanExpired(id); }
            catch (RuntimeException exception) {
                // 单个会话失败不影响其他会话回收，下次调度重新尝试。
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("上传会话清理失败：{}", id, exception);
            }
        }
    }
}
