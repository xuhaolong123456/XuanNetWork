package com.networkdisk.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** 使用 Mock Repository 隔离数据库，专门测试 AuthService 的注册业务。 */
class AuthServiceTest {

    @Mock
    /** 假的数据库访问对象，不会连接真实 MySQL。 */
    private UserRepository userRepository;
    @Mock
    private TokenService tokenService;

    /** 被测试的业务对象。 */
    private AuthService authService;
    /** 测试中使用的真实 BCrypt 编码器。 */
    private BCryptPasswordEncoder passwordEncoder;

    @BeforeEach
    /** 每个测试前重新创建 Service，保证测试相互独立。 */
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(userRepository, passwordEncoder, tokenService);
    }

    @Test
    /** 验证正常注册会标准化邮箱、加密密码并调用 save。 */
    void validRegisterShouldNormalizeEmailHashPasswordAndSave() {
        RegisterRequest request = new RegisterRequest(
                " User@Example.COM ", " user001 ", "123456", null, null);
        User savedUser = new User("user@example.com", "user001", "hash");
        // 预设数据库中邮箱和昵称都不存在。
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(userRepository.existsByNickName("user001")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // 参数模拟 Controller 传入的注册请求。
        String userId = authService.register(request);

        assertThat(userId).isNotNull();
        // 捕获传给 save 的 User，检查实际写入数据库前的数据。
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getPasswordHash()).isNotEqualTo("123456");
        assertThat(passwordEncoder.matches("123456", saved.getPasswordHash())).isTrue();
    }

    @Test
    /** 验证邮箱重复时抛出业务异常，并且绝不能保存用户。 */
    void duplicateEmailShouldFailAndNotSave() {
        RegisterRequest request = new RegisterRequest(
                "user@example.com", "user001", "123456", null, null);
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessage("该邮箱已注册，请直接登录");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    /** 验证昵称重复时抛出业务异常，并且绝不能保存用户。 */
    void duplicateNickNameShouldFailAndNotSave() {
        RegisterRequest request = new RegisterRequest(
                "user@example.com", "user001", "123456", null, null);
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(userRepository.existsByNickName("user001")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessage("用户名已被占用，请更换用户名");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginShouldUseUsernameAndReturnToken() {
        User user = new User("user@example.com", "user001", passwordEncoder.encode("123456"));
        when(userRepository.findByNickName("user001")).thenReturn(java.util.Optional.of(user));
        when(tokenService.create(user.getId())).thenReturn("access-token");

        LoginResponse response = authService.login(new LoginRequest(" user001 ", "123456"));

        verify(userRepository).findByNickName("user001");
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.username()).isEqualTo("user001");
    }

    @Test
    void unknownUsernameShouldReturnGenericLoginFailure() {
        when(userRepository.findByNickName("missing")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("missing", "123456")))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessage("用户名或密码错误");
        verify(tokenService, never()).create(any());
    }

    @Test
    void wrongPasswordShouldReturnGenericLoginFailure() {
        User user = new User("user@example.com", "user001", passwordEncoder.encode("correct-password"));
        when(userRepository.findByNickName("user001")).thenReturn(java.util.Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("user001", "wrong-password")))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessage("用户名或密码错误");
        verify(tokenService, never()).create(any());
    }
}
