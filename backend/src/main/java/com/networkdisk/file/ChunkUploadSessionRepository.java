package com.networkdisk.file;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChunkUploadSessionRepository extends JpaRepository<ChunkUploadSession, String> {
    // 接收与清理锁定同一会话，防止重复落盘以及清理期间写入。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChunkUploadSession s where s.id = :id")
    Optional<ChunkUploadSession> findForUpdate(@Param("id") String id);

    @Query("select s.id from ChunkUploadSession s where s.expiresAt <= :now")
    List<String> findExpiredIds(@Param("now") Instant now);
}
