package com.msd.interaction;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.Ordered;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 互动服务启动类。
 */
@SpringBootApplication
@MapperScan("com.msd.interaction.mapper")
@EnableFeignClients  //打通微服务间的通信
@EnableScheduling    //驱动应用内部的定时任务
@EnableAsync         //评论 AI 预审异步执行
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1) // 死锁重试包在事务之外
public class InteractionApplication {

    /**
     * 启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(InteractionApplication.class, args);
    }
}
