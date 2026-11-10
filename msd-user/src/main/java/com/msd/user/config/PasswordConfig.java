package com.msd.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码编码配置。
 */
@Configuration
public class PasswordConfig {

    /**
     * BCrypt 编码器，兼容种子数据里的 $2b$ 哈希。
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
