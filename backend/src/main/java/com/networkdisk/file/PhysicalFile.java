package com.networkdisk.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/** 物理文件表：同一份内容只保留一份磁盘文件，用户文件记录通过它复用存储内容。 */
@Entity
@Table(name = "physical_file",
        uniqueConstraints = @UniqueConstraint(name = "uk_physical_file_hash_size",
                columnNames = {"file_hash", "file_size"}),
        indexes = @Index(name = "idx_physical_file_hash_size", columnList = "file_hash,file_size"))
public class PhysicalFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "file_md5", length = 32)
    private String fileMd5;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PhysicalFile() {
    }

    public PhysicalFile(String fileHash, long fileSize, String storageKey, String mimeType) {
        this.fileHash = fileHash;
        this.fileSize = fileSize;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getFileHash() { return fileHash; }
    public String getFileMd5() { return fileMd5; }
    public void setFileMd5(String fileMd5) { this.fileMd5 = fileMd5; }
    public long getFileSize() { return fileSize; }
    public String getStorageKey() { return storageKey; }
    public String getMimeType() { return mimeType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
