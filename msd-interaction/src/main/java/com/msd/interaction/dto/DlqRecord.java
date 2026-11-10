package com.msd.interaction.dto;

/**
 * 从 DLQ topic 读取到的一条原始消息。
 *
 * @param partition 分区号
 * @param offset    消息位点
 * @param key       消息分区键
 * @param value     消息体，超长时会被截断
 * @param timestamp 消息时间戳
 */
public record DlqRecord(int partition, long offset, String key, String value, long timestamp) {
}
