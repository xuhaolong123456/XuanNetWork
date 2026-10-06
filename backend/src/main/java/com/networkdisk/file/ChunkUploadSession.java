package com.networkdisk.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

/** 会话元数据持久化，分片文件作为已完成状态的依据，重启后仍可续传。 */
@Entity
public class ChunkUploadSession {
    @Id
    private String id;
    private long ownerId;
    private String name;
    private long sizeBytes;
    private String fileMd5;
    private long chunkSize;
    private Instant expiresAt;
    // 目标目录：合并出最终文件后，UserFile 记录要挂到哪个文件夹下（根目录为 null）。
    @Column(name = "parent_folder_id")
    private Long parentFolderId;
    private Long mergedFileId;

    protected ChunkUploadSession() { }

    public ChunkUploadSession(String id, long ownerId, String name, long sizeBytes,
                              String fileMd5, long chunkSize, Instant expiresAt, Long parentFolderId) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.fileMd5 = fileMd5;
        this.chunkSize = chunkSize;
        this.expiresAt = expiresAt;
        this.parentFolderId = parentFolderId;
    }

    public String getId() { return id; }
    public long getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public long getSizeBytes() { return sizeBytes; }
    public String getFileMd5() { return fileMd5; }
    public long getChunkSize() { return chunkSize; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getParentFolderId() { return parentFolderId; }
    public Long getMergedFileId() { return mergedFileId; }
    public void markMerged(long fileId) { this.mergedFileId = fileId; }
    public int getTotalParts() { return Math.toIntExact((sizeBytes - 1) / chunkSize + 1); }
}
