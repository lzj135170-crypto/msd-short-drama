package com.msd.drama;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 短剧服务启动类。
 */
@SpringBootApplication
// 去 com.msd.drama.mapper找出所有的 Mapper 接口，并把它们自动注册为 Spring 容器里的 Bean
@MapperScan("com.msd.drama.mapper")
public class DramaApplication {

    /**
     * 启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(DramaApplication.class, args);
    }
}
