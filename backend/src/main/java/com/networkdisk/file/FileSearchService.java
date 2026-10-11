package com.networkdisk.file;

import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.stereotype.Service;

@Service
public class FileSearchService {
    private static final Logger log = LoggerFactory.getLogger(FileSearchService.class);
    private final UserFileRepository files;
    private final ElasticsearchOperations elasticsearch;

    public FileSearchService(UserFileRepository files, ElasticsearchOperations elasticsearch) {
        this.files = files;
        this.elasticsearch = elasticsearch;
    }

    public FileSearchResponse search(long ownerId, String rawKeyword, int page, int size) {
        long searchStartedAt = System.nanoTime();
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.isEmpty() || keyword.length() > 128 || page < 0 || size < 1 || size > 50) {
            throw new FileBusinessException("INVALID_PARAM", "搜索关键词或分页参数无效", 400);
        }
        List<String> terms = splitTerms(keyword);
        log.debug("文件搜索开始 ownerId={} page={} size={} terms={}", ownerId, page, size, terms);
        long indexStartedAt = System.nanoTime();
        try {
            FileSearchResponse response = searchIndex(ownerId, terms, page, size);
            long indexElapsedMs = elapsedMillis(indexStartedAt);
            if (!response.items().isEmpty()) {
                log.info("文件搜索完成 source=elasticsearch ownerId={} page={} size={} termCount={} hitCount={} indexMs={} totalMs={}",
                        ownerId, page, size, terms.size(), response.page().totalElements(), indexElapsedMs,
                        elapsedMillis(searchStartedAt));
                return response;
            }
            log.info("文件搜索回退 source=elasticsearch ownerId={} page={} size={} termCount={} esHitCount={} indexMs={} fallback=mysql reason=empty_page",
                    ownerId, page, size, terms.size(), response.page().totalElements(), indexElapsedMs);
        } catch (RuntimeException exception) {
            log.warn("文件搜索回退 source=elasticsearch ownerId={} page={} size={} termCount={} indexMs={} fallback=mysql reason=error errorType={}",
                    ownerId, page, size, terms.size(), elapsedMillis(indexStartedAt),
                    exception.getClass().getSimpleName());
        }
        long databaseStartedAt = System.nanoTime();
        FileSearchResponse response = searchDatabase(ownerId, terms, page, size);
        log.info("文件搜索完成 source=mysql ownerId={} page={} size={} termCount={} hitCount={} databaseMs={} totalMs={}",
                ownerId, page, size, terms.size(), response.page().totalElements(), elapsedMillis(databaseStartedAt),
                elapsedMillis(searchStartedAt));
        return response;
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private FileSearchResponse searchIndex(long ownerId, List<String> terms, int page, int size) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    // 用户范围必须作为过滤条件，关键词只在该用户的文档中做任一词匹配。
                    var builder = b.filter(f -> f.term(t -> t.field("ownerId").value(FieldValue.of(ownerId))));
                    return builder.must(m -> m.bool(any -> {
                        for (String term : terms) {
                            any.should(s -> s.matchPhrase(match -> match.field("searchableName").query(term)));
                        }
                        return any.minimumShouldMatch("1");
                    }));
                }))
                .withPageable(PageRequest.of(page, size))
                // 先按 ES 相关性分数排序，同分时优先展示最近更新的文件。
                .withSort(sort -> sort.score(score -> score.order(SortOrder.Desc)))
                .withSort(sort -> sort.field(field -> field.field("updatedAt").order(SortOrder.Desc)))
                .build();
        var hits = elasticsearch.search(query, FileSearchDocument.class);
        List<FileSearchResponse.SearchItem> items = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(document -> toItem(document.getId(), document.getName(), FileNodeType.fromStoredValue(document.getType()),
                        document.getSizeBytes(), document.getMimeType(), document.getUpdatedAt(),
                        document.isDownloadAllowed(), document.isPreviewAllowed(), terms))
                .toList();
        return new FileSearchResponse(items, new FilePageResponse(page, size, hits.getTotalHits(),
                (int) Math.ceil((double) hits.getTotalHits() / size)));
    }

    private FileSearchResponse searchDatabase(long ownerId, List<String> terms, int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
        Page<UserFile> result = files.searchActiveByOwnerAndAnyNameTerm(ownerId, terms,
                PageRequest.of(page, size, sort));
        List<FileSearchResponse.SearchItem> items = result.getContent().stream()
                .map(file -> toItem(String.valueOf(file.getId()), file.getName(), file.getNodeType(),
                        file.getSizeBytes(), file.getMimeType(), file.getUpdatedAt(),
                        file.isDownloadAllowed(), file.isPreviewAllowed(), terms))
                .toList();
        return new FileSearchResponse(items, new FilePageResponse(result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    static List<String> splitTerms(String keyword) {
        return Arrays.stream(keyword.trim().split("\\s+"))
                .filter(term -> !term.isBlank())
                .map(term -> term.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static FileSearchResponse.SearchItem toItem(String rawId, String name, FileNodeType type,
            long size, String mimeType, java.time.LocalDateTime updatedAt,
            boolean downloadAllowed, boolean previewAllowed, List<String> terms) {
        return new FileSearchResponse.SearchItem(Long.valueOf(rawId), name, type, size, mimeType,
                updatedAt, downloadAllowed, previewAllowed, highlightHtml(name, terms));
    }

    static String highlightHtml(String name, String keyword) {
        return highlightHtml(name, splitTerms(keyword));
    }

    static String highlightHtml(String name, List<String> terms) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        StringBuilder html = new StringBuilder();
        int cursor = 0;
        while (cursor < name.length()) {
            int match = -1;
            String matchedTerm = null;
            for (String term : terms) {
                int candidate = lowerName.indexOf(term, cursor);
                if (candidate >= 0 && (match < 0 || candidate < match)) {
                    match = candidate;
                    matchedTerm = term;
                }
            }
            if (match < 0) break;
            if (match > cursor) html.append(escapeHtml(name.substring(cursor, match)));
            int end = match + matchedTerm.length();
            html.append("<mark>").append(escapeHtml(name.substring(match, end))).append("</mark>");
            cursor = end;
        }
        if (cursor < name.length()) html.append(escapeHtml(name.substring(cursor)));
        return html.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

}
