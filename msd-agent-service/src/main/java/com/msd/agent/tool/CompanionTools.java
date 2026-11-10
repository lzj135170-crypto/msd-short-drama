package com.msd.agent.tool;

import com.msd.agent.client.AgentCompanionClient;
import com.msd.agent.client.dto.AgentCompanionViews.CompanionContextRequest;
import com.msd.agent.client.dto.AgentCompanionViews.CompanionContextView;
import com.msd.agent.client.dto.AgentCompanionViews.ContextItem;
import com.msd.agent.client.dto.AgentCompanionViews.EpisodeSummaryView;
import com.msd.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AI 伴看工具：当前集摘要和前情检索。
 *
 * <p>所有失败都转换成固定标记文本，不向 AgentScope 抛异常；
 * 前情检索只返回当前集之前的内容，工具层再做一次集数提示。</p>
 */
@Component
@Profile("!audit")
public class CompanionTools {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(CompanionTools.class);

    /** 单条前情摘要最大展示字符数。 */
    private static final int MAX_SUMMARY_CHARS = 200;
    /** 前情检索问题最大字符数。 */
    private static final int MAX_QUERY_CHARS = 500;
    /** 默认前情条数。 */
    private static final int CONTEXT_TOP_K = 3;

    // 内容服务伴看接口客户端
    private final AgentCompanionClient companionClient;

    /**
     * 注入内容服务伴看客户端。
     */
    public CompanionTools(AgentCompanionClient companionClient) {
        this.companionClient = companionClient;
    }

    /**
     * 查询当前正在观看剧集的分集摘要。
     */
    @Tool(name = "get_episode_summary",
            description = "查询当前正在观看剧集的分集摘要。必须先调用本工具了解当前集，再回答剧情问题。")
    public String getEpisodeSummary(
            @ToolParam(description = "当前剧集ID，必须为正整数", required = true) Long episodeId) {
        // 1. 本地校验 ID，避免无效远程调用
        if (episodeId == null || episodeId <= 0) {
            return "[NOT_FOUND] episodeId 必须为正整数";
        }
        try {
            // 2. 调用内容服务摘要接口
            Result<EpisodeSummaryView> result = companionClient.summary(episodeId);
            if (result == null || !result.isSuccess()) {
                return "[DEGRADED] 分集摘要服务返回异常：" + resultMessage(result);
            }
            EpisodeSummaryView view = result.getData();
            if (view == null) {
                return "[NOT_FOUND] 未找到剧集 episodeId=" + episodeId;
            }

            // 3. 固定输出当前集信息
            StringBuilder text = new StringBuilder();
            text.append("[EPISODE] dramaId=").append(number(view.dramaId()))
                    .append(" episodeId=").append(number(view.episodeId()))
                    .append(" episodeNo=").append(number(view.episodeNo()))
                    .append(" title=").append(safe(view.episodeTitle()))
                    .append('\n');

            // 4. 摘要缺失时明确标记，不编造
            if (!StringUtils.hasText(view.summary())) {
                text.append("[SUMMARY_MISSING] 当前集摘要暂不可用\n");
                if (view.degraded()) {
                    text.append(degradedLine(view.degradedFlags())).append('\n');
                }
                return text.toString().trim();
            }

            // 5. 摘要可用时输出来源和防剧透提示
            text.append("[SUMMARY] ").append(view.summary().trim()).append('\n')
                    .append("[SOURCE] ").append(source(view.source())).append('\n');
            if (view.degraded()) {
                text.append(degradedLine(view.degradedFlags())).append('\n');
            }
            text.append("[NOTICE] 只能把摘要当作当前集事实；不要推断第")
                    .append(number(view.episodeNo()) + 1).append("集及之后。");
            return text.toString();
        } catch (Exception ex) {
            // 6. 远程异常转成降级标记，避免中断 Agent 循环
            log.warn("get_episode_summary failed: episodeId={}", episodeId, ex);
            return "[DEGRADED] 分集摘要暂不可用：" + errorMessage(ex);
        }
    }

