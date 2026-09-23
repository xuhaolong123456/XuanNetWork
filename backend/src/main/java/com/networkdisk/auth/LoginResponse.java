package com.networkdisk.auth;

/** 登录成功结果。响应中不会包含邮箱和密码哈希。 */
public record LoginResponse(String accessToken, Long userId, String username) {
}
