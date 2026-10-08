package com.networkdisk.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "file_search_index_state")
public class SearchIndexState {
    @Id
    private Long ownerId;
    @Column(name = "index_generation", nullable = false)
    private long generation;
    @Column(name = "indexed_generation", nullable = false)
    private long indexedGeneration;

    protected SearchIndexState() { }

    public SearchIndexState(Long ownerId) {
        this.ownerId = ownerId;
        this.generation = 1;
        this.indexedGeneration = 0;
    }

    public Long getOwnerId() { return ownerId; }
    public long getGeneration() { return generation; }
    public long getIndexedGeneration() { return indexedGeneration; }
    public boolean isDirty() { return generation != indexedGeneration; }
    public void markDirty() { generation++; }
}
