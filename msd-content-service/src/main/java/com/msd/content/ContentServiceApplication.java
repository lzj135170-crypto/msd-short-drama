package com.msd.content;

import com.msd.content.config.CompanionProperties;
import com.msd.content.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * RAG 内容推荐服务启动类。
 *
 * <p>扫描 Spring Boot 组件与 Feign 客户端，并绑定 RAG 与 AI 伴看配置。</p>
 */
@SpringBootApplication
@EnableFeignClients
@EnableConfigurationProperties({RagProperties.class, CompanionProperties.class})
public class ContentServiceApplication {

    /**
     * 启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(ContentServiceApplication.class, args);
    }
}
