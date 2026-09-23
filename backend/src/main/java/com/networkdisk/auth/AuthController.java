package com.networkdisk.auth;

import com.networkdisk.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/v1/auth")
/** 认证模块的 HTTP 接口层，负责接收请求并调用认证业务。 */
public class AuthController {

    /** Spring 自动注入的认证业务 Service。 */
    private final AuthService authService;
    /** 负责验证码发送、过期和一次性校验。 */
    private final EmailCodeService emailCodeService;
    @Value("${app.forwarded-ip.trusted:false}")
    private boolean trustedForwardedIp;

    /** 构造方法参数由 Spring 容器从已注册的 AuthService Bean 中提供。 */
    public AuthController(AuthService authService, EmailCodeService emailCodeService) {
        this.authService = authService;
        this.emailCodeService = emailCodeService;
    }

    @PostMapping("/email-code")
    /** 只接收邮箱，验证码必须由后端生成。 */
    public Result<String> sendEmailCode(@Valid @RequestBody EmailCodeRequest request, HttpServletRequest servletRequest) {
        emailCodeService.send(request.email(), clientIp(servletRequest));
        return Result.success("验证码已发送");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * 注册接口。
     * request 来自前端 JSON 请求体，@Valid 会先执行 DTO 参数校验。
     * 返回值由 Spring 序列化为统一 Result JSON。
     */
    public Result<String> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(emailCodeService.register(request, authService));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return Result.success(authService.login(request, clientIp(servletRequest)));
    }

    /** 兼容旧的控制层单元测试和内部调用；HTTP 请求走上面的真实 IP 重载。 */
    public Result<LoginResponse> login(LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @GetMapping("/captcha")
    public Result<CaptchaResponse> captcha(@RequestParam String username) {
        return Result.success(authService.issueCaptcha(username));
    }

    public record EmailCodeRequest(@NotBlank @Email String email) {}

    /** 本地使用连接来源地址；生产环境只应由受信任网关注入 X-Forwarded-For。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (trustedForwardedIp && forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
