package com.networkdisk.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户名密码登录请求。邮箱仅用于注册和接收验证码，不作为登录凭证。 */
public record LoginRequest(
        @NotBlank(message = "请输入用户名")
        @Size(min = 3, max = 32, message = "用户名长度为3-32位")
        String username,

        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 64, message = "密码长度为6-64位")
        String password,

        /** 达到失败阈值后才填写；为空时由登录状态机返回 CAPTCHA_REQUIRED。 */
        String captchaCode
) {
    public LoginRequest(String username, String password) {
        this(username, password, null);
    }
}
