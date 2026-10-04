package com.networkdisk.file;

import java.util.ArrayList;
import java.util.List;

public record FolderTreeNodeResponse(Long id, Long parentId, String name,
                                     List<FolderTreeNodeResponse> children) {
    public FolderTreeNodeResponse(Long id, Long parentId, String name) {
        this(id, parentId, name, new ArrayList<>());
    }
}
