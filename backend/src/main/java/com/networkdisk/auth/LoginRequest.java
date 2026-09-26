package com.networkdisk.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 用户名密码登录请求。邮箱仅用于注册和接收验证码，不作为登录凭证。 */
public record LoginRequest(
        @NotBlank(message = "请输入用户名")
        @Size(min = 3, max = 32, message = "用户名长度为3-32位")
        String username,

        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 64, message = "密码长度为6-64位")
        String password,

        /** Every login requires a fresh image captcha. */
        @NotBlank(message = "请输入验证码")
        @Pattern(regexp = "\\d{4}", message = "验证码为4位数字")
        String captchaCode
) {
    public LoginRequest(String username, String password) {
        this(username, password, null);
    }
}
