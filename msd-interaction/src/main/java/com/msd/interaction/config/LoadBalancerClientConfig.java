package com.msd.interaction.config;

import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Configuration;

/**
 * 将自定义负载均衡配置应用到所有 Feign 客户端。
 */
@Configuration
@LoadBalancerClients(defaultConfiguration = RandomLoadBalancerConfig.class)
public class LoadBalancerClientConfig {
}
