package com.networkdisk.common;

import com.networkdisk.auth.AuthBusinessException;
import com.networkdisk.file.FileBusinessException;
import com.networkdisk.file.FileOperationError;
import com.networkdisk.file.ChunkUploadResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.data.redis.RedisConnectionFailureException;

@RestControllerAdvice
/** 统一捕获 Controller 和 Service 抛出的业务异常，转换为 JSON 响应。 */
public class GlobalExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 处理注册业务异常，例如邮箱重复或昵称重复。 */
    @ExceptionHandler(AuthBusinessException.class)
    public ResponseEntity<Result<Void>> handleAuthBusiness(AuthBusinessException exception) {
        var builder = ResponseEntity.status(statusFor(exception.getCode()));
        if (exception.getRetryAfterSeconds() != null) {
            builder.header("Retry-After", String.valueOf(exception.getRetryAfterSeconds()));
        }
        return builder.body(Result.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(FileBusinessException.class)
    public ResponseEntity<?> handleFileBusiness(FileBusinessException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatus());
        if (ChunkUploadResult.applies(request)) {
            int code;
            try { code = Integer.parseInt(exception.getCode()); }
            catch (NumberFormatException ignored) { code = status.value(); }
            return ResponseEntity.status(status).body(ChunkUploadResult.failure(code, exception.getMessage()));
        }
        if (FileOperationError.applies(request)) {
            return ResponseEntity.status(status).body(FileOperationError.of(status.value(), exception.getMessage()));
        }
        return ResponseEntity.status(status).body(Result.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleUploadTooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        if (ChunkUploadResult.applies(request)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ChunkUploadResult.failure(41301, "分片大小超限"));
        }
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.failure("FILE_TOO_LARGE", "文件大小超过允许上限"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数无效");
        if (FileOperationError.applies(request)) return FileOperationError.of(400, message);
        return Result.failure("INVALID_PARAM", message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleMalformedRequest(Exception exception, HttpServletRequest request) {
        if (ChunkUploadResult.applies(request)) return ChunkUploadResult.failure(400, "请求参数格式错误");
        return FileOperationError.applies(request) ? FileOperationError.of(400, "请求参数格式错误")
                : Result.failure("INVALID_PARAM", "请求参数格式错误");
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    public ResponseEntity<?> handleDatabaseFailure(RuntimeException exception,
                                                    HttpServletRequest request) {
        // 记录详细故障，但不把数据库结构或连接信息暴露给前端。
        log.error("数据库操作失败", exception);
        if (ChunkUploadResult.isListParts(request)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ChunkUploadResult.failure(503, "服务暂时不可用"));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(FileOperationError.applies(request)
                ? FileOperationError.of(500, "操作失败，请稍后重试")
                : Result.failure("INTERNAL_ERROR", "操作失败，请稍后重试"));
    }

    /** Redis 运行中断开时，验证码接口明确返回 503，不回退到本地内存。 */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<?> handleRedisUnavailable(RedisConnectionFailureException exception, HttpServletRequest request) {
        if (ChunkUploadResult.isListParts(request)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ChunkUploadResult.failure(503, "服务暂时不可用"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure("REDIS_UNAVAILABLE", "验证码服务暂不可用，请稍后重试"));
    }

    private HttpStatus statusFor(String code) {
        return switch (code) {
            case "LOGIN_FAILED", "UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
            case "CAPTCHA_REQUIRED" -> HttpStatus.FORBIDDEN;
            case "CAPTCHA_INVALID" -> HttpStatus.BAD_REQUEST;
            case "ACCOUNT_LOCKED" -> HttpStatus.LOCKED;
            case "EMAIL_COOLDOWN", "EMAIL_DAILY_LIMITED", "IP_RATE_LIMITED", "DEVICE_RATE_LIMITED",
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
