package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class ChunkUploadServiceTest {
    private static final String ID = "upload-123e4567-e89b-42d3-a456-426614174000";
    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    @TempDir Path root;
    private final ChunkUploadSessionRepository sessions = mock(ChunkUploadSessionRepository.class);
    private final ChunkUploadPartRepository parts = mock(ChunkUploadPartRepository.class);
    private final java.util.Map<Integer, ChunkUploadPart> savedParts = new java.util.TreeMap<>();
    private ChunkUploadService service;

    @BeforeEach
    void setUp() {
        service = new ChunkUploadService(sessions, parts, root);
        when(parts.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ChunkUploadPart part = invocation.getArgument(0);
            savedParts.put(part.getPartNumber(), part);
            return part;
        });
        when(parts.existsByUploadIdAndPartNumber(org.mockito.ArgumentMatchers.eq(ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> savedParts.containsKey(invocation.getArgument(1)));
        when(parts.findActivePartNumbers(org.mockito.ArgumentMatchers.eq(ID), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> java.util.List.copyOf(savedParts.keySet()));
        when(sessions.findForUpdate(ID)).thenReturn(Optional.of(session(Instant.now().plusSeconds(3600))));
    }

    @Test
    void receivesOutOfOrderAndSmallLastPartWithoutMerging() throws Exception {
        assertThat(upload(3, "x").mergeFlag()).isEqualTo(MergeFlag.INCOMPLETE);
        assertThat(upload(1, "abcd").finishedPartList()).containsExactly(1, 3);
        ChunkUploadResponse response = upload(2, "efgh");
        assertThat(response.finishedPartList()).containsExactly(1, 2, 3);
        assertThat(response.mergeFlag()).isEqualTo(MergeFlag.READY);
        try (var files = Files.list(root.resolve("chunks").resolve(ID))) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("1.part", "2.part", "3.part");
        }
    }

    @Test
    void retryAndNewServiceInstanceReuseSavedPartWithoutReadingBody() throws Exception {
        upload(1, "abcd");
        MultipartFile retry = mock(MultipartFile.class);
        when(retry.getSize()).thenReturn(4L);
        service = new ChunkUploadService(sessions, parts, root);
        assertThat(service.upload(7L, "large.txt", "FILE", 9, ID, 1, MD5, retry).finishedPartList())
                .containsExactly(1);
        verify(retry, never()).getInputStream();
        assertThat(Files.readString(root.resolve("chunks").resolve(ID).resolve("1.part"))).isEqualTo("abcd");
    }

    @Test
    void rejectsUnauthorizedInvalidAndMismatchedRequestsBeforeWriting() {
        assertError("401", () -> service.upload(null, "large.txt", "FILE", 9, ID, 1, MD5, part("abcd")));
        assertError("40301", () -> service.upload(8L, "large.txt", "FILE", 9, ID, 1, MD5, part("abcd")));
        assertError("40001", () -> service.upload(7L, "large.txt", "FILE", 9, "../bad", 1, MD5, part("abcd")));
        assertError("40002", () -> upload(0, "abcd"));
        assertError("40002", () -> upload(4, "abcd"));
        assertError("40003", () -> upload(1, "abc"));
        assertError("41301", () -> upload(1, "abcde"));
        assertError("40003", () -> service.upload(7L, "large.txt", "FILE", 10, ID, 1, MD5, part("abcd")));
        assertError("40001", () -> service.upload(7L, "changed.txt", "FILE", 9, ID, 1, MD5, part("abcd")));
        assertError("40001", () -> service.upload(7L, "large.txt", "DIRECTORY", 9, ID, 1, MD5, part("abcd")));
        assertError("40001", () -> service.upload(7L, "large.txt", "FILE", 9, ID, 1, "a".repeat(32), part("abcd")));
        assertThat(root.resolve("chunks")).doesNotExist();
    }

    @Test
    void unknownAndExpiredSessionsAreRejected() {
        when(sessions.findForUpdate(ID)).thenReturn(Optional.empty());
        assertError("40001", () -> upload(1, "abcd"));
        when(sessions.findForUpdate(ID)).thenReturn(Optional.of(session(Instant.now().minusSeconds(1))));
        assertError("40001", () -> upload(1, "abcd"));
    }

    @Test
    void resumesOnlyMatchingOwnedLiveSessionWithoutCreatingAnother() {
        assertThat(service.resumeSession(7, ID, "large.txt", 9, MD5).getId()).isEqualTo(ID);
        assertError("40301", () -> service.resumeSession(8, ID, "large.txt", 9, MD5));
        assertError("40001", () -> service.resumeSession(7, ID, "changed.txt", 9, MD5));
        assertError("40001", () -> service.resumeSession(7, "invalid", "large.txt", 9, MD5));
        assertThat(root.resolve("chunks")).doesNotExist();
    }

    @Test
    void diskFailureDoesNotMarkPartFinishedAndCanBeRetried() throws Exception {
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getSize()).thenReturn(4L);
        when(broken.getInputStream()).thenThrow(new IOException("测试写入故障"));
        assertError("50001", () -> service.upload(7L, "large.txt", "FILE", 9, ID, 1, MD5, broken));
        assertThat(root.resolve("chunks").resolve(ID).resolve("1.part")).doesNotExist();
        assertThat(upload(1, "abcd").finishedPartList()).containsExactly(1);
    }

    @Test
    void metadataFailureLeavesRecoverableCompletePartAndRetryDoesNotReadBody() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("测试元数据故障"))
                .when(parts).saveAndFlush(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> upload(1, "abcd")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(savedParts).isEmpty();
        assertThat(root.resolve("chunks").resolve(ID).resolve("1.part")).exists();
        org.mockito.Mockito.doAnswer(invocation -> {
            ChunkUploadPart part = invocation.getArgument(0);
            savedParts.put(part.getPartNumber(), part);
            return part;
        }).when(parts).saveAndFlush(org.mockito.ArgumentMatchers.any());
        MultipartFile retry = mock(MultipartFile.class);
        when(retry.getSize()).thenReturn(4L);
        assertThat(service.upload(7L, "large.txt", "FILE", 9, ID, 1, MD5, retry).finishedPartList()).containsExactly(1);
        verify(retry, never()).getInputStream();
    }

    @Test
    void checksActualStreamLengthAndRemovesPartialFiles() throws Exception {
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getSize()).thenReturn(4L);
        when(broken.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[5]));
        assertError("41301", () -> service.upload(7L, "large.txt", "FILE", 9, ID, 1, MD5, broken));
        try (var paths = Files.list(root.resolve("chunks").resolve(ID))) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test
    void cleansCompletedAndIncompleteExpiredSessionsButRetainsLiveSessions() throws Exception {
        upload(1, "abcd");
        service.cleanExpired(ID);
        assertThat(root.resolve("chunks").resolve(ID)).exists();
        ChunkUploadSession expired = session(Instant.now().minusSeconds(1));
        when(sessions.findForUpdate(ID)).thenReturn(Optional.of(expired));
        service.cleanExpired(ID);
        assertThat(root.resolve("chunks").resolve(ID)).doesNotExist();
        verify(sessions).delete(expired);
        verify(parts).deleteByUploadId(ID);
    }

    @Test
    void completedSessionsAreAlsoReclaimedBecauseThisVersionDoesNotMerge() {
        upload(1, "abcd");
        upload(2, "efgh");
        assertThat(upload(3, "x").mergeFlag()).isEqualTo(MergeFlag.READY);
        when(sessions.findForUpdate(ID)).thenReturn(Optional.of(session(Instant.now().minusSeconds(1))));
        service.cleanExpired(ID);
        assertThat(root.resolve("chunks").resolve(ID)).doesNotExist();
    }

    private ChunkUploadSession session(Instant expires) {
        return new ChunkUploadSession(ID, 7, "large.txt", 9, MD5, 4, expires, null);
    }
    private MockMultipartFile part(String content) { return new MockMultipartFile("chunk", content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private ChunkUploadResponse upload(int number, String content) {
        return service.upload(7L, "large.txt", "FILE", 9, ID, number, MD5, part(content));
    }
    private void assertError(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(FileBusinessException.class).extracting("code").isEqualTo(code);
    }
}
