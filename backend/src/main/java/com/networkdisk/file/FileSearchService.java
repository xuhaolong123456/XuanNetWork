package com.networkdisk.file;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import co.elastic.clients.elasticsearch._types.FieldValue;
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
    private final SearchIndexCoordinator index;
    private final ElasticsearchOperations elasticsearch;

    public FileSearchService(UserFileRepository files, SearchIndexCoordinator index,
                             ElasticsearchOperations elasticsearch) {
        this.files = files;
        this.index = index;
        this.elasticsearch = elasticsearch;
    }

    public FileSearchResponse search(long ownerId, String rawKeyword, Long parentId, int page, int size) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.isEmpty() || keyword.length() > 128 || page < 0 || size < 1 || size > 50
                || (parentId != null && parentId <= 0)) {
            throw new FileBusinessException("INVALID_PARAM", "搜索关键词或分页参数无效", 400);
        }
        if (parentId != null) {
            files.findByIdAndOwner_Id(parentId, ownerId)
                    .filter(node -> node.getNodeType() == FileNodeType.DIRECTORY)
                    .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "文件夹不存在", 404));
        }
        try {
            if (index.isCurrent(ownerId)) {
                FileSearchResponse response = searchIndex(ownerId, keyword, parentId, page, size);
                if (!response.items().isEmpty()) return response;
            }
        } catch (RuntimeException exception) {
            log.debug("文件搜索索引不可用，回退数据库", exception);
        }
        return searchDatabase(ownerId, keyword, parentId, page, size);
    }

    private FileSearchResponse searchIndex(long ownerId, String keyword, Long parentId, int page, int size) {
        String normalized = escapeWildcard(keyword.toLowerCase(Locale.ROOT));
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    var builder = b.filter(f -> f.term(t -> t.field("ownerId").value(FieldValue.of(ownerId))));
                    if (parentId == null) builder = builder.mustNot(m -> m.exists(e -> e.field("parentId")));
                    else builder = builder.filter(f -> f.term(t -> t.field("parentId").value(FieldValue.of(parentId))));
                    return builder.must(m -> m.wildcard(w -> w.field("normalizedName").value("*" + normalized + "*")));
                }))
                .withPageable(PageRequest.of(page, size))
                .build();
        var hits = elasticsearch.search(query, FileSearchDocument.class);
        List<FileSearchResponse.SearchItem> items = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(document -> toItem(document.getId(), document.getName(), FileNodeType.valueOf(document.getType()),
                        document.getSizeBytes(), document.getMimeType(), document.getUpdatedAt(),
                        document.isDownloadAllowed(), document.isPreviewAllowed(), keyword))
                .toList();
        return new FileSearchResponse(items, new FilePageResponse(page, size, hits.getTotalHits(),
                (int) Math.ceil((double) hits.getTotalHits() / size)));
    }

    private FileSearchResponse searchDatabase(long ownerId, String keyword, Long parentId, int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
        Page<UserFile> result = parentId == null
                ? files.findByOwner_IdAndParentIsNullAndDeletedFalseAndNameContainingIgnoreCase(
                        ownerId, keyword, PageRequest.of(page, size, sort))
                : files.findByOwner_IdAndParent_IdAndDeletedFalseAndNameContainingIgnoreCase(
                        ownerId, parentId, keyword, PageRequest.of(page, size, sort));
        List<FileSearchResponse.SearchItem> items = result.getContent().stream()
                .map(file -> toItem(String.valueOf(file.getId()), file.getName(), file.getNodeType(),
                        file.getSizeBytes(), file.getMimeType(), file.getUpdatedAt(),
                        file.isDownloadAllowed(), file.isPreviewAllowed(), keyword))
                .toList();
        return new FileSearchResponse(items, new FilePageResponse(result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    private static FileSearchResponse.SearchItem toItem(String rawId, String name, FileNodeType type,
            long size, String mimeType, java.time.LocalDateTime updatedAt,
            boolean downloadAllowed, boolean previewAllowed, String keyword) {
        return new FileSearchResponse.SearchItem(Long.valueOf(rawId), name, type, size, mimeType,
                updatedAt, downloadAllowed, previewAllowed, highlight(name, keyword));
    }

    private static List<FileSearchResponse.HighlightSegment> highlight(String name, String keyword) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        List<FileSearchResponse.HighlightSegment> segments = new ArrayList<>();
        int cursor = 0;
        int match = lowerName.indexOf(lowerKeyword, cursor);
        while (match >= 0) {
            if (match > cursor) segments.add(new FileSearchResponse.HighlightSegment(name.substring(cursor, match), false));
            int end = match + lowerKeyword.length();
            segments.add(new FileSearchResponse.HighlightSegment(name.substring(match, end), true));
            cursor = end;
            match = lowerName.indexOf(lowerKeyword, cursor);
        }
        if (cursor < name.length()) segments.add(new FileSearchResponse.HighlightSegment(name.substring(cursor), false));
        if (segments.isEmpty()) segments.add(new FileSearchResponse.HighlightSegment(name, false));
        return List.copyOf(segments);
    }

    private static String escapeWildcard(String keyword) {
        return keyword.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
