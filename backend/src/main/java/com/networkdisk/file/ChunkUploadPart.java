package com.networkdisk.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** 仅在分片完整落盘后发布元数据，查询接口只读取此表。 */
@Entity
@Table(name = "chunk_upload_part",
        uniqueConstraints = @UniqueConstraint(columnNames = {"upload_id", "part_number"}),
        indexes = @Index(name = "idx_chunk_part_cursor", columnList = "upload_id,part_number"))
public class ChunkUploadPart {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "upload_id", nullable = false, length = 43)
    private String uploadId;
    @Column(name = "part_number", nullable = false)
    private int partNumber;
    @Column(nullable = false)
    private long size;
    @Column(nullable = false, length = 32)
    private String etag;
    // 逻辑删除标记：分片被废弃或会话重建后置 true，不参与“分片是否齐全”的判断。
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean deleted;
    // 分片过期时间，与所属会话一致；上传新分片前会清理已过期的旧记录。
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ChunkUploadPart() { }

    public ChunkUploadPart(String uploadId, int partNumber, long size, Instant expiresAt) {
        this.id = uploadId + ":" + partNumber;
        this.uploadId = uploadId;
        this.partNumber = partNumber;
        this.size = size;
        this.etag = "";
        this.deleted = false;
        this.expiresAt = expiresAt;
    }

    public String getUploadId() { return uploadId; }
    public int getPartNumber() { return partNumber; }
    public long getSize() { return size; }
    public String getEtag() { return etag; }
    public boolean isDeleted() { return deleted; }
    public Instant getExpiresAt() { return expiresAt; }
}
