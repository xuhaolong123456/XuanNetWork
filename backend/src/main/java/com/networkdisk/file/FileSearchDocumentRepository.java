package com.networkdisk.file;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface FileSearchDocumentRepository extends ElasticsearchRepository<FileSearchDocument, String> {
    long deleteByOwnerId(Long ownerId);
}
