package com.networkdisk.auth;

/** 可预期的业务错误，例如邮箱重复、昵称重复。 */
public class AuthBusinessException extends RuntimeException {

    /** 稳定错误码，前端应根据它处理业务，而不是依赖异常堆栈。 */
    private final String code;
    private final Long retryAfterSeconds;

    /** @param code 返回给前端的业务错误码 @param message 面向用户的提示信息 */
    public AuthBusinessException(String code, String message) {
        this(code, message, null);
    }

    public AuthBusinessException(String code, String message, Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getCode() {
        return code;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
