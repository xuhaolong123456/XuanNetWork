package com.networkdisk.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeNodeRequest(@NotBlank @Size(max = 100) String title,
                                   @Size(max = 20000) String content,
                                   @Size(max = 50) String category) { }
