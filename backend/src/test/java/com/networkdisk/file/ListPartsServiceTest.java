package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class ListPartsServiceTest {
    private static final String ID = "upload-123e4567-e89b-42d3-a456-426614174000";
    private final ChunkUploadSessionRepository sessions = mock(ChunkUploadSessionRepository.class);
    private final ChunkUploadPartRepository parts = mock(ChunkUploadPartRepository.class);
    private final ListPartsService service = new ListPartsService(sessions, parts);
    private final Instant expiry = Instant.now().plusSeconds(3600);

    @BeforeEach
    void setUp() { when(sessions.findById(ID)).thenReturn(Optional.of(session(7, expiry))); }

    @Test
    void boundsQueryAndReturnsSortedCursorPageWithoutAnyWritesOrRenewal() {
        when(parts.findPage(eq(ID), eq(1), any(), eq(PageRequest.of(0, 3)))).thenReturn(List.of(
                new ChunkUploadPart(ID, 2, 4, expiry), new ChunkUploadPart(ID, 4, 4, expiry), new ChunkUploadPart(ID, 6, 1, expiry)));
        ListPartsResponse result = service.list(7L, ID, 2, 1);
        assertThat(result.totalParts()).isEqualTo(6);
        assertThat(result.uploadedChunks()).extracting(ListPartsResponse.UploadedChunk::partNumber).containsExactly(2, 4);
        assertThat(result.uploadedChunks()).extracting(ListPartsResponse.UploadedChunk::etag).containsOnly("");
        assertThat(result.truncated()).isTrue();
        assertThat(result.nextPartNumberMarker()).isEqualTo(4);
        assertThat(sessions.findById(ID).orElseThrow().getExpiresAt()).isEqualTo(expiry);
        verify(parts).findPage(eq(ID), eq(1), any(), eq(PageRequest.of(0, 3)));
        verifyNoMoreInteractions(parts);
        verify(sessions, never()).findForUpdate(any());
        verify(sessions, never()).save(any());
    }

    @Test
    void exactLimitEmptyAndLastPageReturnNoNextCursor() {
        when(parts.findPage(eq(ID), eq(0), any(), eq(PageRequest.of(0, 2)))).thenReturn(List.of(new ChunkUploadPart(ID, 6, 1, expiry)));
        var result = service.list(7L, ID, 1, 0);
        assertThat(result.truncated()).isFalse();
        assertThat(result.nextPartNumberMarker()).isZero();
        assertThat(result.uploadedChunks().getFirst().size()).isEqualTo(1);
        when(parts.findPage(eq(ID), eq(Integer.MAX_VALUE), any(), eq(PageRequest.of(0, 1001)))).thenReturn(List.of());
        var empty = service.list(7L, ID, 1000, Integer.MAX_VALUE);
        assertThat(empty.uploadedChunks()).isEmpty();
        assertThat(empty.truncated()).isFalse();
        assertThat(empty.nextPartNumberMarker()).isZero();
    }

    @Test
    void invalidUnauthorizedForeignMissingAndExpiredRequestsNeverReadParts() {
        assertError("401", () -> service.list(null, ID, 100, 0));
        assertError("40001", () -> service.list(7L, "../bad", 100, 0));
        assertError("400", () -> service.list(7L, ID, 0, 0));
        assertError("400", () -> service.list(7L, ID, 1001, 0));
        assertError("400", () -> service.list(7L, ID, 100, -1));
        assertError("40301", () -> service.list(8L, ID, 100, 0));
        when(sessions.findById(ID)).thenReturn(Optional.empty());
        assertError("40001", () -> service.list(7L, ID, 100, 0));
        when(sessions.findById(ID)).thenReturn(Optional.of(session(7, Instant.now().minusSeconds(1))));
        assertError("40001", () -> service.list(7L, ID, 100, 0));
        verifyNoInteractions(parts);
    }

    private ChunkUploadSession session(long owner, Instant expires) {
        return new ChunkUploadSession(ID, owner, "large.txt", 21, "a".repeat(32), 4, expires, null);
    }
    private void assertError(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(FileBusinessException.class).extracting("code").isEqualTo(code);
    }
}
