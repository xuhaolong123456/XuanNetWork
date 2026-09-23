package com.networkdisk.auth;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** 登录失败、验证码和 IP 计数的 Redis 原子操作。 */
@Repository
public class LoginAttemptRedisRepository {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> incrementScript = listScript("""
            local count = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            return {count, redis.call('TTL', KEYS[1])}
            """);
    private final DefaultRedisScript<List> captchaVerifyScript = listScript("""
            local expected = redis.call('GET', KEYS[1])
            if not expected then return {0, 0} end
            if expected ~= ARGV[1] then return {0, 1} end
            redis.call('DEL', KEYS[1])
            return {1, 1}
            """);
    private final DefaultRedisScript<List> failureStateScript = listScript("""
            local value = redis.call('GET', KEYS[1])
            if not value then return {0, -1} end
            return {tonumber(value), redis.call('TTL', KEYS[1])}
            """);

    /** 仅供现有单元测试的兼容构造器；Spring 生产环境使用 Redis 构造器。 */
    public LoginAttemptRedisRepository() {
        this.redis = null;
    }

    @Autowired
    public LoginAttemptRedisRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public IpAttemptResult beginIpAttempt(String ip, int maxAttempts, int ttlSeconds) {
        if (redis == null) return new IpAttemptResult(false, ttlSeconds);
        List<?> result = redis.execute(incrementScript, List.of(ipKey(ip)), String.valueOf(ttlSeconds));
        if (result == null || result.size() < 2) return new IpAttemptResult(false, ttlSeconds);
        long count = number(result.get(0));
        long ttl = Math.max(1, number(result.get(1)));
        return new IpAttemptResult(count > maxAttempts, ttl);
    }

    public FailureState getFailureState(String username) {
        if (redis == null) return new FailureState(0, -1);
        List<?> result = redis.execute(failureStateScript, List.of(failureKey(username)));
        if (result == null || result.size() < 2) return new FailureState(0, -1);
        return new FailureState(number(result.get(0)), number(result.get(1)));
    }

    public FailureState incrementFailure(String username, int ttlSeconds) {
        if (redis == null) return new FailureState(0, ttlSeconds);
        List<?> result = redis.execute(incrementScript, List.of(failureKey(username)), String.valueOf(ttlSeconds));
        if (result == null || result.size() < 2) return new FailureState(0, ttlSeconds);
        return new FailureState(number(result.get(0)), Math.max(1, number(result.get(1))));
    }

    public void clearFailure(String username) {
        if (redis != null) redis.delete(failureKey(username));
    }

    public void saveCaptcha(String username, String code, int ttlSeconds) {
        if (redis != null) redis.opsForValue().set(captchaKey(username), code, java.time.Duration.ofSeconds(ttlSeconds));
    }

    public boolean verifyAndConsumeCaptcha(String username, String code) {
        if (redis == null) return false;
        List<?> result = redis.execute(captchaVerifyScript, List.of(captchaKey(username)), code == null ? "" : code.trim());
        return result != null && !result.isEmpty() && number(result.get(0)) == 1;
    }

    public void clearCaptcha(String username) {
        if (redis != null) redis.delete(captchaKey(username));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static DefaultRedisScript<List> listScript(String text) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(text);
        script.setResultType(List.class);
        return script;
    }

    private static String failureKey(String username) { return "login:fail:" + username; }
    private static String captchaKey(String username) { return "login:captcha:" + username; }
    private static String ipKey(String ip) { return "login:ip:" + ip; }

    public record IpAttemptResult(boolean limited, long retryAfterSeconds) {}
    public record FailureState(long failures, long ttlSeconds) {}
}
