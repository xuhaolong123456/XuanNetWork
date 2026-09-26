package com.networkdisk.file;

public record TrashNode(Long id, Long ownerId, Long parentId, String name,
                        FileNodeType type, boolean deleted, String deleteBatch) {
}
