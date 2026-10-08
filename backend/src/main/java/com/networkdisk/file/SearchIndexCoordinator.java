package com.networkdisk.file;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchIndexCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SearchIndexCoordinator.class);
    private static final int PAGE_SIZE = 500;
    private final SearchIndexStateRepository states;
    private final UserFileRepository files;
    private final FileSearchDocumentRepository documents;
    private final ElasticsearchOperations elasticsearch;

    public SearchIndexCoordinator(SearchIndexStateRepository states, UserFileRepository files,
                                  FileSearchDocumentRepository documents, ElasticsearchOperations elasticsearch) {
        this.states = states;
        this.files = files;
        this.documents = documents;
        this.elasticsearch = elasticsearch;
    }

    @Transactional
    public void markDirty(long ownerId) {
        SearchIndexState state = states.findById(ownerId).orElse(null);
        if (state == null) states.save(new SearchIndexState(ownerId));
        else {
            state.markDirty();
            states.save(state);
        }
    }

    @Transactional
    public boolean isCurrent(long ownerId) {
        SearchIndexState state = states.findById(ownerId).orElseGet(() -> states.save(new SearchIndexState(ownerId)));
        return !state.isDirty();
    }

    @Scheduled(fixedDelayString = "${app.search.index-refresh-delay-ms:10000}")
    public void refreshDirtyUsers() {
        for (SearchIndexState state : states.findDirty(PageRequest.of(0, 50))) {
            try {
                rebuild(state.getOwnerId(), state.getGeneration());
            } catch (RuntimeException exception) {
                log.warn("文件搜索索引更新失败，用户 {} 将继续使用数据库搜索", state.getOwnerId(), exception);
            }
        }
    }

    private void rebuild(long ownerId, long generation) {
        var indexOperations = elasticsearch.indexOps(FileSearchDocument.class);
        if (!indexOperations.exists()) indexOperations.createWithMapping();
        documents.deleteByOwnerId(ownerId);
        int pageNumber = 0;
        Page<UserFile> page;
        do {
            page = files.findByOwner_IdAndDeletedFalse(ownerId, PageRequest.of(pageNumber++, PAGE_SIZE));
            List<FileSearchDocument> batch = new ArrayList<>(page.getNumberOfElements());
            for (UserFile file : page.getContent()) batch.add(new FileSearchDocument(file));
            if (!batch.isEmpty()) documents.saveAll(batch);
        } while (page.hasNext());
        indexOperations.refresh();
        states.markIndexedIfCurrent(ownerId, generation);
    }
}
