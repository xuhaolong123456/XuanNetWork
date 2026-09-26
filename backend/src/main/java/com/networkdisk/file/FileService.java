package com.networkdisk.file;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileService {
    private static final int MAX_PAGE_SIZE = 100;
    private final UserFileRepository files;

    public FileService(UserFileRepository files) {
        this.files = files;
    }

    @Transactional(readOnly = true)
    public FileListResponse list(long ownerId, Long parentId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new FileBusinessException("INVALID_PARAM", "page must be non-negative and size must be between 1 and 100");
        }
        UserFile directory = null;
        if (parentId != null) {
            directory = files.findByIdAndOwner_Id(parentId, ownerId)
                    .filter(file -> file.getNodeType() == FileNodeType.DIRECTORY)
                    .orElseThrow(() -> new FileBusinessException("FILE_NOT_FOUND", "Directory not found"));
        }

        PageRequest request = PageRequest.of(page, size, Sort.by(
                Sort.Order.asc("nodeType"), Sort.Order.asc("name"), Sort.Order.asc("id")));
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
