package com.networkdisk.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** 协调邮箱验证码发送、Redis 状态管理和注册校验。 */
@Service
public class EmailCodeService {
    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);
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
        if (hmacSecret == null || hmacSecret.isBlank() || hmacSecret.startsWith("请勿填写")) {
            throw new IllegalStateException("未配置 REDIS_KEY_SECRET");
        }
        try {
            this.hmacSecret = Base64.getDecoder().decode(hmacSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("REDIS_KEY_SECRET 必须是 Base64 格式", exception);
        }
    }

    public void send(String rawEmail, String clientIp) {
        String email = normalize(rawEmail);
        log.debug("收到邮箱验证码发送请求：收件地址脱敏值={}，客户端IP={}，邮件开关={}，发件人={}",
                maskEmail(email), clientIp, mailEnabled, from);
        if (!mailEnabled) {
            log.warn("邮箱验证码未发送：邮件功能已关闭（app.mail.enabled=false）");
            throw new AuthBusinessException("MAIL_DISABLED", "邮件服务未启用，请联系管理员");
        }
        if (userRepository.existsByEmail(email)) {
            throw new AuthBusinessException("EMAIL_EXISTS", "该邮箱已注册，请直接登录");
        }
        String emailKey = emailKey(email);
        EmailCodeRedisRepository.SendReservation reservation = redisRepository.beginSend(emailKey, clientIp);
        switch (reservation) {
            case IP_LIMITED -> throw new AuthBusinessException("IP_RATE_LIMITED", "当前请求过于频繁，请稍后重试");
            case COOLDOWN -> throw new AuthBusinessException("EMAIL_COOLDOWN", "请在 60 秒后重新获取验证码");
            case DAILY_LIMITED -> throw new AuthBusinessException("EMAIL_DAILY_LIMITED", "该邮箱今日验证码发送次数已达上限");
            case SEND_IN_PROGRESS -> throw new AuthBusinessException("SEND_IN_PROGRESS", "验证码正在发送，请稍后重试");
            case UNAVAILABLE -> throw new AuthBusinessException("REDIS_UNAVAILABLE", "验证码服务暂不可用，请稍后重试");
            case ACCEPTED -> { }
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        try {
            // 仅在邮件调用成功后保存验证码，避免用户拿到尚未发送的验证码。
            sendMail(email, code);
            if (!redisRepository.completeSend(emailKey, codeDigest(email, code))) {
                throw new AuthBusinessException("EMAIL_DAILY_LIMITED", "该邮箱今日验证码发送次数已达上限");
            }
            log.info("邮箱验证码发送流程完成：收件地址脱敏值={}，验证码已由SMTP客户端接受，Redis状态已保存", maskEmail(email));
        } catch (MailException exception) {
            redisRepository.cancelSend(emailKey);
            log.error("邮箱验证码发送失败：收件地址脱敏值={}，异常类型={}，异常信息={}",
                    maskEmail(email), exception.getClass().getName(), escapeUnicode(exception.getMessage()), exception);
            throw new AuthBusinessException("MAIL_SEND_FAILED", "邮件服务发送失败，请稍后重试");
        } catch (RuntimeException exception) {
            redisRepository.cancelSend(emailKey);
            log.error("邮箱验证码处理失败：收件地址脱敏值={}，异常类型={}，异常信息={}",
                    maskEmail(email), exception.getClass().getName(), escapeUnicode(exception.getMessage()), exception);
            throw exception;
        }
    }

    /** 取得 Redis 一次性注册锁后才执行数据库注册，并在失败时释放锁。 */
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
            case IN_PROGRESS -> throw new AuthBusinessException("REGISTER_IN_PROGRESS", "注册请求正在处理，请稍后重试");
            case UNAVAILABLE -> throw new AuthBusinessException("REDIS_UNAVAILABLE", "验证码服务暂不可用，请稍后重试");
            case ACCEPTED -> { }
        }
        try {
            // 注册锁保证验证码只成功消费一次；业务失败时保留验证码供用户重试。
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
        SimpleMailMessage mail = new SimpleMailMessage();
        if (from != null && !from.isBlank()) mail.setFrom(from.trim());
        mail.setTo(email);
        mail.setSubject("网盘注册验证码");
        mail.setText("你的注册验证码是：" + code + "\n验证码 60 秒内有效，请勿泄露给他人。");
        log.debug("准备调用SMTP发送邮箱验证码：收件地址脱敏值={}，主题转义值={}，正文模板转义值={}，发件人={}",
                maskEmail(email), escapeUnicode(mail.getSubject()),
                escapeUnicode("你的注册验证码是：******\n验证码 60 秒内有效，请勿泄露给他人。"), from);
        mailSender.send(mail);
        log.debug("JavaMailSender.send 已正常返回：收件地址脱敏值={}；该结果表示SMTP客户端已接受请求，不代表收件箱最终投递完成",
                maskEmail(email));
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        return (local.length() == 1 ? "*" : local.substring(0, 1) + "***") + email.substring(at);
    }

    private static String escapeUnicode(String value) {
        if (value == null) return "<null>";
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character > 0x7e || character < 0x20 && character != '\n' && character != '\r' && character != '\t') {
                escaped.append(String.format("\\u%04X", (int) character));
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
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
