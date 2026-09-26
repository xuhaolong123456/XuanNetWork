package com.networkdisk.file;

import java.util.List;

public record FileListResponse(FileDirectoryResponse currentDirectory, List<FileBreadcrumb> breadcrumbs,
                               List<FileItemResponse> items, FilePageResponse page) {
}
