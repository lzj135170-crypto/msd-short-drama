package com.msd.interaction.dto;

/**
 * 非法事件投递结果。
 *
 * @param topic 投递的 Kafka Topic
 * @param key   本次消息的分区键
 * @param hint  投递后的操作提示
 */
public record DlqInjectResult(String topic, String key, String hint) {
}
