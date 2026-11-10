package com.msd.agent.dto;

/**
 * Agent 回复中的可点击短剧卡片。
 *
 * @param dramaId  短剧 ID
 * @param title    标题
 * @param subtitle 副标题
 * @param snippet  简介摘要
 */
public record AgentDramaCard(
        Long dramaId,
        String title,
        String subtitle,
        String snippet
) {

    /**
     * 统一空值。
     */
    public AgentDramaCard {
        title = title == null ? "" : title;
        subtitle = subtitle == null ? "" : subtitle;
        snippet = snippet == null ? "" : snippet;
    }
}
