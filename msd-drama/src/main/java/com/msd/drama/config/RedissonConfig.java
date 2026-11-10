package com.msd.drama.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

/**
 * Redisson 单机客户端配置，连接参数复用 Nacos common.yaml 中的 spring.data.redis.*。
 *
 * <p>Bean 懒加载：Redis 未启动时服务仍能启动，首次缓存调用失败后走 DB 降级。</p>
 */
@Configuration
public class RedissonConfig {

    /**
     * 创建 RedissonClient；@Lazy 保证构造时不建立连接。
     */
    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        // 1. 单机模式连接参数，按小规模缓存场景设置超时和连接池
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectTimeout(2_000)
                .setTimeout(2_000)
                .setRetryAttempts(2)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8);

        // 2. 仅在配置了密码时设置，兼容无鉴权的本地 Redis
        if (StringUtils.hasText(password)) {
            config.setPassword(password);
        }
        // 3. 创建客户端；配合 @Lazy 延迟到首次缓存调用
        return Redisson.create(config);
    }
}
