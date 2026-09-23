package com.networkdisk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.networkdisk.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Controller 回归测试：接口层只提取请求数据，业务规则交给验证码服务。 */
@ExtendWith(MockitoExtension.class)
class AuthControllerRegressionTest {
    @Mock private AuthService authService;
    @Mock private EmailCodeService emailCodeService;
    @Mock private HttpServletRequest servletRequest;
    @InjectMocks private AuthController controller;

    @Test
    void registerShouldDelegateAtomicVerificationAndRegistrationToService() {
        RegisterRequest request = new RegisterRequest(
                "user@example.com", "user001", "123456", null, "123456");
        when(emailCodeService.register(request, authService)).thenReturn("1");

        Result<String> result = controller.register(request);

        verify(emailCodeService).register(request, authService);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo("1");
    }

    @Test
    void sendEmailCodeShouldPassClientIpToService() {
        when(servletRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        Result<String> result = controller.sendEmailCode(
                new AuthController.EmailCodeRequest("user@example.com"), servletRequest);

        verify(emailCodeService).send("user@example.com", "127.0.0.1");
        assertThat(result.success()).isTrue();
    }

    @Test
    void loginShouldDelegateUsernameAndPasswordToService() {
        LoginRequest request = new LoginRequest("user001", "123456");
        LoginResponse response = new LoginResponse("token", 1L, "user001");
        when(authService.login(request)).thenReturn(response);

        Result<LoginResponse> result = controller.login(request);

        verify(authService).login(request);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(response);
    }
}
