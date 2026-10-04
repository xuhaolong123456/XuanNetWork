package com.networkdisk.knowledge;

import com.networkdisk.auth.UserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class KnowledgeService {
    private final KnowledgeNodeRepository nodes;
    private final KnowledgeEdgeRepository edges;
    private final UserRepository users;

    public KnowledgeService(KnowledgeNodeRepository nodes, KnowledgeEdgeRepository edges,
            UserRepository users) {
        this.nodes = nodes;
        this.edges = edges;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeNodeResponse> listNodes(Long userId) {
        return nodes.findAllByOwnerIdOrderByUpdatedTimeDesc(userId).stream()
                .map(KnowledgeNodeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeEdgeResponse> listEdges(Long userId) {
        return edges.findAllBySourceOwnerIdOrderById(userId).stream()
                .map(KnowledgeEdgeResponse::from).toList();
    }

    public KnowledgeNodeResponse createNode(Long userId, KnowledgeNodeRequest request) {
        var node = new KnowledgeNode(users.getReferenceById(userId), request.title().trim(),
                clean(request.content()), clean(request.category()));
        return KnowledgeNodeResponse.from(nodes.save(node));
    }

    public KnowledgeNodeResponse updateNode(Long userId, Long id, KnowledgeNodeRequest request) {
        var node = findNode(userId, id);
        node.update(request.title().trim(), clean(request.content()), clean(request.category()));
        return KnowledgeNodeResponse.from(node);
    }

    public void deleteNode(Long userId, Long id) {
        findNode(userId, id);
        // 先删除关联，避免其他用户构造的跨归属关系残留。
        edges.deleteAllBySourceIdOrTargetId(id, id);
        nodes.deleteById(id);
    }

    public KnowledgeEdgeResponse createEdge(Long userId, KnowledgeEdgeRequest request) {
        if (request.sourceId().equals(request.targetId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识点不能关联自身");
        }
        var source = findNode(userId, request.sourceId());
        var target = findNode(userId, request.targetId());
        if (edges.existsBySourceIdAndTargetIdAndSourceOwnerId(source.getId(), target.getId(), userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该关系已存在");
        }
        return KnowledgeEdgeResponse.from(edges.save(new KnowledgeEdge(source, target, clean(request.relation()))));
    }

    public void deleteEdge(Long userId, Long id) {
        var edge = edges.findByIdAndSourceOwnerId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        edges.delete(edge);
    }

    private KnowledgeNode findNode(Long userId, Long id) {
        // 所有节点查询都带登录用户 ID，避免通过猜测资源 ID 越权访问。
        return nodes.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
