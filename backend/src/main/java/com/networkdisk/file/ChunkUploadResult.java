package com.networkdisk.file;

import jakarta.servlet.http.HttpServletRequest;

/** 分片接口遵循数字业务码协议，不改变既有接口的 Result 协议。 */
public record ChunkUploadResult<T>(int code, String msg, T data) {
    public static boolean applies(HttpServletRequest request) {
        String base = request.getContextPath() + "/api/v1/files/file/chunk-upload";
        String path = request.getRequestURI();
        return path.equals(base) || path.equals(base + "/complete");
    }

    public static ChunkUploadResult<Void> failure(int code, String message) {
        return new ChunkUploadResult<>(code, message, null);
    }

    public static boolean isListParts(HttpServletRequest request) {
        return applies(request) && "GET".equals(request.getMethod());
    }

    public static String unauthorizedMessage(HttpServletRequest request) {
        return isListParts(request) ? "用户未登录，请重新登录" : "用户身份校验失败";
    }
}
