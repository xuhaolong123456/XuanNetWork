package com.networkdisk.file;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "networkdisk-files-v2", createIndex = false)
public class FileSearchDocument {
    @Id
    private String id;
    @Field(type = FieldType.Long) private Long ownerId;
    @Field(type = FieldType.Long) private Long parentId;
    @Field(type = FieldType.Keyword) private String name;
    @Field(type = FieldType.Keyword) private String normalizedName;
    // 使用 ES 内置 CJK 分析器生成中文二元词项，避免依赖额外插件。
    @Field(type = FieldType.Text, analyzer = "cjk", searchAnalyzer = "cjk")
    private String searchableName;
    @Field(type = FieldType.Keyword) private String type;
    @Field(type = FieldType.Long) private long sizeBytes;
    @Field(type = FieldType.Keyword) private String mimeType;
    @Field(type = FieldType.Date) private LocalDateTime updatedAt;
    @Field(type = FieldType.Boolean) private boolean downloadAllowed;
    @Field(type = FieldType.Boolean) private boolean previewAllowed;

    protected FileSearchDocument() { }

    public FileSearchDocument(UserFile file) {
        this.id = String.valueOf(file.getId());
        this.ownerId = file.getOwner().getId();
        this.parentId = file.getParent() == null ? null : file.getParent().getId();
        this.name = file.getName();
        this.normalizedName = file.getName().toLowerCase(java.util.Locale.ROOT);
        this.searchableName = file.getName();
        this.type = file.getNodeType().name();
        this.sizeBytes = file.getSizeBytes();
        this.mimeType = file.getMimeType();
        this.updatedAt = file.getUpdatedAt();
        this.downloadAllowed = file.isDownloadAllowed();
        this.previewAllowed = file.isPreviewAllowed();
    }

    public FileSearchDocument(long id, long ownerId, Long parentId, String name, String type,
            long sizeBytes, String mimeType, LocalDateTime updatedAt,
            boolean downloadAllowed, boolean previewAllowed) {
        this.id = String.valueOf(id);
        this.ownerId = ownerId;
        this.parentId = parentId;
        this.name = name;
        this.normalizedName = name.toLowerCase(java.util.Locale.ROOT);
        this.searchableName = name;
        this.type = type;
        this.sizeBytes = sizeBytes;
        this.mimeType = mimeType;
        this.updatedAt = updatedAt;
        this.downloadAllowed = downloadAllowed;
        this.previewAllowed = previewAllowed;
    }

    public String getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public Long getParentId() { return parentId; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public String getSearchableName() { return searchableName; }
    public String getType() { return type; }
    public long getSizeBytes() { return sizeBytes; }
    public String getMimeType() { return mimeType; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public boolean isDownloadAllowed() { return downloadAllowed; }
    public boolean isPreviewAllowed() { return previewAllowed; }
}
