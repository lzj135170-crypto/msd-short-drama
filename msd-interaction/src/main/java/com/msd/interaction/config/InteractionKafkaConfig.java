package com.msd.interaction.config;

import com.msd.common.event.KafkaTopics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * interaction 评论计数消费者容错：重试 3 次后投递到统一 DLQ。
 */
@Configuration
@EnableKafka
public class InteractionKafkaConfig {

    /**
     * 评论计数消费错误处理器。
     */
    @Bean
    public DefaultErrorHandler interactionErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate) {
        // 1. 所有死信统一写入互动 DLQ
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.INTERACTION_DLQ, -1));

        // 2. 固定间隔重试 3 次，之后进入 DLQ
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
