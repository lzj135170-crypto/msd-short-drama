package com.msd.interaction.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RandomLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 随机负载均衡配置，msd.loadbalancer.strategy=random 时生效。
 * 不加 @Configuration，由 @LoadBalancerClients 引入负载均衡子上下文。
 */
public class RandomLoadBalancerConfig {

    /**
     * 用 RandomLoadBalancer 覆盖默认的 RoundRobinLoadBalancer。
     */
    @Bean
    @ConditionalOnProperty(name = "msd.loadbalancer.strategy", havingValue = "random")
    public ReactorLoadBalancer<ServiceInstance> randomLoadBalancer(
            Environment environment, LoadBalancerClientFactory factory) {
        // 1. 取出当前客户端对应的服务名，如 msd-user
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        // 2. 从服务的实例列表供应商中随机选择一个实例
        return new RandomLoadBalancer(
                factory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
    }
}
