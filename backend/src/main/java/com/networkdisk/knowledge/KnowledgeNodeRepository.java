package com.networkdisk.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeNodeRepository extends JpaRepository<KnowledgeNode, Long> {
    List<KnowledgeNode> findAllByOwnerIdOrderByUpdatedTimeDesc(Long ownerId);
    Optional<KnowledgeNode> findByIdAndOwnerId(Long id, Long ownerId);
}
