package com.networkdisk.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** 邮箱验证码业务层：协调 MySQL、Redis Lua 操作与同步 SMTP 发送。 */
@Service
public class EmailCodeService {
    private final EmailCodeRedisRepository redisRepository;
    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final String from;
    private final boolean mailEnabled;
    private final byte[] hmacSecret;
    private final SecureRandom random = new SecureRandom();

    public EmailCodeService(EmailCodeRedisRepository redisRepository, UserRepository userRepository,
                            JavaMailSender mailSender, @Value("${app.mail.from:}") String from,
                            @Value("${app.mail.enabled:true}") boolean mailEnabled,
                            @Value("${app.redis.key-secret}") String hmacSecret) {
        this.redisRepository = redisRepository;
        this.userRepository = userRepository;
        this.mailSender = mailSender;
        this.from = from;
        this.mailEnabled = mailEnabled;
        if (hmacSecret == null || hmacSecret.isBlank() || hmacSecret.startsWith("请填写")) {
            throw new IllegalStateException("REDIS_KEY_SECRET 未配置");
        }
        try {
            this.hmacSecret = Base64.getDecoder().decode(hmacSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("REDIS_KEY_SECRET 必须是 Base64 格式", exception);
        }
    }

    public void send(String rawEmail, String clientIp) {
        String email = normalize(rawEmail);
        if (userRepository.existsByEmail(email)) {
            throw new AuthBusinessException("EMAIL_EXISTS", "该邮箱已注册，请直接登录");
        }
        String emailKey = emailKey(email);
        EmailCodeRedisRepository.SendReservation reservation = redisRepository.beginSend(emailKey, clientIp);
        switch (reservation) {
            case IP_LIMITED -> throw new AuthBusinessException("IP_RATE_LIMITED", "当前请求过于频繁，请稍后重试");
            case COOLDOWN -> throw new AuthBusinessException("EMAIL_COOLDOWN", "请在 60 秒后重新获取验证码");
            case DAILY_LIMITED -> throw new AuthBusinessException("EMAIL_DAILY_LIMITED", "该邮箱今日验证码发送次数已达上限");
            case SEND_IN_PROGRESS -> throw new AuthBusinessException("SEND_IN_PROGRESS", "验证码已经发送，请稍后");
            case UNAVAILABLE -> throw new AuthBusinessException("REDIS_UNAVAILABLE", "验证码服务暂不可用，请稍后重试");
            case ACCEPTED -> { }
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        try {
            // 先确认邮件发送成功，再把验证码摘要写入 Redis，避免用户收到不可用的验证码。
            sendMail(email, code);
            if (!redisRepository.completeSend(emailKey, codeDigest(email, code))) {
                throw new AuthBusinessException("EMAIL_DAILY_LIMITED", "该邮箱今日验证码发送次数已达上限");
            }
        } catch (MailException exception) {
            redisRepository.cancelSend(emailKey);
            throw new AuthBusinessException("MAIL_SEND_FAILED", "邮件服务发送失败，请稍后重试");
        } catch (RuntimeException exception) {
            redisRepository.cancelSend(emailKey);
            throw exception;
        }
    }

    /** 只有 Lua 原子校验并取得注册锁后，才允许调用数据库注册逻辑。 */
    public String register(RegisterRequest request, AuthService authService) {
        String email = normalize(request.email());
        String emailKey = emailKey(email);
        EmailCodeRedisRepository.RegisterReservation reservation =
                redisRepository.beginRegister(emailKey, codeDigest(email, request.emailCode()));
        switch (reservation.status()) {
            case EXPIRED -> throw new AuthBusinessException("CODE_EXPIRED", "验证码已过期，请重新获取");
            case WRONG_CODE -> throw new AuthBusinessException("INVALID_EMAIL_CODE",
                    "验证码错误，还可尝试 " + reservation.remainingAttempts() + " 次");
            case MAX_FAILURES -> throw new AuthBusinessException("CODE_EXPIRED", "验证码错误次数已达上限，请重新获取");
            case IN_PROGRESS -> throw new AuthBusinessException("REGISTER_IN_PROGRESS", "注册请求正在处理，请稍后");
            case UNAVAILABLE -> throw new AuthBusinessException("REDIS_UNAVAILABLE", "验证码服务暂不可用，请稍后重试");
            case ACCEPTED -> { }
        }
        try {
            // Redis 已取得一次性注册锁；完成数据库注册后删除验证码，失败时释放锁以便重试。
            String userId = authService.register(request);
            redisRepository.completeRegistration(emailKey);
            return userId;
        } catch (AuthBusinessException exception) {
            if ("EMAIL_EXISTS".equals(exception.getCode())) {
                redisRepository.invalidateCode(emailKey);
            } else {
                redisRepository.releaseRegisterLock(emailKey);
            }
            throw exception;
        } catch (RuntimeException exception) {
            redisRepository.releaseRegisterLock(emailKey);
            throw exception;
        }
    }

    private void sendMail(String email, String code) {
        if (!mailEnabled) return;
        SimpleMailMessage mail = new SimpleMailMessage();
        if (from != null && !from.isBlank()) mail.setFrom(from.trim());
        mail.setTo(email);
        mail.setSubject("NetworkDisk 注册验证码");
        mail.setText("你的注册验证码是：" + code + "\n验证码 60 秒内有效，请勿泄露给他人。");
        mailSender.send(mail);
    }

    private String emailKey(String email) { return hmac("email:" + email); }
    // Redis 键和值仅保存 HMAC 摘要，避免直接暴露邮箱和验证码。
    private String codeDigest(String email, String code) { return hmac("code:" + email + ":" + (code == null ? "" : code)); }
    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法初始化 HMAC", exception);
        }
    }
    private static String normalize(String email) { return email.trim().toLowerCase(); }
}
