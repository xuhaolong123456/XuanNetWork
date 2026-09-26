package com.networkdisk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networkdisk.auth.ActiveTokenRepository;
import com.networkdisk.auth.AuthCookie;
import com.networkdisk.auth.TokenService;
import com.networkdisk.auth.UserRepository;
import com.networkdisk.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Restores the signed user ID into Spring Security's request context. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final TokenService tokenService;
    private final ActiveTokenRepository activeTokens;
    private final UserRepository users;
    private final ObjectMapper mapper;

    public JwtAuthenticationFilter(TokenService tokenService, ActiveTokenRepository activeTokens,
                                   UserRepository users, ObjectMapper mapper) {
        this.tokenService = tokenService;
        this.activeTokens = activeTokens;
        this.users = users;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Read auth only from the HttpOnly cookie; the API does not accept a JS-readable bearer token.
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        String token = null;
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie cookie : cookies) {
                if (AuthCookie.NAME.equals(cookie.getName())) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        if (token != null) {
            var claims = tokenService.verify(token);
            if (claims.isPresent()) {
                long userId = claims.get().userId();
                try {
                    // JWT signature/expiry is not enough: also check Redis revocation and current credential version.
                    if (activeTokens.isActive(token, userId)) {
                        users.findById(userId).filter(user -> tokenService.hasCurrentPassword(
                                claims.get(), user.getPasswordHash())).ifPresent(user -> {
                            SecurityContext context = SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
                            SecurityContextHolder.setContext(context);
                        });
                    }
                } catch (DataAccessException exception) {
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    mapper.writeValue(response.getWriter(), Result.failure("AUTH_SERVICE_UNAVAILABLE", "认证服务暂不可用"));
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
