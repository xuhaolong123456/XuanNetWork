package com.networkdisk.knowledge;

import com.networkdisk.common.Result;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) { this.knowledge = knowledge; }

    @GetMapping("/nodes")
    public Result<List<KnowledgeNodeResponse>> nodes(@AuthenticationPrincipal Long userId) {
        return Result.success(knowledge.listNodes(userId));
    }

    @PostMapping("/node")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<KnowledgeNodeResponse> createNode(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody KnowledgeNodeRequest request) {
        return Result.success(knowledge.createNode(userId, request));
    }

    @PutMapping("/node/{id}")
    public Result<KnowledgeNodeResponse> updateNode(@AuthenticationPrincipal Long userId,
            @PathVariable Long id, @Valid @RequestBody KnowledgeNodeRequest request) {
        return Result.success(knowledge.updateNode(userId, id, request));
    }

    @DeleteMapping("/node/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNode(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        knowledge.deleteNode(userId, id);
    }

    @GetMapping("/edges")
    public Result<List<KnowledgeEdgeResponse>> edges(@AuthenticationPrincipal Long userId) {
        return Result.success(knowledge.listEdges(userId));
    }

    @PostMapping("/edge")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<KnowledgeEdgeResponse> createEdge(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody KnowledgeEdgeRequest request) {
        return Result.success(knowledge.createEdge(userId, request));
    }

    @DeleteMapping("/edge/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEdge(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        knowledge.deleteEdge(userId, id);
    }
}
