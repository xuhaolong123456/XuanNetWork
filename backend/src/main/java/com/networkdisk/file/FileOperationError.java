package com.networkdisk.file;

import jakarta.servlet.http.HttpServletRequest;

public record FileOperationError(int code, String msg, Object data) {
    public static boolean applies(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/files/file/") || path.equals("/api/v1/files/files")
                || path.startsWith("/api/v1/files/recover/") || path.equals("/api/v1/files/recycle-bin")
                || (request.getMethod().equals("DELETE") && path.startsWith("/api/v1/files/directories/"));
    }

    public static FileOperationError of(int status, String message) {
        return new FileOperationError(status, message, null);
    }
}
