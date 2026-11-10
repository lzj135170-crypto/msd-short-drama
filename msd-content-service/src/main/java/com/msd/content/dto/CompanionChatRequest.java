package com.msd.content.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * AI 伴看聊天请求。
 *
 * @param message    用户消息，1-1000 字
 * @param sessionId  客户端会话标识，只允许字母、数字、下划线和短横线
 * @param episodeId  当前正在观看的剧集 ID
 * @param positionMs 播放器当前位置（毫秒），用于字幕防剧透过滤；为空时只返回之前剧集
 */
public record CompanionChatRequest(
        @NotBlank @Size(max = 1000) String message,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sessionId,
        @NotNull @Min(1) Long episodeId,
        @Min(value = 0, message = "positionMs 不能为负数") Long positionMs
) {

    /**
     * 兼容旧调用方，不传播放位置。
     */
    public CompanionChatRequest(String message, String sessionId, Long episodeId) {
        this(message, sessionId, episodeId, null);
    }
}
