package com.networkdisk.file;

import java.util.List;

public record TrashListResponse(List<TrashItemResponse> items, FilePageResponse page) {
}
