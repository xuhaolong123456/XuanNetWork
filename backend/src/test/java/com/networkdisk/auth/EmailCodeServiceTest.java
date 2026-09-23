package com.networkdisk.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

/** 验证码业务层单元测试：Redis 与 SMTP 都使用 Mock，不连接真实外部服务。 */
@ExtendWith(MockitoExtension.class)
class EmailCodeServiceTest {
    @Mock private EmailCodeRedisRepository redisRepository;
    @Mock private UserRepository userRepository;
    @Mock private JavaMailSender mailSender;
    @Mock private AuthService authService;
    private EmailCodeService service;

    @BeforeEach
    void setUp() {
        service = new EmailCodeService(redisRepository, userRepository, mailSender,
                "sender@qq.com", true, "dGVzdC1obWFjLXNlY3JldA==");
    }

    @Test
    void shouldSendMailThenPersistDigestToRedis() {
        when(redisRepository.beginSend(anyString(), anyString()))
                .thenReturn(EmailCodeRedisRepository.SendReservation.ACCEPTED);
        when(redisRepository.completeSend(anyString(), anyString())).thenReturn(true);

        service.send("User@Example.com", "127.0.0.1");

        // 邮件发送成功后才写入 Redis，避免留下没有投递出去的验证码。
        verify(mailSender).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        verify(redisRepository).completeSend(anyString(), anyString());
    }

    @Test
    void shouldRejectEmailCooldownBeforeSendingMail() {
        when(redisRepository.beginSend(anyString(), anyString()))
                .thenReturn(EmailCodeRedisRepository.SendReservation.COOLDOWN);

        assertThatThrownBy(() -> service.send("user@example.com", "127.0.0.1"))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessage("请在 60 秒后重新获取验证码");
        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
    }

    @Test
    void shouldConsumeCodeOnlyAfterSuccessfulRegistration() {
        RegisterRequest request = new RegisterRequest("user@example.com", "user001", "123456", null, "123456");
        when(redisRepository.beginRegister(anyString(), anyString())).thenReturn(
                new EmailCodeRedisRepository.RegisterReservation(
                        EmailCodeRedisRepository.RegisterStatus.ACCEPTED, 0));
        when(authService.register(request)).thenReturn("1");

        service.register(request, authService);

        verify(authService).register(request);
        verify(redisRepository).completeRegistration(anyString());
    }

    @Test
    void shouldKeepCodeForNicknameRetry() {
        RegisterRequest request = new RegisterRequest("user@example.com", "used-name", "123456", null, "123456");
        when(redisRepository.beginRegister(anyString(), anyString())).thenReturn(
                new EmailCodeRedisRepository.RegisterReservation(
                        EmailCodeRedisRepository.RegisterStatus.ACCEPTED, 0));
        when(authService.register(request)).thenThrow(new AuthBusinessException("NICKNAME_EXISTS", "昵称已被占用"));

        assertThatThrownBy(() -> service.register(request, authService))
                .isInstanceOf(AuthBusinessException.class);

        // 昵称可修改后重试，因此只释放注册锁，保留验证码。
        verify(redisRepository).releaseRegisterLock(anyString());
        verify(redisRepository, never()).invalidateCode(anyString());
    }
}
