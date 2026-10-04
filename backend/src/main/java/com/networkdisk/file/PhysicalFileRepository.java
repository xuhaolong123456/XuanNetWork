package com.networkdisk.file;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhysicalFileRepository extends JpaRepository<PhysicalFile, Long> {
    Optional<PhysicalFile> findByFileHashAndFileSize(String fileHash, long fileSize);
    Optional<PhysicalFile> findFirstByFileMd5AndFileSize(String fileMd5, long fileSize);
}
