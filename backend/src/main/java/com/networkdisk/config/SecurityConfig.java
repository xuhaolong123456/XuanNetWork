package com.networkdisk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networkdisk.auth.ActiveTokenRepository;
import com.networkdisk.auth.TokenService;
import com.networkdisk.auth.UserRepository;
import com.networkdisk.common.Result;
import com.networkdisk.file.FileOperationError;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenService tokenService,
                                            ActiveTokenRepository activeTokens, UserRepository users,
                                            ObjectMapper mapper,
                                            @Value("${app.auth.cookie-secure:false}") boolean authCookieSecure)
            throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(tokenService, activeTokens, users, mapper);
        return http
                // 浏览器会自动携带认证 Cookie，因此对非安全方法保留 CSRF 校验。
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository(authCookieSecure))
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/", "/api/v1/auth/captcha", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register",
                                "/api/v1/auth/email-code").permitAll()
                        .requestMatchers("/error").permitAll()
                        // 放行 OpenAPI 文档与 Swagger UI，供开发期浏览接口定义。
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, error) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    mapper.writeValue(response.getWriter(), FileOperationError.applies(request)
                            ? FileOperationError.of(401, "请先登录或重新登录")
                            : Result.failure("UNAUTHORIZED", "请先登录或重新登录"));
                }).accessDeniedHandler((request, response, error) -> {
                    // CSRF 过滤器执行时请求尚未包装 Principal，从安全上下文判断登录状态。
                    var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    int status = FileOperationError.applies(request) && authentication == null ? 401 : 403;
                    response.setStatus(status);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    mapper.writeValue(response.getWriter(), FileOperationError.applies(request)
                            ? FileOperationError.of(status, status == 401 ? "请先登录或重新登录" : "请求校验失败，请刷新后重试")
                            : Result.failure("FORBIDDEN", "请求校验失败，请刷新后重试"));
                }))
                .addFilterBefore(jwtFilter, CsrfFilter.class)
                .build();
    }

    private CookieCsrfTokenRepository csrfTokenRepository(boolean secure) {
        // 前端需要读取独立的 CSRF Cookie，并在 X-XSRF-TOKEN 请求头中回传其值。
        // 该 Cookie 有意允许脚本读取；认证 Cookie 仍保持 HttpOnly。
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(secure));
        return repository;
    }
}
