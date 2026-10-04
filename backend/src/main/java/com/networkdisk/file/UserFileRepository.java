package com.networkdisk.file;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFileRepository extends JpaRepository<UserFile, Long> {
    @Query("select f from UserFile f where f.owner.id = :ownerId and f.parent is null and f.deleted = false")
    Page<UserFile> findByOwner_IdAndParentIsNull(@Param("ownerId") Long ownerId, Pageable pageable);

    @Query("select f from UserFile f where f.owner.id = :ownerId and f.parent.id = :parentId and f.deleted = false")
    Page<UserFile> findByOwner_IdAndParent_Id(@Param("ownerId") Long ownerId, @Param("parentId") Long parentId, Pageable pageable);

    @Query("select f from UserFile f where f.id = :id and f.owner.id = :ownerId and f.deleted = false")
    Optional<UserFile> findByIdAndOwner_Id(@Param("id") Long id, @Param("ownerId") Long ownerId);

    @Query("select f from UserFile f where f.owner.id = :ownerId and f.name = :name and f.deleted = false and f.nodeType = com.networkdisk.file.FileNodeType.FILE")
    List<UserFile> findActiveFilesByOwnerAndName(@Param("ownerId") long ownerId, @Param("name") String name);

    @Query("select new com.networkdisk.file.FolderTreeRow(f.id, p.id, f.name) "
            + "from UserFile f left join f.parent p "
            + "where f.owner.id = :ownerId and f.deleted = false "
            + "and f.nodeType = com.networkdisk.file.FileNodeType.DIRECTORY order by f.name, f.id")
    List<FolderTreeRow> findActiveDirectoryRows(@Param("ownerId") long ownerId);

    // 子目录创建时锁住目标目录，确保同一目录的重名处理串行执行。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select file from UserFile file where file.id = :id and file.owner.id = :ownerId and file.deleted = false")
    Optional<UserFile> findByIdAndOwner_IdForUpdate(@Param("id") Long id, @Param("ownerId") Long ownerId);

    @Query("select (count(f) > 0) from UserFile f where f.owner.id = :ownerId and f.parent is null and f.name = :name and f.deleted = false")
    boolean existsByOwner_IdAndParentIsNullAndName(@Param("ownerId") Long ownerId, @Param("name") String name);

    @Query("select (count(f) > 0) from UserFile f where f.owner.id = :ownerId and f.parent.id = :parentId and f.name = :name and f.deleted = false")
    boolean existsByOwner_IdAndParent_IdAndName(@Param("ownerId") Long ownerId, @Param("parentId") Long parentId, @Param("name") String name);

    @Query("select (count(f) > 0) from UserFile f where f.owner.id = :ownerId and f.parent is null and f.name = :name and f.id <> :excludedId and f.deleted = false")
    boolean existsByOwner_IdAndParentIsNullAndNameAndIdNot(@Param("ownerId") Long ownerId, @Param("name") String name, @Param("excludedId") Long excludedId);

    @Query("select (count(f) > 0) from UserFile f where f.owner.id = :ownerId and f.parent.id = :parentId and f.name = :name and f.id <> :excludedId and f.deleted = false")
    boolean existsByOwner_IdAndParent_IdAndNameAndIdNot(@Param("ownerId") Long ownerId, @Param("parentId") Long parentId, @Param("name") String name, @Param("excludedId") Long excludedId);

    @Query("select (count(f) > 0) from UserFile f where f.owner.id = :ownerId and f.parent.id = :parentId and f.deleted = false")
    boolean existsByOwner_IdAndParent_Id(@Param("ownerId") Long ownerId, @Param("parentId") Long parentId);

    // 回收站专用查询允许读取已删除节点，必须由业务层先校验归属。
    @Query("select new com.networkdisk.file.TrashNode(f.id, f.owner.id, p.id, f.name, f.nodeType, f.deleted, f.deleteBatch) from UserFile f left join f.parent p where f.id in :ids")
    List<TrashNode> findTrashNodes(@Param("ids") List<Long> ids);

    @Query("select new com.networkdisk.file.TrashNode(f.id, f.owner.id, p.id, f.name, f.nodeType, f.deleted, f.deleteBatch) from UserFile f join f.parent p where f.owner.id = :ownerId and p.id = :parentId and f.id > :afterId order by f.id")
    List<TrashNode> findChildNodes(@Param("ownerId") long ownerId, @Param("parentId") long parentId,
                                 @Param("afterId") long afterId, Pageable pageable);

    @Query(value = "select new com.networkdisk.file.TrashItemResponse(f.id, f.name, f.nodeType, f.sizeBytes, f.deletedAt, p.id, coalesce(p.name, '我的文件')) from UserFile f left join f.parent p where f.owner.id = :ownerId and f.deleted = true",
           countQuery = "select count(f) from UserFile f where f.owner.id = :ownerId and f.deleted = true")
    Page<TrashItemResponse> listTrash(@Param("ownerId") long ownerId, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserFile f set f.deleted = true, f.deletedAt = :now, f.deleteBatch = :batch, f.updatedAt = :now where f.owner.id = :ownerId and f.id in :ids and f.deleted = false")
    int markDeleted(@Param("ownerId") long ownerId, @Param("ids") List<Long> ids,
                    @Param("now") LocalDateTime now, @Param("batch") String batch);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserFile f set f.deleted = false, f.deletedAt = null, f.deleteBatch = null, f.name = :name, f.updatedAt = :now where f.owner.id = :ownerId and f.id = :id and f.deleted = true")
    int markRestored(@Param("ownerId") long ownerId, @Param("id") long id,
                     @Param("name") String name, @Param("now") LocalDateTime now);
}
