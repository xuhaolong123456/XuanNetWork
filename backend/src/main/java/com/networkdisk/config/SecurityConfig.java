package com.networkdisk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networkdisk.auth.ActiveTokenRepository;
import com.networkdisk.auth.TokenService;
import com.networkdisk.auth.UserRepository;
import com.networkdisk.common.Result;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
                // Auth is carried by an automatic browser cookie, so keep CSRF checks enabled for unsafe methods.
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
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, error) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    mapper.writeValue(response.getWriter(), Result.failure("UNAUTHORIZED", "请先登录或重新登录"));
                }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private CookieCsrfTokenRepository csrfTokenRepository(boolean secure) {
        // The frontend must read this separate CSRF cookie and echo its value in X-XSRF-TOKEN.
        // It is intentionally script-readable; the authentication cookie remains HttpOnly.
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(secure));
        return repository;
    }
}
