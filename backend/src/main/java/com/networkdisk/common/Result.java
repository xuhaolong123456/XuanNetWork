package com.networkdisk.common;

/** 所有 REST 接口共用的统一响应结构。T 表示 data 的具体类型。 */
public record Result<T>(boolean success, String code, String message, T data) {

    /** 构造成功响应，data 是接口返回的业务数据。 */
    public static <T> Result<T> success(T data) {
        return new Result<>(true, "OK", "操作成功", data);
    }

    /** 构造带业务结果说明的成功响应，适用于成功状态下仍需区分业务分支的接口。 */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(true, "OK", message, data);
    }

    /** 构造失败响应，data 固定为空，前端根据 code 和 message 处理错误。 */
    public static <T> Result<T> failure(String code, String message) {
        return new Result<>(false, code, message, null);
    }
}
