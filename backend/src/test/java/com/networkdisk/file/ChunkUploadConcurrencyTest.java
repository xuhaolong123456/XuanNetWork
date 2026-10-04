package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockMultipartFile;

@DataJpaTest(showSql = false)
@Import(ChunkUploadService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChunkUploadConcurrencyTest {
    @TempDir static Path root;
    @Autowired ChunkUploadService service;
    @Autowired ChunkUploadSessionRepository sessions;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.root", () -> root.toString());
    }

    @Test
    void concurrentRetriesPublishOnePartAndPreserveDurableSession() throws Exception {
        String id = "upload-" + UUID.randomUUID();
        String md5 = "a".repeat(32);
        sessions.saveAndFlush(new ChunkUploadSession(id, 7, "large.txt", 8, md5, 4, Instant.now().plusSeconds(3600), null));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<ChunkUploadResponse>>();
            for (int i = 0; i < 8; i++) {
                tasks.add(executor.submit(() -> service.upload(7L, "large.txt", "FILE", 8, id, 1, md5,
                        new MockMultipartFile("chunk", "abcd".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
            }
            for (var task : tasks) assertThat(task.get(15, TimeUnit.SECONDS).finishedPartList()).containsExactly(1);
        }
        assertThat(sessions.findById(id)).isPresent();
        try (var paths = Files.list(root.resolve("chunks").resolve(id))) { assertThat(paths.toList()).hasSize(1); }
        assertThat(service.upload(7L, "large.txt", "FILE", 8, id, 2, md5,
                new MockMultipartFile("chunk", "efgh".getBytes(java.nio.charset.StandardCharsets.UTF_8))).mergeFlag())
                .isEqualTo(MergeFlag.READY);
        sessions.deleteById(id);
    }
}
