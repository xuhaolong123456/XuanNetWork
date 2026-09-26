package com.networkdisk.auth;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import jakarta.servlet.http.HttpServletResponse;

/** 构造用于承载短期认证令牌的浏览器 Cookie。 */
public final class AuthCookie {
    public static final String NAME = "NETWORKDISK_AUTH";

    private AuthCookie() {}

    public static void set(HttpServletResponse response, String token, long ttlSeconds, boolean secure) {
        // HttpOnly 可阻止页面脚本读取 JWT；SameSite 为跨站请求伪造提供浏览器层面的防护。
        // HTTPS 部署时启用 Secure；本地 HTTP 开发环境可通过配置调整。
        ResponseCookie cookie = ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ofSeconds(ttlSeconds))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static void clear(HttpServletResponse response, boolean secure) {
        // 删除 Cookie 时必须使用与签发时相同的名称和路径，浏览器才能正确替换原 Cookie。
        ResponseCookie cookie = ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
