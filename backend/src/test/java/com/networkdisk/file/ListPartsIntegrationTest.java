package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest(showSql = false)
@Import(ListPartsService.class)
class ListPartsIntegrationTest {
    @Autowired ListPartsService service;
    @Autowired ChunkUploadSessionRepository sessions;
    @Autowired ChunkUploadPartRepository parts;

    @Test
    void pagesThousandsOfRowsFromDatabaseWithExclusiveNumericCursorAndStableExpiry() {
        String id = "upload-" + UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(3600);
        sessions.saveAndFlush(new ChunkUploadSession(id, 7, "large.txt", 2005 * 4L, "a".repeat(32), 4, expiry, null));
        parts.saveAllAndFlush(IntStream.rangeClosed(1, 2001).filter(number -> number != 5)
                .mapToObj(number -> new ChunkUploadPart(id, number, 4, expiry)).toList());
        var first = service.list(7L, id, 1000, 0);
        assertThat(first.uploadedChunks()).hasSize(1000);
        assertThat(first.uploadedChunks().getFirst().partNumber()).isEqualTo(1);
        assertThat(first.nextPartNumberMarker()).isEqualTo(1001);
        var last = service.list(7L, id, 1000, first.nextPartNumberMarker());
        assertThat(last.uploadedChunks()).hasSize(1000);
        assertThat(last.uploadedChunks().getFirst().partNumber()).isEqualTo(1002);
        assertThat(last.uploadedChunks().getLast().partNumber()).isEqualTo(2001);
        assertThat(last.truncated()).isFalse();
        assertThat(last.nextPartNumberMarker()).isZero();
        assertThat(sessions.findById(id).orElseThrow().getExpiresAt()).isEqualTo(expiry);
        assertThat(parts.count()).isEqualTo(2000);
        // 测试不创建任何分片文件，证明列表查询只依赖数据库元数据。
    }
}
