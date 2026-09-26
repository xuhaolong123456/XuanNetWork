package com.networkdisk.auth;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import jakarta.servlet.http.HttpServletResponse;

/** Builds the browser cookie that carries the short-lived authentication token. */
public final class AuthCookie {
    public static final String NAME = "NETWORKDISK_AUTH";

    private AuthCookie() {}

    public static void set(HttpServletResponse response, String token, long ttlSeconds, boolean secure) {
        // HttpOnly prevents page scripts from reading the JWT; SameSite adds a browser-level CSRF barrier.
        // Secure is enabled for HTTPS deployments and stays configurable for local HTTP development.
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
        // Cookie removal must use the same name and path as issuance so the browser replaces it.
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
