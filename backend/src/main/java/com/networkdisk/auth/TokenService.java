package com.networkdisk.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 生成短期 HMAC-SHA256 登录令牌。 */
@Service
public class TokenService {
    private static final String HEADER = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
    private final byte[] secret;
    private final long ttlSeconds;

    public TokenService(@Value("${app.auth.token-secret}") String encodedSecret,
                        @Value("${app.auth.token-ttl-seconds:7200}") long ttlSeconds) {
        try {
            this.secret = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AUTH_TOKEN_SECRET 必须是 Base64 格式", exception);
        }
        if (secret.length < 32) {
            throw new IllegalStateException("AUTH_TOKEN_SECRET 解码后不能少于 32 字节");
        }
        this.ttlSeconds = ttlSeconds;
    }

    public String create(Long userId) {
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        String payload = base64Url("{\"sub\":\"" + userId + "\",\"exp\":" + expiresAt + "}");
        String unsignedToken = HEADER + "." + payload;
        return unsignedToken + "." + sign(unsignedToken);
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法生成登录令牌", exception);
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
