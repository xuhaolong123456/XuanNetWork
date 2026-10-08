package com.networkdisk.file;

import com.networkdisk.auth.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileTrashService {
    private static final int CHUNK_SIZE = 200;
    private final UserFileRepository files;
    private final UserRepository users;
    private final FileService fileService;
    private SearchIndexCoordinator searchIndex;

    public FileTrashService(UserFileRepository files, UserRepository users, FileService fileService) {
        this.files = files;
        this.users = users;
        this.fileService = fileService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setSearchIndex(SearchIndexCoordinator searchIndex) { this.searchIndex = searchIndex; }

    private void markSearchDirty(long ownerId) { if (searchIndex != null) searchIndex.markDirty(ownerId); }

    @Transactional(readOnly = true)
    public TrashListResponse list(long ownerId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new FileBusinessException("INVALID_PARAM", "分页参数无效");
        }
        var result = files.listTrash(ownerId, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("deletedAt"), Sort.Order.desc("id"))));
        return new TrashListResponse(result.getContent(), new FilePageResponse(result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @Transactional
    public void delete(long ownerId, List<Long> ids) {
        List<Long> requested = validateIds(ids);
        lockOwner(ownerId);
        // 全量验证所选资源后才更新，任何一项越权或不存在都使整个批次失败。
        List<TrashNode> roots = requireOwnedNodes(ownerId, requested);
        String batch = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        for (int offset = 0; offset < requested.size(); offset += CHUNK_SIZE) {
            files.markDeleted(ownerId, requested.subList(offset, Math.min(offset + CHUNK_SIZE, requested.size())), now, batch);
        }
        var directories = new ArrayDeque<Long>();
        roots.stream().filter(node -> node.type() == FileNodeType.DIRECTORY)
                .map(TrashNode::id).forEach(directories::add);
        Set<Long> visited = new HashSet<>();
        // 使用主键游标分批遍历，避免深层递归栈溢出；同一事务保障批量删除原子性。
        while (!directories.isEmpty()) {
            long parentId = directories.remove();
            if (!visited.add(parentId)) continue;
            long afterId = 0;
            while (true) {
                List<TrashNode> children = children(ownerId, parentId, afterId);
                if (children.isEmpty()) break;
                files.markDeleted(ownerId, children.stream().map(TrashNode::id).toList(), now, batch);
                children.stream().filter(node -> node.type() == FileNodeType.DIRECTORY)
                        .map(TrashNode::id).forEach(directories::add);
                afterId = children.getLast().id();
            }
        }
        markSearchDirty(ownerId);
    }

    @Transactional
    public void restore(long ownerId, List<Long> ids) {
        List<Long> requested = validateIds(ids);
        lockOwner(ownerId);
        List<TrashNode> roots = requireOwnedNodes(ownerId, requested);
        for (TrashNode root : roots) {
            if (!root.deleted()) continue;
            restoreAncestors(ownerId, root, new HashSet<>());
            restoreNode(ownerId, root);
            if (root.type() != FileNodeType.DIRECTORY) continue;
            var directories = new ArrayDeque<Long>();
            directories.add(root.id());
            Set<Long> visited = new HashSet<>();
            while (!directories.isEmpty()) {
                long parentId = directories.remove();
                if (!visited.add(parentId)) continue;
                long afterId = 0;
                while (true) {
                    List<TrashNode> children = children(ownerId, parentId, afterId);
                    if (children.isEmpty()) break;
                    for (TrashNode child : children) {
                        // 不恢复更早单独删除的子树；已恢复的目录仍需遍历其余同批次后代。
                        if (child.deleted() && !Objects.equals(root.deleteBatch(), child.deleteBatch())) continue;
                        restoreNode(ownerId, child);
                        if (child.type() == FileNodeType.DIRECTORY) directories.add(child.id());
                    }
                    afterId = children.getLast().id();
                }
            }
        }
        markSearchDirty(ownerId);
    }

    private void restoreAncestors(long ownerId, TrashNode node, Set<Long> visited) {
        var ancestors = new ArrayList<TrashNode>();
        TrashNode current = node;
        while (current.parentId() != null) {
            if (!visited.add(current.parentId())) {
                throw new FileBusinessException("INVALID_TREE", "目录结构异常，无法恢复", 409);
            }
            current = requireOwnedNodes(ownerId, List.of(current.parentId())).getFirst();
            if (current.type() != FileNodeType.DIRECTORY) {
                throw new FileBusinessException("INVALID_TREE", "父节点不是文件夹，无法恢复", 409);
            }
            ancestors.add(current);
        }
        // 恢复子文件时补齐可见的祖先目录，避免恢复后文件仍隐藏在已删除目录内。
        for (int index = ancestors.size() - 1; index >= 0; index--) restoreNode(ownerId, ancestors.get(index));
    }

    private void restoreNode(long ownerId, TrashNode node) {
        if (!node.deleted()) return;
        // 批量选择可能同时包含父目录和子节点，重新读取状态避免重复恢复或再次改名。
        node = requireOwnedNodes(ownerId, List.of(node.id())).getFirst();
        if (!node.deleted()) return;
        String name = fileService.handleDuplicateFilename(ownerId, node.parentId(), node.name(), node.type(), node.id());
        files.markRestored(ownerId, node.id(), name, LocalDateTime.now());
    }

    private List<TrashNode> children(long ownerId, long parentId, long afterId) {
        return files.findChildNodes(ownerId, parentId, afterId, PageRequest.of(0, CHUNK_SIZE));
    }

    private List<TrashNode> requireOwnedNodes(long ownerId, List<Long> ids) {
        List<TrashNode> nodes = files.findTrashNodes(ids);
        if (nodes.stream().anyMatch(node -> node.ownerId() != ownerId)) {
            throw new FileBusinessException("FORBIDDEN", "只能操作自己的文件或文件夹", 403);
        }
        if (nodes.size() != ids.size()) {
            throw new FileBusinessException("FILE_NOT_FOUND", "文件或文件夹不存在", 404);
        }
        return nodes;
    }

    private void lockOwner(long ownerId) {
        // 复用用户行锁，和创建、改名保持一致；串行化同用户的目录写入。
        users.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new FileBusinessException("UNAUTHORIZED", "请先登录", 401));
    }

    private static List<Long> validateIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 1000
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new FileBusinessException("INVALID_PARAM", "请选择 1 到 1000 个有效资源");
        }
        return List.copyOf(new LinkedHashSet<>(ids));
    }
}
