package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

class FileSearchServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void returnsElasticsearchHitWithoutDatabaseFallbackForNumericNodeType() {
        UserFileRepository files = mock(UserFileRepository.class);
        ElasticsearchOperations elasticsearch = mock(ElasticsearchOperations.class);
        SearchHits<FileSearchDocument> hits = mock(SearchHits.class);
        SearchHit<FileSearchDocument> hit = mock(SearchHit.class);
        FileSearchDocument document = new FileSearchDocument(31L, 7L, null, "报告.pdf", "2",
                42L, "application/pdf", LocalDateTime.parse("2026-10-11T12:00:00"), true, true);
        when(hit.getContent()).thenReturn(document);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(hits.getTotalHits()).thenReturn(1L);
        when(elasticsearch.search(any(Query.class), eq(FileSearchDocument.class))).thenReturn(hits);

        FileSearchResponse response = new FileSearchService(files, elasticsearch)
                .search(7L, "报告", 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().type()).isEqualTo(FileNodeType.FILE);
        assertThat(response.items().getFirst().id()).isEqualTo(31L);
        verifyNoInteractions(files);
    }

    @Test
    void fallsBackToDatabaseWhenIndexHasNoUsableResultsAndReturnsEscapedHighlightHtml() {
        UserFileRepository files = mock(UserFileRepository.class);
        ElasticsearchOperations elasticsearch = mock(ElasticsearchOperations.class);
        UserFile file = mock(UserFile.class);
        when(file.getId()).thenReturn(12L);
        when(file.getName()).thenReturn("代码随想录-笔记.txt");
        when(file.getNodeType()).thenReturn(FileNodeType.FILE);
        when(file.getSizeBytes()).thenReturn(42L);
        when(file.getMimeType()).thenReturn("text/plain");
        when(file.getUpdatedAt()).thenReturn(LocalDateTime.parse("2026-01-01T12:00:00"));
        when(file.isDownloadAllowed()).thenReturn(true);
        when(file.isPreviewAllowed()).thenReturn(true);
        when(elasticsearch.search(any(Query.class), eq(FileSearchDocument.class)))
                .thenThrow(new IllegalStateException("ES不可用"));
        when(files.searchActiveByOwnerAndAnyNameTerm(eq(7L), eq(List.of("随想录")), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(file)));

        FileSearchResponse response = new FileSearchService(files, elasticsearch)
                .search(7L, "随想录", 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().id()).isEqualTo(12L);
        assertThat(response.items().getFirst().highlightHtml()).isEqualTo("代码<mark>随想录</mark>-笔记.txt");
        verify(files).searchActiveByOwnerAndAnyNameTerm(eq(7L), eq(List.of("随想录")), any(PageRequest.class));
    }

    @Test
    void escapesFilenameHtmlBeforeReturningHighlightHtml() {
        assertThat(FileSearchService.highlightHtml("<随想录 onmouseover='x'>.txt", "随想录"))
                .isEqualTo("&lt;<mark>随想录</mark> onmouseover=&#39;x&#39;&gt;.txt");
    }

    @Test
    void fallsBackToMysqlWhenElasticsearchReturnsNoResults() {
        UserFileRepository files = mock(UserFileRepository.class);
        ElasticsearchOperations elasticsearch = mock(ElasticsearchOperations.class);
        when(elasticsearch.search(any(Query.class), eq(FileSearchDocument.class)))
                .thenThrow(new IllegalStateException("ES不可用"));
        when(files.searchActiveByOwnerAndAnyNameTerm(eq(7L), eq(List.of("随想录")), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        new FileSearchService(files, elasticsearch).search(7L, "随想录", 0, 20);

        verify(elasticsearch, times(1)).search(any(Query.class), eq(FileSearchDocument.class));
        verify(files).searchActiveByOwnerAndAnyNameTerm(eq(7L), eq(List.of("随想录")), any(PageRequest.class));
    }

    @Test
    void splitsSearchTermsAndHighlightsAnyMatchingTerm() {
        assertThat(FileSearchService.splitTerms("  项目  PLAN  项目 "))
                .containsExactly("项目", "plan");
        assertThat(FileSearchService.highlightHtml("项目计划.md", List.of("plan", "计划")))
                .isEqualTo("项目<mark>计划</mark>.md");
    }

    @Test
    void ordersElasticsearchResultsByScoreThenUpdateTime() {
        UserFileRepository files = mock(UserFileRepository.class);
        ElasticsearchOperations elasticsearch = mock(ElasticsearchOperations.class);
        var queryCaptor = forClass(Query.class);
        when(elasticsearch.search(queryCaptor.capture(), eq(FileSearchDocument.class)))
                .thenThrow(new IllegalStateException("触发数据库回退"));
        when(files.searchActiveByOwnerAndAnyNameTerm(eq(7L), eq(List.of("报告")), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        new FileSearchService(files, elasticsearch).search(7L, "报告", 0, 20);

        NativeQuery query = (NativeQuery) queryCaptor.getValue();
        assertThat(query.getSortOptions()).hasSize(2);
        assertThat(query.getSortOptions().getFirst().isScore()).isTrue();
        assertThat(query.getSortOptions().getFirst().score().order().jsonValue()).isEqualTo("desc");
        assertThat(query.getSortOptions().get(1).field().field()).isEqualTo("updatedAt");
    }
}
