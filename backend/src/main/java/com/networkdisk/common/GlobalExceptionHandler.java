package com.networkdisk.common;

import com.networkdisk.auth.AuthBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.data.redis.RedisConnectionFailureException;

@RestControllerAdvice
/** 统一捕获 Controller 和 Service 抛出的业务异常，转换为 JSON 响应。 */
public class GlobalExceptionHandler {

    /** 处理注册业务异常，例如邮箱重复或昵称重复。 */
    @ExceptionHandler(AuthBusinessException.class)
    public ResponseEntity<Result<Void>> handleAuthBusiness(AuthBusinessException exception) {
        var builder = ResponseEntity.status(statusFor(exception.getCode()));
        if (exception.getRetryAfterSeconds() != null) {
            builder.header("Retry-After", String.valueOf(exception.getRetryAfterSeconds()));
        }
        return builder.body(Result.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请检查注册信息");
        return Result.failure("INVALID_PARAM", message);
    }

    /** Redis 运行中断开时，验证码接口明确返回 503，不回退到本地内存。 */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Result<Void>> handleRedisUnavailable(RedisConnectionFailureException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure("REDIS_UNAVAILABLE", "验证码服务暂不可用，请稍后重试"));
    }

    private HttpStatus statusFor(String code) {
        return switch (code) {
            case "LOGIN_FAILED" -> HttpStatus.UNAUTHORIZED;
            case "CAPTCHA_REQUIRED" -> HttpStatus.FORBIDDEN;
            case "CAPTCHA_INVALID" -> HttpStatus.BAD_REQUEST;
            case "ACCOUNT_LOCKED" -> HttpStatus.LOCKED;
            case "EMAIL_COOLDOWN", "EMAIL_DAILY_LIMITED", "IP_RATE_LIMITED",
                    "SEND_IN_PROGRESS", "REGISTER_IN_PROGRESS" -> HttpStatus.TOO_MANY_REQUESTS;
            case "REDIS_UNAVAILABLE", "MAIL_SEND_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
    /**
     * 处理 @Valid 触发的参数校验异常。
     * 异常中的字段错误来自 RegisterRequest 上的 @NotBlank、@Email、@Size 等注解。
     */
