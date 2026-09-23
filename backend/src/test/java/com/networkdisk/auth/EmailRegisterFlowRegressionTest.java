package com.networkdisk.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

/** 模拟“发送 → Redis 保存 → 原子校验 → 注册成功 → 删除验证码”的回归流程。 */
class EmailRegisterFlowRegressionTest {
    @Test
    void sendThenRegisterShouldPersistAndConsumeCode() {
        EmailCodeRedisRepository redis = org.mockito.Mockito.mock(EmailCodeRedisRepository.class);
        UserRepository users = org.mockito.Mockito.mock(UserRepository.class);
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        AuthService authService = org.mockito.Mockito.mock(AuthService.class);
        EmailCodeService service = new EmailCodeService(redis, users, mailSender,
                "sender@qq.com", true, "dGVzdC1obWFjLXNlY3JldA==");
        RegisterRequest request = new RegisterRequest("user@example.com", "user001", "123456", null, "123456");

        when(redis.beginSend(anyString(), anyString()))
                .thenReturn(EmailCodeRedisRepository.SendReservation.ACCEPTED);
        when(redis.completeSend(anyString(), anyString())).thenReturn(true);
        when(redis.beginRegister(anyString(), anyString())).thenReturn(
                new EmailCodeRedisRepository.RegisterReservation(
                        EmailCodeRedisRepository.RegisterStatus.ACCEPTED, 0));
        when(authService.register(request)).thenReturn("1");

        service.send("user@example.com", "127.0.0.1");
        service.register(request, authService);

        verify(redis).completeSend(anyString(), anyString());
        verify(authService).register(request);
        verify(redis).completeRegistration(anyString());
    }
}
