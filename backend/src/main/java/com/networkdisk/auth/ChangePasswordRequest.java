package com.networkdisk.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "请输入当前密码") String currentPassword,
        @NotBlank(message = "请输入新密码")
        @Size(min = 6, max = 64, message = "新密码长度为6-64位") String newPassword
) {}
