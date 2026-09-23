package com.networkdisk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
/** 密码相关 Bean 配置类。 */
public class PasswordConfig {

    /** 创建 BCrypt 编码器，供 AuthService 注入使用。 */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
