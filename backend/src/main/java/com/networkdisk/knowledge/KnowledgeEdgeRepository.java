package com.networkdisk.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeEdgeRepository extends JpaRepository<KnowledgeEdge, Long> {
    List<KnowledgeEdge> findAllBySourceOwnerIdOrderById(Long ownerId);
    Optional<KnowledgeEdge> findByIdAndSourceOwnerId(Long id, Long ownerId);
    boolean existsBySourceIdAndTargetIdAndSourceOwnerId(Long sourceId, Long targetId, Long ownerId);
    void deleteAllBySourceIdOrTargetId(Long sourceId, Long targetId);
}
