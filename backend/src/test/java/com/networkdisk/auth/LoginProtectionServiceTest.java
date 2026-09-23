package com.networkdisk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class LoginProtectionServiceTest {
    @Mock private UserRepository userRepository;
    @Mock private TokenService tokenService;
    @Mock private LoginAttemptRedisRepository loginAttemptRepository;
    @Mock private CaptchaGenerator captchaGenerator;

    private AuthService authService;
    private LoginSecurityProperties properties;
    private BCryptPasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        properties = new LoginSecurityProperties();
        authService = new AuthService(userRepository, passwordEncoder, tokenService,
                loginAttemptRepository, captchaGenerator, properties);
        when(loginAttemptRepository.beginIpAttempt(anyString(), anyInt(), anyInt()))
                .thenReturn(new LoginAttemptRedisRepository.IpAttemptResult(false, 60));
    }

    @Test
    void thirdFailureShouldRequireCaptchaWithoutCheckingPassword() {
        when(loginAttemptRepository.getFailureState("user001"))
                .thenReturn(new LoginAttemptRedisRepository.FailureState(3, 900));

        assertThatThrownBy(() -> authService.login(new LoginRequest("user001", "wrong"), "127.0.0.1"))
                .isInstanceOf(AuthBusinessException.class)
                .extracting(exception -> ((AuthBusinessException) exception).getCode())
                .isEqualTo("CAPTCHA_REQUIRED");
        verify(userRepository, never()).findByNickName(anyString());
        verify(loginAttemptRepository, never()).incrementFailure(anyString(), anyInt());
    }

    @Test
    void invalidCaptchaShouldCountAsFailure() {
        when(loginAttemptRepository.getFailureState("user001"))
                .thenReturn(new LoginAttemptRedisRepository.FailureState(3, 900));
        when(loginAttemptRepository.verifyAndConsumeCaptcha("user001", "0000")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user001", "wrong", "0000"), "127.0.0.1"))
                .isInstanceOf(AuthBusinessException.class)
                .extracting(exception -> ((AuthBusinessException) exception).getCode())
                .isEqualTo("CAPTCHA_INVALID");
        verify(loginAttemptRepository).incrementFailure("user001", 900);
        verify(userRepository, never()).findByNickName(anyString());
    }

    @Test
    void fifthFailureShouldLockAccountAndExposeRemainingTtl() {
        when(loginAttemptRepository.getFailureState("user001"))
                .thenReturn(new LoginAttemptRedisRepository.FailureState(5, 541));

        assertThatThrownBy(() -> authService.login(new LoginRequest("user001", "wrong"), "127.0.0.1"))
                .isInstanceOf(AuthBusinessException.class)
                .satisfies(exception -> {
                    AuthBusinessException authException = (AuthBusinessException) exception;
                    assertThat(authException.getCode()).isEqualTo("ACCOUNT_LOCKED");
                    assertThat(authException.getRetryAfterSeconds()).isEqualTo(541L);
                });
        verify(userRepository, never()).findByNickName(anyString());
    }

    @Test
    void correctPasswordAfterCaptchaShouldClearFailureState() {
        User user = new User("user@example.com", "user001", passwordEncoder.encode("123456"));
        when(loginAttemptRepository.getFailureState("user001"))
                .thenReturn(new LoginAttemptRedisRepository.FailureState(3, 900));
        when(loginAttemptRepository.verifyAndConsumeCaptcha("user001", "2345")).thenReturn(true);
        when(userRepository.findByNickName("user001")).thenReturn(Optional.of(user));
        when(tokenService.create(null)).thenReturn("token");

        LoginResponse response = authService.login(new LoginRequest("user001", "123456", "2345"), "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("token");
        verify(loginAttemptRepository).clearFailure("user001");
        verify(loginAttemptRepository).clearCaptcha("user001");
    }
}
