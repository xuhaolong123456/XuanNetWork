package com.networkdisk.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求对象。
 * 字段值来自前端 POST /api/v1/auth/register 的 JSON 请求体。
 * confirmPassword 只在前端确认，不提交到后端。
 */
public record RegisterRequest(
        @NotBlank(message = "请输入邮箱")
        @Email(message = "邮箱格式错误")
        @Size(max = 128, message = "邮箱长度不能超过128位")
        /** 用户注册邮箱，仅用于注册验证和后续通知，不作为登录账号。 */
        String email,

        @NotBlank(message = "请输入用户名")
        @Size(min = 3, max = 32, message = "用户名长度为3-32位")
        /** 全局唯一用户名；底层暂沿用 nickName 字段以兼容现有数据。 */
        String nickName,

        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 64, message = "密码长度为6-64位")
        /** 用户提交的原始密码，仅在内存中短暂存在，Service 会立即进行 BCrypt 哈希。 */
        String password,

        /** 图形验证码预留字段，当前尚未校验。 */
        String checkCode,
        /** 邮箱验证码预留字段，当前尚未校验。 */
        String emailCode
) {
}
