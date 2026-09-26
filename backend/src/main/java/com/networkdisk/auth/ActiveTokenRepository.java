package com.networkdisk.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** Redis 活跃令牌白名单；删除对应键即可立即撤销单个令牌。 */
@Repository
public class ActiveTokenRepository {
    private final StringRedisTemplate redis;

    public ActiveTokenRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void activate(String token, long userId, long ttlSeconds) {
        redis.opsForValue().set(key(token), String.valueOf(userId), Duration.ofSeconds(ttlSeconds));
    }

    public boolean isActive(String token, long userId) {
        return String.valueOf(userId).equals(redis.opsForValue().get(key(token)));
    }

    public void revoke(String token) {
        redis.delete(key(token));
    }

    private static String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return "auth:token:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算令牌摘要", exception);
        }
    }
}
