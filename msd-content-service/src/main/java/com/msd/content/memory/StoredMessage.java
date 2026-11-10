package com.msd.content.memory;

/**
 * Redis 归档中的一条会话消息。
 *
 * @param type 消息角色：user 或 assistant
 * @param text 消息正文
 */
public record StoredMessage(String type, String text) {

    /** 用户消息。 */
    public static final String TYPE_USER = "user";
    /** 助手消息。 */
    public static final String TYPE_ASSISTANT = "assistant";

    /**
     * 统一空值，避免反序列化后出现 null。
     */
    public StoredMessage {
        type = type == null ? TYPE_USER : type;
        text = text == null ? "" : text;
    }
}
