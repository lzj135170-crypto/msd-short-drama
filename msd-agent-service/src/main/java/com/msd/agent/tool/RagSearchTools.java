package com.msd.agent.tool;

import com.msd.agent.client.AgentRagClient;
import com.msd.agent.client.RagSearchRequest;
import com.msd.agent.client.dto.AgentRagViews.RagRecommendView;
import com.msd.agent.client.dto.AgentRagViews.RagSourceView;
import com.msd.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 自然语言找剧工具。
 *
 * <p>把用户 query 和槽位拼成 RAG 检索需求，返回回答和标准 DRAMA_CARD 行；
 * 只读、不直接播放，卡片由前端解析后跳转。</p>
 */
@Component
public class RagSearchTools {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(RagSearchTools.class);
    /** 拼接后 query 最大长度，与 content 接口限制一致。 */
    private static final int MAX_QUERY_CHARS = 200;
    /** 固定返回 3 部，搜索结果更聚焦。 */
    private static final int DEFAULT_TOP_K = 3;
    /** 卡片摘要最大字符数。 */
    private static final int MAX_SNIPPET_CHARS = 120;

    // content RAG 推荐客户端
    private final AgentRagClient ragClient;

    /**
     * 注入 RAG 客户端。
     */
    public RagSearchTools(AgentRagClient ragClient) {
        this.ragClient = ragClient;
    }

    /**
     * 按自然语言和槽位找剧。
     */
    @Tool(name = "search_drama_rag",
            description = "按自然语言需求检索短剧。用户要找剧、推荐、类似剧时调用；"
                    + "可带题材、演员、时长、是否完结等槽位。返回回答和可点击的 DRAMA_CARD。")
    public String searchDramaRag(
            @ToolParam(description = "用户找剧需求，例如先婚后爱的甜宠剧", required = true)
            String query,
            @ToolParam(description = "题材偏好，例如甜宠、悬疑、古装", required = false)
            String genre,
            @ToolParam(description = "演员名字", required = false)
            String actor,
            @ToolParam(description = "时长偏好，例如短剧、10分钟以内", required = false)
            String duration,
            @ToolParam(description = "是否已完结；true 已完结，false 连载中", required = false)
            Boolean completed) {
        // 1. 拼接槽位；全部为空时提示补全
        String searchText = buildQuery(query, genre, actor, duration, completed);
        if (!StringUtils.hasText(searchText)) {
            return "[NOT_FOUND] 请先告诉我想看什么题材、演员或剧情";
        }

        try {
            // 2. 调用 content RAG；这里不传用户 ID，避免工具层伪造身份
            Result<RagRecommendView> result = ragClient.recommend(
                    new RagSearchRequest(searchText, DEFAULT_TOP_K), null);
            if (result == null || !result.isSuccess()) {
                return "[DEGRADED] 找剧服务返回异常："
                        + (result == null ? "无响应" : result.getMessage());
            }
            RagRecommendView view = result.getData();
            if (view == null) {
                return "[NO_RESULT] 暂时没有找到匹配的短剧";
            }

            // 3. 输出回答、降级标记和标准卡片行
            StringBuilder text = new StringBuilder();
            text.append("[ANSWER] ").append(safe(view.answer())).append('\n');
            if (view.degraded()) {
                text.append("[DEGRADED] flags=")
                        .append(view.degradedFlags().isEmpty()
                                ? "unknown" : String.join(",", view.degradedFlags()))
                        .append('\n');
            }
            if (view.sources().isEmpty()) {
                text.append("[NO_RESULT] 没有检索到匹配的短剧\n");
                return text.toString();
            }
            text.append("[SOURCES] ").append(view.sources().size()).append('\n');
            for (RagSourceView source : view.sources()) {
                if (source == null || source.dramaId() == null) {
                    continue;
                }
                text.append("[DRAMA_CARD] dramaId=").append(source.dramaId())
                        .append("; title=").append(cardPart(source.title()))
                        .append("; subtitle=").append(cardPart(source.subtitle()))
                        .append("; snippet=").append(cardPart(source.snippet()))
                        .append('\n');
            }
            text.append("[NOTICE] 卡片中的 dramaId 是真实数据，不要编造不存在的短剧。");
            return text.toString();
        } catch (Exception ex) {
            // 4. 远程异常转成降级标记，不中断 Agent 循环
            log.warn("search_drama_rag failed: queryChars={}", searchText.length(), ex);
            return "[DEGRADED] 找剧服务暂不可用：" + errorMessage(ex);
        }
    }

    /**
     * 拼接 query 和槽位，截断到接口允许长度。
     */
    private String buildQuery(String query, String genre, String actor,
                              String duration, Boolean completed) {
        StringBuilder text = new StringBuilder();
        appendPart(text, query);
        appendPart(text, genre);
        appendPart(text, actor);
        appendPart(text, duration);
        if (Boolean.TRUE.equals(completed)) {
            appendPart(text, "已完结");
        } else if (Boolean.FALSE.equals(completed)) {
            appendPart(text, "连载中");
        }
        String value = text.toString().trim();
        return value.length() <= MAX_QUERY_CHARS
                ? value : value.substring(0, MAX_QUERY_CHARS);
    }

    /**
     * 追加非空片段。
     */
    private void appendPart(StringBuilder text, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(value.trim());
    }

    /**
     * 清洗卡片字段，避免分号或换行破坏解析。
     */
    private String cardPart(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace(';', '，').trim();
        return clean.length() <= MAX_SNIPPET_CHARS
                ? clean : clean.substring(0, MAX_SNIPPET_CHARS);
    }

    /**
     * 空值转空串。
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 提取可读异常信息。
     */
    private String errorMessage(Exception ex) {
        return StringUtils.hasText(ex.getMessage())
                ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}
