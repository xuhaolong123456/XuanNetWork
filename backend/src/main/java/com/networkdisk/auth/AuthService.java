package com.networkdisk.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/** 注册认证业务层，承载注册规则和事务边界。 */
public class AuthService {

    /** 由 Spring 注入，用于查询和保存用户。 */
    private final UserRepository userRepository;
    /** 由 Spring 注入，用于 BCrypt 密码哈希。 */
    private final BCryptPasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final LoginAttemptRedisRepository loginAttemptRepository;
    private final CaptchaGenerator captchaGenerator;
    private final LoginSecurityProperties loginProperties;

    /** 兼容旧的单元测试构造方式；生产环境由 Spring 使用完整构造器。 */
    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder,
                       TokenService tokenService) {
        this(userRepository, passwordEncoder, tokenService, new LoginAttemptRedisRepository(),
                new CaptchaGenerator(), new LoginSecurityProperties());
    }

    /** 构造方法参数由 Spring 容器自动注入。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder,
                       TokenService tokenService, LoginAttemptRedisRepository loginAttemptRepository,
                       CaptchaGenerator captchaGenerator, LoginSecurityProperties loginProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.loginAttemptRepository = loginAttemptRepository;
        this.captchaGenerator = captchaGenerator;
        this.loginProperties = loginProperties;
    }

    @Transactional
    /**
     * 执行注册业务。
     * request 来自 Controller 的请求体；返回值是数据库生成的用户 ID。
     * @throws AuthBusinessException 邮箱或昵称重复时抛出
     */
    public String register(RegisterRequest request) {
        // 邮箱统一小写，昵称只去除首尾空格，保证查询和唯一约束一致。
        String email = request.email().trim().toLowerCase();
        String nickName = request.nickName().trim();

        if (userRepository.existsByEmail(email)) {
            throw new AuthBusinessException("EMAIL_EXISTS", "该邮箱已注册，请直接登录");
        }
        if (userRepository.existsByNickName(nickName)) {
            throw new AuthBusinessException("NICKNAME_EXISTS", "用户名已被占用，请更换用户名");
        }

        try {
            User user = userRepository.save(new User(
                    email,
                    nickName,
                    passwordEncoder.encode(request.password())
            ));
            return String.valueOf(user.getId());
        } catch (DataIntegrityViolationException exception) {
            throw new AuthBusinessException("REGISTER_CONFLICT", "注册信息已存在，请检查后重试");
        }
    }

    public CaptchaResponse issueCaptcha(String rawUsername) {
        String username = normalizeUsername(rawUsername);
        CaptchaGenerator.Captcha captcha = captchaGenerator.generate();
        loginAttemptRepository.saveCaptcha(username, captcha.code(), loginProperties.getCaptchaTtlSeconds());
        return new CaptchaResponse(captcha.image(), "请输入图片中的4位数字");
    }

    /** 仅供旧调用方使用；HTTP 登录会传入真实客户端 IP。 */
    public LoginResponse login(LoginRequest request) {
        return login(request, "unknown");
    }

    /** 仅允许使用注册时填写的用户名登录，不接受邮箱登录。 */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request, String clientIp) {
        String username = normalizeUsername(request.username());
        LoginAttemptRedisRepository.IpAttemptResult ipResult = loginAttemptRepository.beginIpAttempt(
                clientIp == null || clientIp.isBlank() ? "unknown" : clientIp,
                loginProperties.getIpMaxAttempts(), loginProperties.getIpWindowTtlSeconds());
        if (ipResult.limited()) {
            throw new AuthBusinessException("IP_RATE_LIMITED", "请求过于频繁，请稍后重试", ipResult.retryAfterSeconds());
        }

        LoginAttemptRedisRepository.FailureState state = loginAttemptRepository.getFailureState(username);
        if (state.failures() >= loginProperties.getFailLockThreshold()) {
            long retryAfter = Math.max(1, state.ttlSeconds());
            throw new AuthBusinessException("ACCOUNT_LOCKED", "账号已锁定，请稍后重试", retryAfter);
        }

        if (state.failures() >= loginProperties.getFailCaptchaThreshold()) {
            if (request.captchaCode() == null || request.captchaCode().isBlank()) {
                throw new AuthBusinessException("CAPTCHA_REQUIRED", "请输入验证码");
            }
            if (!loginAttemptRepository.verifyAndConsumeCaptcha(username, request.captchaCode())) {
                loginAttemptRepository.incrementFailure(username, loginProperties.getFailWindowTtlSeconds());
                throw new AuthBusinessException("CAPTCHA_INVALID", "验证码错误，请刷新后重试");
            }
        }

        User user = userRepository.findByNickName(username)
                .orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptRepository.incrementFailure(username, loginProperties.getFailWindowTtlSeconds());
            throw loginFailed();
        }
        loginAttemptRepository.clearFailure(username);
        loginAttemptRepository.clearCaptcha(username);
        return new LoginResponse(tokenService.create(user.getId()), user.getId(), user.getNickName());
    }

    private static String normalizeUsername(String rawUsername) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        if (username.length() < 3 || username.length() > 32) {
            throw new AuthBusinessException("INVALID_PARAM", "用户名长度为3-32位");
        }
        return username;
    }

    private static AuthBusinessException loginFailed() {
        return new AuthBusinessException("LOGIN_FAILED", "用户名或密码错误");
    }
}
        // 先做友好的业务校验；数据库唯一约束仍然是最终并发安全保障。
        // 只把 BCrypt 密文传给 User，原始密码不会写入数据库。