    /**
     * 检索当前集之前的前情。
     */
    @Tool(name = "search_drama_context",
            description = "检索当前集之前的前情。只返回 currentEpisodeId 之前的剧集，严禁用于推测后续剧情。")
    public String searchDramaContext(
            @ToolParam(description = "前情关键词或问题", required = true) String query,
            @ToolParam(description = "当前剧集ID，必须为正整数", required = true) Long currentEpisodeId) {
        // 1. 本地校验参数
        if (!StringUtils.hasText(query)) {
            return "[CONTEXT_EMPTY] 前情查询内容为空";
        }
        if (currentEpisodeId == null || currentEpisodeId <= 0) {
            return "[NOT_FOUND] currentEpisodeId 必须为正整数";
        }
        String safeQuery = truncate(query.trim(), MAX_QUERY_CHARS);

        try {
            // 2. 调用内容服务前情检索
            Result<CompanionContextView> result = companionClient.context(
                    new CompanionContextRequest(safeQuery, currentEpisodeId, CONTEXT_TOP_K));
            if (result == null || !result.isSuccess()) {
                return "[DEGRADED] 前情检索服务返回异常：" + resultMessage(result);
            }
            CompanionContextView view = result.getData();
            if (view == null) {
                return "[NOT_FOUND] 未找到剧集 currentEpisodeId=" + currentEpisodeId;
            }

            // 3. 没有前情时区分降级和正常空结果
            if (view.items() == null || view.items().isEmpty()) {
                if (view.degraded()) {
                    return "[DEGRADED] " + degradedLine(view.degradedFlags())
                            + "\n[CONTEXT_EMPTY] 未检索到当前集之前的前情";
                }
                return "[CONTEXT_EMPTY] 没有当前集之前的前情";
            }

            // 4. 逐条输出前情，摘要截断避免结果过长
            StringBuilder text = new StringBuilder();
            text.append("[CONTEXT] dramaId=").append(number(view.dramaId()))
                    .append(" currentEpisodeId=").append(number(view.currentEpisodeId()))
                    .append(" currentEpisodeNo=").append(number(view.currentEpisodeNo()))
                    .append(" returned=").append(view.items().size())
                    .append('\n');
            int index = 1;
            for (ContextItem item : view.items()) {
                if (item == null) {
                    continue;
                }
                text.append('[').append(index++).append("] episodeId=")
                        .append(number(item.episodeId()))
                        .append(" episodeNo=").append(number(item.episodeNo()))
                        .append(" title=").append(safe(item.episodeTitle()))
                        .append('\n')
                        .append("摘要：").append(truncate(safe(item.summary()), MAX_SUMMARY_CHARS))
                        .append('\n');
            }
            if (view.degraded()) {
                text.append(degradedLine(view.degradedFlags())).append('\n');
            }
            text.append("[NOTICE] 以上仅为第")
                    .append(number(view.currentEpisodeNo()))
                    .append("集之前的内容；不得推测后续剧情。");
            return text.toString();
        } catch (Exception ex) {
            // 5. 远程异常转成降级标记
            log.warn("search_drama_context failed: episodeId={}", currentEpisodeId, ex);
            return "[DEGRADED] 前情检索暂不可用：" + errorMessage(ex);
        }
    }

    /**
     * 统一降级标记行。
     */
    private String degradedLine(java.util.List<String> flags) {
        String values = flags == null || flags.isEmpty() ? "unknown" : String.join(",", flags);
        return "[DEGRADED] flags=" + values;
    }

    /**
     * 摘要来源，缺失时使用 unknown。
     */
    private String source(String source) {
        return StringUtils.hasText(source) ? source : "unknown";
    }

    /**
     * 取远程响应消息。
     */
    private String resultMessage(Result<?> result) {
        if (result == null) {
            return "内容服务无响应";
        }
        return StringUtils.hasText(result.getMessage()) ? result.getMessage() : "内容服务返回异常";
    }

    /**
     * 空值转空串。
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 空计数转 0。
     */
    private static long number(Number value) {
        return value == null ? 0 : value.longValue();
    }

    /**
     * 按字符数截断文本。
     */
    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    /**
     * 提取可读异常信息。
     */
    private static String errorMessage(Exception ex) {
        return StringUtils.hasText(ex.getMessage())
                ? ex.getMessage()
                : ex.getClass().getSimpleName();
    }
}
