package com.networkdisk.file;

import com.networkdisk.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_file", indexes = {
        @Index(name = "idx_user_file_owner_parent_id", columnList = "user_id,parent_id,id")
})
public class UserFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private UserFile parent;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 16)
    private FileNodeType nodeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UserFile() {
    }

    public UserFile(User owner, UserFile parent, String name, FileNodeType nodeType) {
        this(owner, parent, name, nodeType, 0, null);
    }

    public UserFile(User owner, UserFile parent, String name, FileNodeType nodeType,
                    long sizeBytes, String mimeType) {
        this.owner = owner;
        this.parent = parent;
        this.name = name;
        this.nodeType = nodeType;
        this.sizeBytes = sizeBytes;
        this.mimeType = mimeType;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PrePersist
    void beforeInsert() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getOwner() { return owner; }
    public UserFile getParent() { return parent; }
    public String getName() { return name; }
    public FileNodeType getNodeType() { return nodeType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getMimeType() { return mimeType; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
