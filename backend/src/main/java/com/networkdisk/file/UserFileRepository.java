package com.networkdisk.file;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFileRepository extends JpaRepository<UserFile, Long> {
    Page<UserFile> findByOwner_IdAndParentIsNull(Long ownerId, Pageable pageable);

    Page<UserFile> findByOwner_IdAndParent_Id(Long ownerId, Long parentId, Pageable pageable);

    Optional<UserFile> findByIdAndOwner_Id(Long id, Long ownerId);

    boolean existsByOwner_IdAndParentIsNullAndName(Long ownerId, String name);
}
