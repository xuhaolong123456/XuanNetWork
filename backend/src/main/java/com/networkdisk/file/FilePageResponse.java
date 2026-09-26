package com.networkdisk.file;

public record FilePageResponse(int number, int size, long totalElements, int totalPages) {
}
