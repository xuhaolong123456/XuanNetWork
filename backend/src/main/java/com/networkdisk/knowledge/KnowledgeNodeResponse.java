package com.networkdisk.knowledge;

import java.time.LocalDateTime;

public record KnowledgeNodeResponse(Long id, String title, String content, String category,
                                    LocalDateTime createdTime, LocalDateTime updatedTime) {
    static KnowledgeNodeResponse from(KnowledgeNode node) {
        return new KnowledgeNodeResponse(node.getId(), node.getTitle(), node.getContent(),
                node.getCategory(), node.getCreatedTime(), node.getUpdatedTime());
    }
}
