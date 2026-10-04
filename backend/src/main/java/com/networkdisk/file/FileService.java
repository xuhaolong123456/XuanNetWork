package com.networkdisk.file;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {
    private static final int MAX_PAGE_SIZE = 100;
    private final UserFileRepository files;
    private final UserRepository users;
    private final FileStorageService storage;
    private final PhysicalFileRepository physicalFiles;
    private ChunkUploadService chunks;

    @org.springframework.beans.factory.annotation.Autowired
    public void setChunks(ChunkUploadService chunks) { this.chunks = chunks; }

    public FileService(UserFileRepository files, UserRepository users, FileStorageService storage) {
        this(files, users, storage, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FileService(UserFileRepository files, UserRepository users, FileStorageService storage,
                       PhysicalFileRepository physicalFiles) {
        this.files = files;
        this.users = users;
        this.storage = storage;
        this.physicalFiles = physicalFiles;
    }

    @Transactional(readOnly = true)
    public FileListResponse list(long ownerId, Long parentId, int page, int size) {
        // 限制单页最大条数，避免不受控的查询一次读取过多文件记录。
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new FileBusinessException("INVALID_PARAM", "page must be non-negative and size must be between 1 and 100");
        }
        UserFile directory = null;
        if (parentId != null) {
            directory = requireDirectory(ownerId, parentId);
        }

        PageRequest request = PageRequest.of(page, size, Sort.by(
                Sort.Order.asc("nodeType"), Sort.Order.asc("name"), Sort.Order.asc("id")));
        // 根目录与子目录分别查询，且始终把当前用户 ID 纳入查询条件。
        Page<UserFile> result = parentId == null
                ? files.findByOwner_IdAndParentIsNull(ownerId, request)
                : files.findByOwner_IdAndParent_Id(ownerId, parentId, request);
        List<FileBreadcrumb> breadcrumbs = breadcrumbs(directory);
        FileDirectoryResponse current = directory == null ? null : new FileDirectoryResponse(
                directory.getId(), directory.getName(), directory.getParent() == null ? null : directory.getParent().getId());
        List<FileItemResponse> items = result.getContent().stream().map(file -> new FileItemResponse(
                file.getId(), file.getName(), file.getNodeType(), file.getSizeBytes(),
                file.getMimeType(), file.getUpdatedAt(), file.isDownloadAllowed(), file.isPreviewAllowed())).toList();
        return new FileListResponse(current, breadcrumbs, items,
                new FilePageResponse(result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @Transactional
    public FileItemResponse createDirectory(long ownerId, Long parentId, String rawName) {
        String name = normalizeDirectoryName(rawName);
        // 所有目录写入按用户先加锁，和删除、恢复保持一致的锁顺序。
        User owner = users.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "User not found", 404));
        UserFile parent;
        if (parentId == null) {
            // 根目录没有父目录行可锁定，因此用用户行串行化同一用户的根目录创建。
            parent = null;
        } else {
            parent = requireDirectoryForUpdate(ownerId, parentId);
        }
        String uniqueName = handleDuplicateFilename(ownerId, parentId, name, FileNodeType.DIRECTORY, null);
        return toItem(files.save(new UserFile(owner, parent, uniqueName, FileNodeType.DIRECTORY)));
    }

    @Transactional
    public FileItemResponse renameDirectory(long ownerId, long directoryId, String rawName) {
        String name = normalizeDirectoryName(rawName);
        users.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "User not found", 404));
        UserFile directory = requireDirectoryForUpdate(ownerId, directoryId);
        Long parentId = directory.getParent() == null ? null : directory.getParent().getId();
        if (parentId != null) {
            requireDirectoryForUpdate(ownerId, parentId);
        }

        if (directory.getName().equals(name)) return toItem(directory);
        if (nameExists(ownerId, parentId, name, directoryId)) {
            throw new FileBusinessException("NAME_CONFLICT", "同一目录中已存在相同名称", 409);
        }
        directory.rename(name);
        return toItem(directory);
    }

    @Transactional
    public List<FileItemResponse> move(long ownerId, List<Long> ids, Long targetParentId) {
        if (ids == null || ids.isEmpty() || targetParentId == null || targetParentId <= 0) {
            throw new FileBusinessException("INVALID_PARAM", "移动参数错误", 400);
        }
        List<Long> uniqueIds = ids.stream().distinct().toList();
        // 先锁用户行，保证移动与新建、重命名、回收站操作使用一致的并发控制顺序。
        users.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "用户不存在", 404));
        UserFile target = requireNodeForUpdate(ownerId, targetParentId);
        if (target.getNodeType() != FileNodeType.DIRECTORY) {
            throw new FileBusinessException("INVALID_PARAM", "目标节点必须是文件夹", 400);
        }

        List<FileItemResponse> moved = new ArrayList<>();
        for (Long id : uniqueIds) {
            UserFile node = requireNodeForUpdate(ownerId, id);
            if (node.getNodeType() == FileNodeType.DIRECTORY) {
                // 目录不能移动到自身或后代目录，避免破坏父子树结构。
                if (node.getId().equals(target.getId())) {
                    throw new FileBusinessException("INVALID_PARAM", "目标不能是待移动的文件夹本身", 400);
                }
                for (UserFile ancestor = target.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                    if (ancestor.getId().equals(node.getId())) {
                        throw new FileBusinessException("INVALID_PARAM", "目标不能是待移动文件夹的子文件夹", 400);
                    }
                }
            }
            UserFile parent = node.getParent();
            if (parent != null && target.getId().equals(parent.getId())) {
                moved.add(toItem(node));
                continue;
            }
            String name = handleDuplicateFilename(ownerId, target.getId(), node.getName(), node.getNodeType(), node.getId());
            if (!name.equals(node.getName())) node.rename(name);
            node.moveTo(target);
            moved.add(toItem(node));
        }
        return moved;
    }

    @Transactional
    public FileItemResponse upload(long ownerId, Long parentId, MultipartFile upload) {
        User owner = users.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "User not found", 404));
        UserFile parent = parentId == null ? null : requireDirectoryForUpdate(ownerId, parentId);
        FileStorageService.StoredFile stored = storage.store(ownerId, upload);
        PhysicalFile physical = null;
        boolean storedBytesOwned = true;
        boolean physicalCreated = false;
        try {
            // 先按服务端计算的 hash 和大小复用物理文件，客户端指纹只用于预查询，不作为可信来源。
            if (stored.contentHash() != null) {
                Optional<PhysicalFile> existing = physicalFiles.findByFileHashAndFileSize(
                        stored.contentHash(), stored.sizeBytes());
                if (existing.isPresent()) {
                    physical = existing.get();
                    storage.delete(stored.storageKey());
                    storedBytesOwned = false;
                } else {
                    try {
                        physical = physicalFiles.save(new PhysicalFile(stored.contentHash(), stored.sizeBytes(),
                                stored.storageKey(), stored.mimeType()));
                        physicalCreated = true;
                    } catch (DataIntegrityViolationException exception) {
                        physical = physicalFiles.findByFileHashAndFileSize(
                                        stored.contentHash(), stored.sizeBytes())
                                .orElseThrow(() -> exception);
                        storage.delete(stored.storageKey());
                        storedBytesOwned = false;
                    }
                }
            }
            if (physical != null && stored.fileMd5() != null) physical.setFileMd5(stored.fileMd5());
            String name = handleDuplicateFilename(ownerId, parentId, stored.name(), FileNodeType.FILE, null);
            UserFile node = physical == null
                    ? new UserFile(owner, parent, name, FileNodeType.FILE,
                            stored.sizeBytes(), stored.mimeType(), stored.storageKey())
                    : new UserFile(owner, parent, name, physical);
            return toItem(files.save(node));
        } catch (RuntimeException exception) {
            // 元数据保存失败时清理刚写入磁盘的内容，避免产生无法查询的孤立文件。
            try {
                if (storedBytesOwned) storage.delete(stored.storageKey());
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            if (physicalCreated && physical != null) {
                try {
                    physicalFiles.delete(physical);
                } catch (RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            throw exception;
        }
    }

    @Transactional
    public QuickCheckResponse quickCheck(long ownerId, QuickCheckRequest request) {
        String hash = normalizeHash(request.fileHash());
        String name = FileStorageService.validateUploadName(request.fileName());
        FileStorageService.uploadFormatOf(name);
        User owner = users.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "User not found", 404));
        UserFile parent = request.parentFolderId() == null
                ? null : requireDirectoryForUpdate(ownerId, request.parentFolderId());
        // 命中后只新增用户文件视图，绝不再次写入磁盘或接收文件二进制。
        boolean md5 = hash.length() == 32;
        Optional<PhysicalFile> existing = md5
                ? physicalFiles.findFirstByFileMd5AndFileSize(hash, request.fileSize())
                : physicalFiles.findByFileHashAndFileSize(hash, request.fileSize());
        if (existing.isEmpty()) {
            // 仅大文件且使用完整 MD5 时建立会话，秒传命中绝不创建分片会话。
            if (md5 && request.fileSize() > 2 * 1024 * 1024 * 1024L) {
                // 会话绑定目标目录，合并时把最终文件落到该目录下。
                ChunkUploadSession session = request.uploadId() == null
                        ? chunks.createSession(ownerId, name, request.fileSize(), hash, request.parentFolderId())
                        : chunks.resumeSession(ownerId, request.uploadId(), name, request.fileSize(), hash);
                return new QuickCheckResponse(false, null, session.getId(), session.getChunkSize(), session.getTotalParts());
            }
            return new QuickCheckResponse(false, null);
        }

        String uniqueName = handleDuplicateFilename(ownerId, request.parentFolderId(), name, FileNodeType.FILE, null);
        UserFile saved = files.save(new UserFile(owner, parent, uniqueName, existing.get()));
        return new QuickCheckResponse(true, saved.getId());
    }

    private static String normalizeHash(String rawHash) {
        String hash = rawHash == null ? "" : rawHash.trim().toLowerCase(java.util.Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}|[0-9a-f]{32}")) {
            throw new FileBusinessException("INVALID_PARAM", "fileHash必须是32位MD5或64位SHA-256值");
        }
        return hash;
    }

    @Transactional(readOnly = true)
    public FilePreviewResponse preview(long ownerId, long fileId) {
        if (fileId <= 0) throw new FileBusinessException("INVALID_PARAM", "fileId 必须为正整数");
        // 必须先验证当前用户归属与未删除状态，再访问磁盘，统一隐藏他人的文件是否存在。
        UserFile file = files.findByIdAndOwner_Id(fileId, ownerId)
                .filter(node -> node.getNodeType() == FileNodeType.FILE && !node.isDeleted() && node.isPreviewAllowed())
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "资源不存在", 404));
        String format = FileStorageService.formatOf(file.getName());
        return new FilePreviewResponse(file.getId(), file.getName(), format,
                storage.readText(ownerId, file.getStorageKey()), file.isDownloadAllowed());
    }

    @Transactional(readOnly = true)
    public FileDownload download(long ownerId, String filename, Long fileId) {
        if (filename == null || filename.isBlank() || (fileId != null && fileId <= 0)) {
            throw new FileBusinessException("INVALID_PARAM", "请求参数错误", 401);
        }
        UserFile file;
        if (fileId != null) {
            // 文件 ID 用于区分不同目录下的同名文件，查询同时限制当前用户。
            file = files.findByIdAndOwner_Id(fileId, ownerId)
                    .filter(node -> node.getName().equals(filename))
                    .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "资源不存在", 404));
        } else {
            List<UserFile> matches = files.findActiveFilesByOwnerAndName(ownerId, filename);
            if (matches.isEmpty()) throw new FileBusinessException("FILE_NOT_FOUND", "资源不存在", 404);
            if (matches.size() > 1) throw new FileBusinessException("NAME_CONFLICT", "存在同名文件，请指定文件 ID", 409);
            file = matches.get(0);
        }
        // 归属、软删除及两个权限标记均在读取磁盘前校验，失败统一隐藏记录存在性。
        if (file.getNodeType() != FileNodeType.FILE || file.isDeleted()
                || !file.isDownloadAllowed() || !file.isPreviewAllowed()) {
            throw new FileBusinessException("FILE_NOT_FOUND", "资源不存在", 404);
        }
        return new FileDownload(file.getName(), storage.resolveDownload(ownerId, file.getStorageKey()));
    }

    @Transactional(readOnly = true)
    public List<FolderTreeNodeResponse> folderTree(long ownerId) {
        // 查询只包含当前登录用户的未删除目录，文件和其他用户的记录不会进入树。
        List<FolderTreeRow> rows = files.findActiveDirectoryRows(ownerId);
        if (rows.isEmpty()) return List.of();
        Map<Long, List<FolderTreeNodeResponse>> byParentId = new HashMap<>();
        for (FolderTreeRow row : rows) {
            long parentId = row.parentId() == null ? 0L : row.parentId();
            byParentId.computeIfAbsent(parentId, ignored -> new ArrayList<>())
                    .add(new FolderTreeNodeResponse(row.id(), parentId, row.name()));
        }
        List<FolderTreeNodeResponse> roots = byParentId.getOrDefault(0L, List.of());
        ArrayDeque<FolderTreeNodeResponse> pending = new ArrayDeque<>(roots);
        Set<Long> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            FolderTreeNodeResponse node = pending.removeFirst();
            if (!visited.add(node.id())) {
                throw new FileBusinessException("DIRECTORY_TREE_INVALID", "文件夹结构异常", 409);
            }
            List<FolderTreeNodeResponse> children = byParentId.getOrDefault(node.id(), List.of());
            node.children().addAll(children);
            pending.addAll(children);
        }
        if (visited.size() != rows.size()) {
            throw new FileBusinessException("DIRECTORY_TREE_INVALID", "文件夹结构异常", 409);
        }
        return roots;
    }

    @Transactional(readOnly = true)
    public List<FileDownload> downloadMany(long ownerId, List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 50 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new FileBusinessException("INVALID_PARAM", "请选择最多 50 个文件", 400);
        }
        List<Long> uniqueIds = ids.stream().distinct().toList();
        List<FileDownload> downloads = new ArrayList<>();
        long totalSize = 0;
        for (Long id : uniqueIds) {
            UserFile file = files.findByIdAndOwner_Id(id, ownerId)
                    .filter(node -> node.getNodeType() == FileNodeType.FILE && !node.isDeleted()
                            && node.isDownloadAllowed() && node.isPreviewAllowed())
                    .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "资源不存在", 404));
            Path path = storage.resolveDownload(ownerId, file.getStorageKey());
            totalSize += file.getSizeBytes();
            if (totalSize > 1073741824L) {
                throw new FileBusinessException("FILE_TOO_LARGE", "批量下载总大小不能超过 1 GiB", 413);
            }
            downloads.add(new FileDownload(file.getName(), path));
        }
        return List.copyOf(downloads);
    }

    public record FileDownload(String name, Path path) {
    }

    private UserFile requireDirectory(long ownerId, long directoryId) {
        return files.findByIdAndOwner_Id(directoryId, ownerId)
                .filter(file -> file.getNodeType() == FileNodeType.DIRECTORY)
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "Directory not found", 404));
    }

    private UserFile requireDirectoryForUpdate(long ownerId, long directoryId) {
        return files.findByIdAndOwner_IdForUpdate(directoryId, ownerId)
                .filter(file -> file.getNodeType() == FileNodeType.DIRECTORY)
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "Directory not found", 404));
    }

    private UserFile requireNodeForUpdate(long ownerId, long id) {
        return files.findByIdAndOwner_IdForUpdate(id, ownerId)
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "文件或文件夹不存在", 404));
    }

    // 新建、复制或转移节点时共用此规则；文件名加序号时保留扩展名。
    String handleDuplicateFilename(long ownerId, Long parentId, String name,
                                           FileNodeType nodeType, Long excludedId) {
        if (!nameExists(ownerId, parentId, name, excludedId)) return name;
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            String candidate = withNumberSuffix(name, suffix, nodeType == FileNodeType.FILE);
            if (!nameExists(ownerId, parentId, candidate, excludedId)) return candidate;
        }
        throw new FileBusinessException("NAME_EXHAUSTED", "无法为文件夹生成可用名称", 409);
    }

    static String withNumberSuffix(String name, int suffix, boolean preserveExtension) {
        String ending = "（" + suffix + "）";
        int extensionStart = preserveExtension ? name.lastIndexOf('.') : -1;
        if (extensionStart < 0) extensionStart = name.length();
        String base = name.substring(0, extensionStart);
        String extension = name.substring(extensionStart);
        int maximumBaseLength = 255 - ending.length() - extension.length();
        if (maximumBaseLength < 0) {
            throw new FileBusinessException("INVALID_PARAM", "无法在保留扩展名的情况下生成可用名称");
        }
        int end = base.length();
        while (end > maximumBaseLength) {
            end = base.offsetByCodePoints(end, -1);
        }
        return base.substring(0, end) + ending + extension;
    }

    private boolean nameExists(long ownerId, Long parentId, String name) {
        return parentId == null
                ? files.existsByOwner_IdAndParentIsNullAndName(ownerId, name)
                : files.existsByOwner_IdAndParent_IdAndName(ownerId, parentId, name);
    }

    private boolean nameExists(long ownerId, Long parentId, String name, Long excludedId) {
        if (excludedId == null) return nameExists(ownerId, parentId, name);
        return parentId == null
                ? files.existsByOwner_IdAndParentIsNullAndNameAndIdNot(ownerId, name, excludedId)
                : files.existsByOwner_IdAndParent_IdAndNameAndIdNot(ownerId, parentId, name, excludedId);
    }

    private static String normalizeDirectoryName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(character -> character < 32 || "<>:\"/\\|?*".indexOf(character) >= 0)) {
            throw new FileBusinessException("INVALID_PARAM", "文件夹名称无效或超过 255 个字符");
        }
        return name;
    }

    private static FileItemResponse toItem(UserFile file) {
        return new FileItemResponse(file.getId(), file.getName(), file.getNodeType(), file.getSizeBytes(),
                file.getMimeType(), file.getUpdatedAt(), file.isDownloadAllowed(), file.isPreviewAllowed());
    }

    private List<FileBreadcrumb> breadcrumbs(UserFile directory) {
        List<FileBreadcrumb> crumbs = new ArrayList<>();
        for (UserFile current = directory; current != null; current = current.getParent()) {
            crumbs.add(new FileBreadcrumb(current.getId(), current.getName()));
        }
        Collections.reverse(crumbs);
        crumbs.add(0, new FileBreadcrumb(null, "我的文件"));
        return List.copyOf(crumbs);
    }
}
