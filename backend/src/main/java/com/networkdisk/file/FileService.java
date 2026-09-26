package com.networkdisk.file;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    public FileService(UserFileRepository files, UserRepository users, FileStorageService storage) {
        this.files = files;
        this.users = users;
        this.storage = storage;
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
                file.getMimeType(), file.getUpdatedAt())).toList();
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
    public FileItemResponse upload(long ownerId, Long parentId, MultipartFile upload) {
        User owner = users.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "User not found", 404));
        UserFile parent = parentId == null ? null : requireDirectoryForUpdate(ownerId, parentId);
        FileStorageService.StoredFile stored = storage.store(ownerId, upload);
        try {
            UserFile node = new UserFile(owner, parent, stored.name(), FileNodeType.FILE,
                    stored.sizeBytes(), stored.mimeType(), stored.storageKey());
            return toItem(files.save(node));
        } catch (RuntimeException exception) {
            // 元数据保存失败时清理刚写入磁盘的内容，避免产生无法查询的孤立文件。
            try {
                storage.delete(stored.storageKey());
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
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
                file.getMimeType(), file.getUpdatedAt());
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
