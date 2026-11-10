package com.msd.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 订单与积分服务启动类。
 */
@SpringBootApplication
@MapperScan("com.msd.order.mapper")
@EnableFeignClients
public class OrderApplication {

    /**
     * 启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
