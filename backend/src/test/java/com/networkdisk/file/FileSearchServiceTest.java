package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Query;

class FileSearchServiceTest {
    @Test
    void usesDatabaseWhileIndexIsNotSynchronizedAndReturnsSafeHighlightParts() {
        UserFileRepository files = mock(UserFileRepository.class);
        SearchIndexCoordinator index = mock(SearchIndexCoordinator.class);
        ElasticsearchOperations elasticsearch = mock(ElasticsearchOperations.class);
        UserFile file = mock(UserFile.class);
        when(index.isCurrent(7L)).thenReturn(false);
        when(file.getId()).thenReturn(12L);
        when(file.getName()).thenReturn("代码随想录-笔记.txt");
        when(file.getNodeType()).thenReturn(FileNodeType.FILE);
        when(file.getSizeBytes()).thenReturn(42L);
        when(file.getMimeType()).thenReturn("text/plain");
        when(file.getUpdatedAt()).thenReturn(LocalDateTime.parse("2026-01-01T12:00:00"));
        when(file.isDownloadAllowed()).thenReturn(true);
        when(file.isPreviewAllowed()).thenReturn(true);
        when(files.findByOwner_IdAndParentIsNullAndDeletedFalseAndNameContainingIgnoreCase(
                eq(7L), eq("随想录"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(file)));

        FileSearchResponse response = new FileSearchService(files, index, elasticsearch)
                .search(7L, "随想录", null, 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().id()).isEqualTo(12L);
        assertThat(response.items().getFirst().highlight())
                .containsExactly(new FileSearchResponse.HighlightSegment("代码", false),
                        new FileSearchResponse.HighlightSegment("随想录", true),
                        new FileSearchResponse.HighlightSegment("-笔记.txt", false));
        verify(elasticsearch, never()).search(any(Query.class), eq(FileSearchDocument.class));
    }
}
