package com.msd.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * AI Agent 服务启动类。
 */
@SpringBootApplication
@EnableFeignClients
public class AgentServiceApplication {

    /**
     * 启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
