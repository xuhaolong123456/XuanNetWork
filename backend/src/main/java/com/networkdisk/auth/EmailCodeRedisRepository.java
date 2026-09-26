package com.networkdisk.auth;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** Redis 数据访问层：所有会改变多个验证码状态的操作均使用 Lua 保证原子性。 */
@Repository
public class EmailCodeRedisRepository {
    private static final int CODE_TTL_SECONDS = 60;
    private static final int SEND_LOCK_TTL_SECONDS = 30;
    private static final int REGISTER_LOCK_TTL_SECONDS = 30;
    private static final int MAX_DAILY_SENDS = 10;
    private static final int MAX_IP_SENDS = 10;
    private static final int MAX_FAILURES = 3;

    // Lua 脚本在 Redis 单线程中原子执行，保证限流检查与发送锁获取不会被并发请求穿插。
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> beginSendScript = longScript("""
            local ipCount = redis.call('INCR', KEYS[1])
            if ipCount == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            if ipCount > tonumber(ARGV[2]) then return 1 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
            local daily = tonumber(redis.call('GET', KEYS[3]) or '0')
            if daily >= tonumber(ARGV[3]) then return 3 end
            if redis.call('SET', KEYS[4], '1', 'NX', 'EX', ARGV[4]) then return 0 end
            return 4
            """);
    private final DefaultRedisScript<Long> completeSendScript = longScript("""
            local daily = tonumber(redis.call('GET', KEYS[3]) or '0')
            if daily >= tonumber(ARGV[3]) then redis.call('DEL', KEYS[4]); return 1 end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])
            local count = redis.call('INCR', KEYS[3])
            if count == 1 then redis.call('EXPIRE', KEYS[3], ARGV[4]) end
            redis.call('DEL', KEYS[4])
            return 0
            """);
    private final DefaultRedisScript<List> beginRegisterScript = listScript("""
            local digest = redis.call('GET', KEYS[1])
            if not digest then return {1, 0} end
            if digest ~= ARGV[1] then
              local failures = redis.call('INCR', KEYS[2])
              local ttl = redis.call('TTL', KEYS[1])
              if ttl > 0 then redis.call('EXPIRE', KEYS[2], ttl) end
              if failures >= tonumber(ARGV[2]) then
                redis.call('DEL', KEYS[1]); redis.call('DEL', KEYS[2]); return {3, 0}
              end
              return {2, tonumber(ARGV[2]) - failures}
            end
            if redis.call('SET', KEYS[3], '1', 'NX', 'EX', ARGV[3]) then return {0, 0} end
            return {4, 0}
            """);

    public EmailCodeRedisRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 发送前检查限流，并取得仅针对该邮箱的短期发送锁。 */
    public SendReservation beginSend(String emailKey, String clientIp) {
        Long result = redis.execute(beginSendScript, List.of(ipKey(clientIp), cooldownKey(emailKey), dailyKey(emailKey), sendLockKey(emailKey)),
                "60", String.valueOf(MAX_IP_SENDS), String.valueOf(MAX_DAILY_SENDS), String.valueOf(SEND_LOCK_TTL_SECONDS));
        return SendReservation.from(result == null ? -1 : result);
    }

    /** SMTP 成功后才保存验证码状态；每日计数 TTL 对齐到北京时间下一个零点。 */
    public boolean completeSend(String emailKey, String codeDigest) {
        long dailyTtl = secondsUntilNextMidnight();
        Long result = redis.execute(completeSendScript, List.of(codeKey(emailKey), cooldownKey(emailKey), dailyKey(emailKey), sendLockKey(emailKey)),
                codeDigest, String.valueOf(CODE_TTL_SECONDS), String.valueOf(MAX_DAILY_SENDS), String.valueOf(dailyTtl));
        return result != null && result == 0;
    }

    public void cancelSend(String emailKey) {
        redis.delete(sendLockKey(emailKey));
    }

    /** 原子校验验证码、累计失败次数并获取注册使用锁。 */
    public RegisterReservation beginRegister(String emailKey, String codeDigest) {
        List<?> result = redis.execute(beginRegisterScript, List.of(codeKey(emailKey), failureKey(emailKey), registerLockKey(emailKey)),
                codeDigest, String.valueOf(MAX_FAILURES), String.valueOf(REGISTER_LOCK_TTL_SECONDS));
        if (result == null || result.size() != 2) return RegisterReservation.unavailable();
        return RegisterReservation.from(((Number) result.get(0)).longValue(), ((Number) result.get(1)).longValue());
    }

    public void completeRegistration(String emailKey) {
        redis.delete(List.of(codeKey(emailKey), failureKey(emailKey), registerLockKey(emailKey)));
    }

    public void releaseRegisterLock(String emailKey) {
        redis.delete(registerLockKey(emailKey));
    }

    public void invalidateCode(String emailKey) {
        redis.delete(List.of(codeKey(emailKey), failureKey(emailKey), registerLockKey(emailKey)));
    }

    private static DefaultRedisScript<Long> longScript(String text) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(); script.setScriptText(text); script.setResultType(Long.class); return script;
    }
    private static DefaultRedisScript<List> listScript(String text) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>(); script.setScriptText(text); script.setResultType(List.class); return script;
    }
    private static long secondsUntilNextMidnight() {
        ZonedDateTime now = ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).toSeconds();
    }
    private static String codeKey(String key) { return "verify:code:" + key; }
    private static String cooldownKey(String key) { return "verify:cooldown:" + key; }
    private static String dailyKey(String key) { return "verify:daily:" + key; }
    private static String failureKey(String key) { return "verify:fail:" + key; }
    private static String registerLockKey(String key) { return "verify:register-lock:" + key; }
    private static String sendLockKey(String key) { return "verify:send-lock:" + key; }
    private static String ipKey(String ip) { return "verify:ip:" + ip; }

    public enum SendReservation {
        ACCEPTED, IP_LIMITED, COOLDOWN, DAILY_LIMITED, SEND_IN_PROGRESS, UNAVAILABLE;
        static SendReservation from(long value) { return switch ((int) value) { case 0 -> ACCEPTED; case 1 -> IP_LIMITED; case 2 -> COOLDOWN; case 3 -> DAILY_LIMITED; case 4 -> SEND_IN_PROGRESS; default -> UNAVAILABLE; }; }
    }
    public record RegisterReservation(RegisterStatus status, int remainingAttempts) {
        static RegisterReservation from(long status, long remaining) { return new RegisterReservation(switch ((int) status) { case 0 -> RegisterStatus.ACCEPTED; case 1 -> RegisterStatus.EXPIRED; case 2 -> RegisterStatus.WRONG_CODE; case 3 -> RegisterStatus.MAX_FAILURES; case 4 -> RegisterStatus.IN_PROGRESS; default -> RegisterStatus.UNAVAILABLE; }, (int) remaining); }
        static RegisterReservation unavailable() { return new RegisterReservation(RegisterStatus.UNAVAILABLE, 0); }
    }
    public enum RegisterStatus { ACCEPTED, EXPIRED, WRONG_CODE, MAX_FAILURES, IN_PROGRESS, UNAVAILABLE }
}
