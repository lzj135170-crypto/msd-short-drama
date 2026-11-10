package com.msd.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户与认证服务启动类。
 */
@SpringBootApplication
@MapperScan("com.msd.user.mapper")
public class UserApplication {

    /**
     * 启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
