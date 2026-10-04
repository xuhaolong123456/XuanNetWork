package com.networkdisk.knowledge;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record KnowledgeEdgeRequest(@NotNull Long sourceId, @NotNull Long targetId,
                                   @Size(max = 50) String relation) { }
