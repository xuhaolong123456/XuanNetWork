package com.networkdisk.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.networkdisk.auth.ActiveTokenRepository;
import com.networkdisk.auth.AuthController;
import com.networkdisk.auth.AuthCookie;
import com.networkdisk.auth.AuthService;
import com.networkdisk.auth.ChangePasswordRequest;
import com.networkdisk.auth.EmailCodeService;
import com.networkdisk.auth.LoginRequest;
import com.networkdisk.auth.LoginResponse;
import com.networkdisk.auth.TokenService;
import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import com.networkdisk.file.FileBreadcrumb;
import com.networkdisk.file.FileController;
import com.networkdisk.file.FileListResponse;
import com.networkdisk.file.FilePageResponse;
import com.networkdisk.file.FileService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = {SecurityFilterChainRegressionTest.TestController.class, AuthController.class, FileController.class})
@Import({SecurityConfig.class, SecurityFilterChainRegressionTest.TestBeans.class,
        SecurityFilterChainRegressionTest.TestController.class, AuthController.class, FileController.class})
class SecurityFilterChainRegressionTest {
    private static final String DEVICE_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String CSRF_TOKEN = "csrf-test-token";

    @Autowired private MockMvc mvc;
    @Autowired private TokenService tokens;
    @Autowired private ActiveTokenRepository activeTokens;
    @Autowired private UserRepository users;
    @Autowired private AuthService authService;
    @Autowired private FileService fileService;

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<User> user = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        active.set(true);
        user.set(new User("user@example.com", "user001", "hash-one"));
        when(authService.tokenTtlSeconds()).thenReturn(7200L);
        when(activeTokens.isActive(anyString(), eq(42L))).thenAnswer(invocation -> active.get());
        when(users.findById(42L)).thenAnswer(invocation -> Optional.of(user.get()));
    }

    @Test
    void publicLoginRouteSetsHttpOnlyCookieAndDoesNotReturnTokenInJson() throws Exception {
        when(authService.login(any(LoginRequest.class), anyString(), anyString()))
                .thenReturn(new LoginResponse("issued-token", 42L, "user001"));

        mvc.perform(post("/api/v1/auth/login").with(csrfRequest(null))
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"user001\",\"password\":\"123456\",\"captchaCode\":\"2345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.username").value("user001"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString(
                        AuthCookie.NAME + "=issued-token;")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")));
    }

    @Test
    void protectedRouteRejectsMissingMalformedTamperedAndExpiredCookies() throws Exception {
        mvc.perform(get("/api/v1/private"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, "malformed")))
                .andExpect(status().isUnauthorized());

        String token = tokens.create(42L, "hash-one");
        mvc.perform(get("/api/v1/private")
                        .cookie(new MockCookie(AuthCookie.NAME, token.substring(0, token.length() - 2) + "AA")))
                .andExpect(status().isUnauthorized());
        TokenService expiredTokens = new TokenService(TestBeans.secret(), -1);
        mvc.perform(get("/api/v1/private")
                        .cookie(new MockCookie(AuthCookie.NAME, expiredTokens.create(42L, "hash-one"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRouteReadsAuthenticatedUserFromCookieAndIgnoresBearerHeader() throws Exception {
        String token = tokens.create(42L, "hash-one");
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42));
        mvc.perform(get("/api/v1/private").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fileListRequiresCookieAuthenticationAndUsesPrincipalAsOwner() throws Exception {
        mvc.perform(get("/api/v1/files"))
                .andExpect(status().isUnauthorized());

        String token = tokens.create(42L, "hash-one");
        when(fileService.list(42L, 13L, 0, 50)).thenReturn(new FileListResponse(
                null, java.util.List.of(new FileBreadcrumb(null, "我的文件")), java.util.List.of(),
                new FilePageResponse(0, 50, 0, 0)));
        mvc.perform(get("/api/v1/files").param("parentId", "13")
                        .cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.breadcrumbs[0].name").value("我的文件"));
        verify(fileService).list(42L, 13L, 0, 50);
    }

    @Test
    void loginLogoutThenLoginAgainRevokesOldCookieAndKeepsNewCookieValid() throws Exception {
        Set<String> activeSessionTokens = new HashSet<>();
        when(activeTokens.isActive(anyString(), eq(42L))).thenAnswer(invocation ->
                activeSessionTokens.contains(invocation.getArgument(0, String.class)));
        when(authService.login(any(LoginRequest.class), anyString(), anyString())).thenAnswer(invocation -> {
            String token = tokens.create(42L, "hash-one");
            activeSessionTokens.add(token);
            return new LoginResponse(token, 42L, "user001");
        });
        doAnswer(invocation -> {
            activeSessionTokens.remove(invocation.getArgument(0, String.class));
            return null;
        }).when(authService).logout(anyString());

        String firstToken = loginAndGetCookieToken(CSRF_TOKEN);
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, firstToken)))
                .andExpect(status().isOk());

        MvcResult logoutResult = mvc.perform(post("/api/v1/auth/logout").with(csrfRequest(firstToken)))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(logoutResult.getResponse().getHeaders("Set-Cookie"))
                .anyMatch(value -> value.contains(AuthCookie.NAME + "=; Path=/; Max-Age=0"))
                .anyMatch(value -> value.contains("XSRF-TOKEN=; Path=/; Max-Age=0"));
        verify(authService).logout(firstToken);
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, firstToken)))
                .andExpect(status().isUnauthorized());

        MvcResult staleCsrfLogin = mvc.perform(post("/api/v1/auth/login").with(csrfHeaderOnlyRequest(CSRF_TOKEN))
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andExpect(status().isForbidden())
                .andReturn();
        String refreshedCsrfToken = staleCsrfLogin.getResponse().getCookie("XSRF-TOKEN").getValue();
        String secondToken = loginAndGetCookieToken(refreshedCsrfToken);
        org.assertj.core.api.Assertions.assertThat(secondToken).isNotEqualTo(firstToken);
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, secondToken)))
                .andExpect(status().isOk());
    }

    @Test
    void revokedTokenIsRejectedOnNextRequest() throws Exception {
        String token = tokens.create(42L, "hash-one");
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isOk());
        doAnswer(invocation -> { active.set(false); return null; })
                .when(authService).logout(token);
        mvc.perform(post("/api/v1/auth/logout").with(csrfRequest(token)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeInvalidatesEarlierTokenEvenWhenRedisEntryStillExists() throws Exception {
        String token = tokens.create(42L, "hash-one");
        doAnswer(invocation -> { user.get().changePasswordHash("hash-two"); return null; })
                .when(authService).changePassword(eq(42L), any(ChangePasswordRequest.class));
        mvc.perform(post("/api/v1/auth/change-password").with(csrfRequest(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redisFailureFailsClosedInsteadOfAcceptingSignedCookie() throws Exception {
        String token = tokens.create(42L, "hash-one");
        when(activeTokens.isActive(token, 42L))
                .thenThrow(new RedisConnectionFailureException("unavailable"));

        mvc.perform(get("/api/v1/private").cookie(new MockCookie(AuthCookie.NAME, token)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_SERVICE_UNAVAILABLE"));
    }

    private String loginAndGetCookieToken(String csrfToken) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrfRequest(null, csrfToken))
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(AuthCookie.NAME + "="))
                .map(value -> value.substring((AuthCookie.NAME + "=").length(), value.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Login did not set the authentication cookie"));
    }

    private RequestPostProcessor csrfRequest(String authToken) {
        return csrfRequest(authToken, CSRF_TOKEN);
    }

    private RequestPostProcessor csrfRequest(String authToken, String csrfToken) {
        return request -> {
            MockCookie csrfCookie = new MockCookie("XSRF-TOKEN", csrfToken);
            if (authToken == null) request.setCookies(csrfCookie);
            else request.setCookies(csrfCookie, new MockCookie(AuthCookie.NAME, authToken));
            request.addHeader("X-XSRF-TOKEN", csrfToken);
            return request;
        };
    }

    private RequestPostProcessor csrfHeaderOnlyRequest(String csrfToken) {
        return request -> {
            request.addHeader("X-XSRF-TOKEN", csrfToken);
            return request;
        };
    }

    private String loginBody() {
        return "{\"username\":\"user001\",\"password\":\"123456\",\"captchaCode\":\"2345\"}";
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestBeans {
        static String secret() {
            return Base64.getEncoder().encodeToString(
                    "regression-test-key-longer-than-32-bytes".getBytes(StandardCharsets.UTF_8));
        }

        @Bean
        TokenService tokenService() {
            return new TokenService(secret(), 3600);
        }

        @Bean
        ActiveTokenRepository activeTokenRepository() { return mock(ActiveTokenRepository.class); }

        @Bean
        UserRepository userRepository() { return mock(UserRepository.class); }

        @Bean
        AuthService authService() { return mock(AuthService.class); }

        @Bean
        EmailCodeService emailCodeService() { return mock(EmailCodeService.class); }

        @Bean
        FileService fileService() { return mock(FileService.class); }
    }

    @RestController
    static class TestController {
        @GetMapping(value = "/api/v1/private", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Long> privateRoute(Authentication authentication) {
            return Map.of("userId", (Long) authentication.getPrincipal());
        }
    }
}
