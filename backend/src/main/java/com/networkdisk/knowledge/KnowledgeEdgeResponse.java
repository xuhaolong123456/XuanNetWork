package com.networkdisk.knowledge;

public record KnowledgeEdgeResponse(Long id, Long source, Long target, String relation) {
    static KnowledgeEdgeResponse from(KnowledgeEdge edge) {
        return new KnowledgeEdgeResponse(edge.getId(), edge.getSource().getId(),
                edge.getTarget().getId(), edge.getRelation());
    }
}
