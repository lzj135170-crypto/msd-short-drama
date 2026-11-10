package com.msd.interaction.dto;

import java.util.List;

/**
 * DLQ 只读巡检结果。
 *
 * @param topic      巡检的 Kafka Topic
 * @param limit      本次读取上限
 * @param topicReady topic 是否存在且可读
 * @param messages   读取到的消息列表
 * @param note       巡检说明或失败原因
 */
public record DlqInspection(String topic,
                            int limit,
                            boolean topicReady,
                            List<DlqRecord> messages,
                            String note) {
}
