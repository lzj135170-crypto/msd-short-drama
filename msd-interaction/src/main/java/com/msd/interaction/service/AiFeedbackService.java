package com.msd.interaction.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.interaction.dto.AiFeedbackRequest;
import com.msd.interaction.dto.AiFeedbackResult;
import com.msd.interaction.entity.AiFeedback;
import com.msd.interaction.mapper.AiFeedbackMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 回答反馈：校验、幂等写入和指标。
 */
@Service
public class AiFeedbackService {

    /** 允许的 AI 场景。 */
    private static final Set<String> SCENES = Set.of(
            "RAG_SEARCH", "COMPANION", "AUDIT", "RECOMMEND", "CREATOR_COPY");
    /** 允许的反馈原因，ADOPT 用于创作者采纳文案。 */
    private static final Set<String> REASONS = Set.of(
            "INACCURATE", "IRRELEVANT", "SPOILER", "UNSAFE", "OTHER", "ADOPT");
    /** 除换行、回车、制表符外的控制字符。 */
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    /** 补充说明最大长度，与请求 DTO 一致。 */
    private static final int MAX_COMMENT_LENGTH = 500;

    // 反馈 Mapper
    private final AiFeedbackMapper feedbackMapper;
    // Micrometer 注册表
    private final MeterRegistry meterRegistry;

    /**
     * 注入反馈 Mapper 和指标注册表。
     */
    public AiFeedbackService(AiFeedbackMapper feedbackMapper, MeterRegistry meterRegistry) {
        this.feedbackMapper = feedbackMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 提交一条反馈；相同用户、场景和 requestId 重复提交保持幂等。
     */
    public AiFeedbackResult submit(Long userId, AiFeedbackRequest request) {
        // 1. 校验并归一场景、评分和原因
        String scene = normalizeScene(request.scene());
        int rating = validateRating(request.rating());
        String requestId = normalizeText(request.requestId(), "requestId");
        String reason = normalizeReason(request.reason());
        String comment = normalizeComment(request.comment());
        String sessionId = normalizeOptional(request.sessionId());

        // 2. 先尝试插入，唯一键冲突时返回已有记录
        AiFeedback feedback = new AiFeedback();
        feedback.setUserId(userId);
        feedback.setScene(scene);
        feedback.setRequestId(requestId);
        feedback.setRating(rating);
        feedback.setReason(reason);
        feedback.setComment(comment);
        feedback.setTargetId(request.targetId());
        feedback.setSessionId(sessionId);
        int inserted = feedbackMapper.insertIgnore(feedback);
        if (inserted == 1) {
            meterRegistry.counter("ai_feedback_total",
                    "scene", scene, "rating", String.valueOf(rating)).increment();
            return new AiFeedbackResult(feedback.getId(), false);
        }

        // 3. 冲突时读取已有记录，仍返回成功，前端不重复计数
        AiFeedback existing = feedbackMapper.selectByUserSceneRequest(userId, scene, requestId);
        if (existing == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "反馈提交失败，请稍后重试");
        }
        return new AiFeedbackResult(existing.getId(), true);
    }

    /**
     * 场景转大写并校验白名单。
     */
    private String normalizeScene(String value) {
        String scene = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SCENES.contains(scene)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不支持的反馈场景");
        }
        return scene;
    }

    /**
     * rating 只允许 1 和 -1。
     */
    private int validateRating(Integer value) {
        if (value == null || (value != 1 && value != -1)) {
            throw new BizException(ResultCode.PARAM_ERROR, "反馈评分只能为 1 或 -1");
        }
        return value;
    }

    /**
     * 原因转大写并校验白名单；空值按未选择处理。
     */
    private String normalizeReason(String value) {
        String reason = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (reason.isEmpty()) {
            return "";
        }
        if (!REASONS.contains(reason)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不支持的反馈原因");
        }
        return reason;
    }

    /**
     * requestId 去空格并拒绝控制字符。
     */
    private String normalizeText(String value, String fieldName) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, fieldName + "不能为空");
        }
        if (CONTROL_CHARS.matcher(text).find()) {
            throw new BizException(ResultCode.PARAM_ERROR, fieldName + "包含不支持的字符");
        }
        return text;
    }

    /**
     * 补充说明去空格、限制长度并拒绝控制字符。
     */
    private String normalizeComment(String value) {
        String comment = value == null ? "" : value.trim();
        if (comment.length() > MAX_COMMENT_LENGTH) {
            throw new BizException(ResultCode.PARAM_ERROR, "补充说明不能超过 500 字");
        }
        if (CONTROL_CHARS.matcher(comment).find()) {
            throw new BizException(ResultCode.PARAM_ERROR, "补充说明包含不支持的字符");
        }
        return comment;
    }

    /**
     * 可选字段去空格，空值转 null。
     */
    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (CONTROL_CHARS.matcher(text).find()) {
            throw new BizException(ResultCode.PARAM_ERROR, "会话 ID 包含不支持的字符");
        }
        return text;
    }
}
