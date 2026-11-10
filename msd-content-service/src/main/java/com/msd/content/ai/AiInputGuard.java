package com.msd.content.ai;

import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AI 入口输入防护。
 *
 * <p>只拦截明显的内部标记、提示词注入和控制字符，不替代业务参数校验；
 * 正常的中文自然语言查询不应被误伤。</p>
 */
@Component
public class AiInputGuard {

    /** RAG 查询最大长度，与请求 DTO 保持一致。 */
    private static final int MAX_QUERY_LENGTH = 200;
    /** 伴看消息最大长度，与请求 DTO 保持一致。 */
    private static final int MAX_MESSAGE_LENGTH = 1000;
    /** 审核内容最大长度，与 DramaReviewAuditService 的截断上限保持一致。 */
    private static final int MAX_AUDIT_CONTENT_LENGTH = 5000;

    /** 除换行、回车、制表符外的控制字符。 */
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    /** 常见内部路由中不会出现在用户输入里的标记。 */
    private static final List<String> INTERNAL_MARKERS = List.of(
            "[route_hint",
            "[当前剧集id",
            "<|im_start|>",
            "<|im_end|>",
            "ignore previous instructions",
            "ignore all previous instructions",
            "忽略以上指令",
            "忽略之前的指令",
            "忽略前面的指令",
            "system prompt"
    );

    /**
     * 校验 RAG 查询。
     *
     * @param query 用户自然语言查询
     */
    public void validateQuery(String query) {
        validate(query, MAX_QUERY_LENGTH, "查询");
    }

    /**
     * 校验伴看消息。
     *
     * @param message 用户聊天消息
     */
    public void validateMessage(String message) {
        validate(message, MAX_MESSAGE_LENGTH, "消息");
    }

    /**
     * 校验送审文本；标题和简介也可能被用来注入指令。
     *
     * @param content 送审文本
     */
    public void validateAuditContent(String content) {
        validate(content, MAX_AUDIT_CONTENT_LENGTH, "审核内容");
    }

    /**
     * 统一执行长度、控制字符和内部标记检查。
     */
    private void validate(String value, int maxLength, String fieldName) {
        // 1. 空值交给 Bean Validation 或其他业务校验处理
        if (value == null || value.isBlank()) {
            return;
        }
        // 2. 防止超长输入放大模型成本和延迟
        if (value.length() > maxLength) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    fieldName + "不能超过 " + maxLength + " 个字符");
        }
        // 3. 控制字符通常来自复制粘贴或构造请求，直接拒绝
        if (CONTROL_CHARS.matcher(value).find()) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    fieldName + "包含不支持的字符");
        }
        // 4. 拦截伪造内部标记和明显的提示词注入
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String marker : INTERNAL_MARKERS) {
            if (normalized.contains(marker)) {
                throw new BizException(ResultCode.PARAM_ERROR,
                        fieldName + "包含不支持的指令");
            }
        }
    }
}
