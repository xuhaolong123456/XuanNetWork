package com.networkdisk.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    public String create(Long userId, String passwordHash) {
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        // 将用户 ID、过期时间、随机令牌 ID 和密码指纹写入签名载荷。
        String payload = base64Url("{\"sub\":\"" + userId + "\",\"exp\":" + expiresAt
                + ",\"jti\":\"" + UUID.randomUUID() + "\",\"ver\":\"" + credentialFingerprint(passwordHash) + "\"}");
        String unsignedToken = HEADER + "." + payload;
        return unsignedToken + "." + sign(unsignedToken);
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** 仅当令牌签名有效且尚未过期时返回其中的声明。 */
    public Optional<Claims> verify(String token) {
        if (token == null || token.length() > 4096) return Optional.empty();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !HEADER.equals(parts[0])) return Optional.empty();
        try {
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expectedSignature = signBytes(parts[0] + "." + parts[1]);
            // 使用定时比较避免签名比较时间泄露与输入字节内容相关的信息。
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) return Optional.empty();

            JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (claims == null || !claims.isObject()) return Optional.empty();
            JsonNode subject = claims.get("sub");
            JsonNode expiry = claims.get("exp");
            JsonNode tokenId = claims.get("jti");
            JsonNode version = claims.get("ver");
            if (subject == null || !subject.isTextual() || !subject.textValue().matches("[1-9][0-9]*")
                    || expiry == null || !expiry.isIntegralNumber() || !expiry.canConvertToLong()
                    || expiry.longValue() <= Instant.now().getEpochSecond()
                    || tokenId == null || !tokenId.isTextual() || tokenId.textValue().isBlank()
                    || version == null || !version.isTextual() || version.textValue().isBlank()) return Optional.empty();
            long userId = Long.parseLong(subject.textValue());
            return userId > 0 ? Optional.of(new Claims(userId, expiry.longValue(), version.textValue())) : Optional.empty();
        } catch (IllegalArgumentException | java.io.IOException exception) {
            return Optional.empty();
        }
    }

    /** 密码重新哈希后指纹会改变，从而使此前签发的所有令牌失效。 */
    public boolean hasCurrentPassword(Claims claims, String passwordHash) {
        return MessageDigest.isEqual(claims.credentialFingerprint().getBytes(StandardCharsets.US_ASCII),
                credentialFingerprint(passwordHash).getBytes(StandardCharsets.US_ASCII));
    }

    private String credentialFingerprint(String passwordHash) {
        return sign("credential:" + passwordHash);
    }

    public record Claims(long userId, long expiresAt, String credentialFingerprint) {}

    private String sign(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signBytes(value));
    }

    private byte[] signBytes(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法生成登录令牌", exception);
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
