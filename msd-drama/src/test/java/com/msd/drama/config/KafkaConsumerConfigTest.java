package com.msd.drama.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 批量监听容器工厂测试：防止批量监听误用 RECORD 确认模式导致启动失败。
 */
class KafkaConsumerConfigTest {

    /**
     * 批量工厂必须开启批量监听并使用 BATCH 确认模式。
     */
    @Test
    void batchFactoryUsesBatchListenerAndBatchAckMode() {
        // 1. 构造配置和可注入的消费者工厂
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, String> consumerFactory = mock(ConsumerFactory.class);

        // 2. 创建批量容器工厂
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.batchKafkaListenerContainerFactory(consumerFactory, new DefaultErrorHandler());

        // 3. 批量监听与 BATCH 确认模式必须同时生效
        assertThat(factory.isBatchListener()).isTrue();
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
    }
}
