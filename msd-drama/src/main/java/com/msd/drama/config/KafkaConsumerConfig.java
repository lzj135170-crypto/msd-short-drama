package com.msd.drama.config;

import com.msd.common.event.KafkaTopics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费容错配置：重试 3 次后把消息投递到死信 topic。
 */
@Configuration
public class KafkaConsumerConfig {

    /**
     * 默认错误处理器：每次重试间隔 1 秒，共重试 3 次，仍失败则进入 DLQ。
     */
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // 1. 所有死信统一投递到互动 DLQ topic
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.INTERACTION_DLQ, -1));

        // 2. 固定间隔重试 3 次，之后交给 recoverer
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    /**
     * 批量监听专用容器工厂。
     *
     * <p>批量监听与默认的 RECORD 确认模式不兼容，必须使用 BATCH 模式；
     * 单条消费者继续使用 Spring Boot 自动配置的默认工厂。</p>
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler) {
        // 1. 复用自动配置的消费者工厂，保持反序列化与连接参数一致
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 2. 开启批量监听，整批处理完成后统一提交位点
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

        // 3. 批量模式下按 BatchListenerFailedException 定位单条失败并复用 DLQ
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
