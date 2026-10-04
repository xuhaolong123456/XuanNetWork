package com.networkdisk.file;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChunkUploadPartRepository extends JpaRepository<ChunkUploadPart, String> {
    boolean existsByUploadIdAndPartNumber(String uploadId, int partNumber);

    // 分页列出有效分片，供 List-Parts 续传查询；过滤掉逻辑删除和已过期的记录。
    @Query("select p from ChunkUploadPart p where p.uploadId = :id and p.partNumber > :marker "
            + "and p.deleted = false and p.expiresAt > :now order by p.partNumber")
    List<ChunkUploadPart> findPage(@Param("id") String id, @Param("marker") int marker,
            @Param("now") Instant now, Pageable page);

    // 有效分片序号升序列表：只统计未删除且未过期的分片，作为“分片是否齐全”的依据。
    @Query("select p.partNumber from ChunkUploadPart p where p.uploadId = :id "
            + "and p.deleted = false and p.expiresAt > :now order by p.partNumber")
    List<Integer> findActivePartNumbers(@Param("id") String id, @Param("now") Instant now);

    // 待清理的旧分片序号：逻辑删除或已过期的记录，上传新分片前统一回收。
    @Query("select p.partNumber from ChunkUploadPart p where p.uploadId = :id "
            + "and (p.deleted = true or p.expiresAt <= :now)")
    List<Integer> findStalePartNumbers(@Param("id") String id, @Param("now") Instant now);

    // 有效分片数量：与总分片数比较，判断是否全部就位。
    @Query("select count(p) from ChunkUploadPart p where p.uploadId = :id "
            + "and p.deleted = false and p.expiresAt > :now")
    long countActiveParts(@Param("id") String id, @Param("now") Instant now);

    // 物理删除指定分片记录，调用方负责先删除对应的 .part 文件。
    @Modifying
    @Query("delete from ChunkUploadPart p where p.uploadId = :id and p.partNumber in :partNumbers")
    void deleteParts(@Param("id") String id, @Param("partNumbers") List<Integer> partNumbers);

    void deleteByUploadId(String uploadId);
}
