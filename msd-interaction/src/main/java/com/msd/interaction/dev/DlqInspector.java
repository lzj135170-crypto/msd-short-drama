package com.msd.interaction.dev;

import com.msd.common.event.KafkaTopics;
import com.msd.interaction.dto.DlqInspection;
import com.msd.interaction.dto.DlqRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * DLQ 只读巡检器：临时消费者从每个分区尾部读取最近消息，不提交位点。
 * 仅在 dev 且开启故障接口时加载。
 */
@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "msd.dev.faults", name = "enabled", havingValue = "true")
public class DlqInspector {

    /** 单条消息体的最大展示长度，超出部分截断。 */
    private static final int MAX_VALUE_LENGTH = 4000;

    // Kafka 集群地址
    private final String bootstrapServers;

    /**
     * 注入 Kafka 地址。
     */
    public DlqInspector(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * 读取 DLQ 每个分区末尾的若干条消息。
     */
    public DlqInspection inspect(int requestedLimit) {
        // 1. 限制单次读取数量
        int limit = Math.max(1, Math.min(requestedLimit, 50));

        // 2. 构造临时消费者配置：随机 group + assign，不参与消费组协调
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "msd-dlq-inspector-" + UUID.randomUUID());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "msd-dlq-inspector");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, limit);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            // 3. 查询分区；topic 不存在时直接返回提示
            List<TopicPartition> partitions = consumer
                    .partitionsFor(KafkaTopics.INTERACTION_DLQ, Duration.ofSeconds(3))
                    .stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            if (partitions.isEmpty()) {
                return new DlqInspection(KafkaTopics.INTERACTION_DLQ, limit, false,
                        List.of(), "DLQ topic 不存在或无分区，请先执行 create-topics");
            }

            // 4. 手动分配分区，并把每个分区定位到末尾 limit 条之前
            consumer.assign(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions, Duration.ofSeconds(2));
            Map<TopicPartition, Long> beginOffsets = consumer.beginningOffsets(partitions, Duration.ofSeconds(2));
            long remaining = 0L;
            for (TopicPartition partition : partitions) {
                long end = endOffsets.getOrDefault(partition, 0L);
                long begin = beginOffsets.getOrDefault(partition, 0L);
                long start = Math.max(begin, end - limit);
                consumer.seek(partition, start);
                remaining += end - start;
            }

            // 5. 短暂 poll，最多读取 limit 条
            List<DlqRecord> messages = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (messages.size() < limit && remaining > 0 && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> record : records) {
                    messages.add(new DlqRecord(
                            record.partition(),
                            record.offset(),
                            record.key(),
                            truncate(record.value()),
                            record.timestamp()));
                    remaining--;
                    if (messages.size() >= limit) {
                        break;
                    }
                }
            }

            // 6. 按分区和 offset 排序后返回
            messages.sort(Comparator.comparingInt(DlqRecord::partition)
                    .thenComparingLong(DlqRecord::offset));
            return new DlqInspection(KafkaTopics.INTERACTION_DLQ, limit, true, messages,
                    "只读检查：随机 group + assign，不提交位点；最多读取每个分区尾部 " + limit + " 条");
        } catch (Exception ex) {
            return new DlqInspection(KafkaTopics.INTERACTION_DLQ, limit, false,
                    List.of(), "读取 DLQ 失败: " + ex.getMessage());
        }
    }

    /**
     * 截断过长的消息体，避免页面渲染过大响应。
     */
    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_VALUE_LENGTH
                ? value : value.substring(0, MAX_VALUE_LENGTH) + "...";
    }
}
