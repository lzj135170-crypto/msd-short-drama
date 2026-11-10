package com.msd.agent.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ConstantDelay;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Redisson 单机客户端配置，连接参数复用 Nacos common.yaml 中的 spring.data.redis.*。
 *
 * <p>仅 Supervisor 使用；audit profile 不创建 Redis 客户端。</p>
 */
@Configuration
@Profile("!audit")
public class RedissonConfig {

    /**
     * 创建 RedissonClient；懒加载，Redis 未就绪时不阻塞服务启动。
     */
    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        // 1. 统一使用单机配置，host/port/password/database 来自公共配置
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                // 路由缓存允许 fail-open，Redis 不可用时快速失败，避免拖住首个请求
                .setConnectTimeout(500)
                .setTimeout(800)
                .setRetryAttempts(1)
                .setRetryDelay(new ConstantDelay(Duration.ofMillis(200)));

        // 2. 空密码不设置，避免发送空 AUTH；Redisson 4.8 推荐在 Config 层设置密码
        if (StringUtils.hasText(password)) {
            config.setPassword(password);
        }
        return Redisson.create(config);
    }
}
