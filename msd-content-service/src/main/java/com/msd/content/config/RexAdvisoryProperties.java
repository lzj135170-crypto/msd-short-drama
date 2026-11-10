package com.msd.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * RexUniNLU 情感/标签建议配置。
 *
 * <p>默认关闭；sidecar 就绪后通过 Nacos 打开。所有异常都只影响建议，不影响合规审核。</p>
 */
@ConfigurationProperties(prefix = "msd.ai.rex")
public class RexAdvisoryProperties {

    /** 是否启用 Rex 建议。 */
    private boolean enabled = false;

    /** sidecar 地址。 */
    private String baseUrl = "http://127.0.0.1:8108";

    /** 分析接口路径。 */
    private String analyzePath = "/v1/analyze";

    /** 连接超时（毫秒）。 */
    private long connectTimeoutMs = 1_000;

    /** 单次 HTTP 超时（毫秒）。 */
    private long timeoutMs = 8_000;

    /** 合规调用结束后等待建议的超时（毫秒）。 */
    private long joinTimeoutMs = 7_500;

    /** 失败后的退避时间（毫秒）。 */
    private long failureBackoffMs = 30_000;

    /** 标签字典缓存时间（毫秒）。 */
    private long tagCacheMs = 300_000;

    /** 单次请求最多携带的标签候选数。 */
    private int maxTagCandidates = 30;

    /** 最多保留的标签建议数。 */
    private int maxTags = 5;

    /** 内部调用 token，为空时不校验。 */
    private String internalToken = "";

    /** 情感标签，按正向、负向顺序解释。 */
    private List<String> sentimentLabels = List.of("正向情感", "负向情感");

    /** drama 标签字典不可用时的兜底候选。 */
    private List<String> defaultTagCandidates = List.of(
            "逆袭", "甜宠", "重生", "复仇", "悬疑", "穿越",
            "学霸", "先婚后爱", "职场", "玄幻", "亲情", "搞笑");

    /** 是否启用 Rex 建议。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 设置是否启用 Rex 建议。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** sidecar 地址。 */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** 设置 sidecar 地址。 */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 分析接口路径。 */
    public String getAnalyzePath() {
        return analyzePath;
    }

    /** 设置分析接口路径。 */
    public void setAnalyzePath(String analyzePath) {
        this.analyzePath = analyzePath;
    }

    /** 连接超时（毫秒）。 */
    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /** 设置连接超时（毫秒）。 */
    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** 单次 HTTP 超时（毫秒）。 */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /** 设置单次 HTTP 超时（毫秒）。 */
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** 建议等待超时（毫秒）。 */
    public long getJoinTimeoutMs() {
        return joinTimeoutMs;
    }

    /** 设置建议等待超时（毫秒）。 */
    public void setJoinTimeoutMs(long joinTimeoutMs) {
        this.joinTimeoutMs = joinTimeoutMs;
    }

    /** 失败退避时间（毫秒）。 */
    public long getFailureBackoffMs() {
        return failureBackoffMs;
    }

    /** 设置失败退避时间（毫秒）。 */
    public void setFailureBackoffMs(long failureBackoffMs) {
        this.failureBackoffMs = failureBackoffMs;
    }

    /** 标签字典缓存时间（毫秒）。 */
    public long getTagCacheMs() {
        return tagCacheMs;
    }

    /** 设置标签字典缓存时间（毫秒）。 */
    public void setTagCacheMs(long tagCacheMs) {
        this.tagCacheMs = tagCacheMs;
    }

    /** 最多标签候选数。 */
    public int getMaxTagCandidates() {
        return maxTagCandidates;
    }

    /** 设置最多标签候选数。 */
    public void setMaxTagCandidates(int maxTagCandidates) {
        this.maxTagCandidates = maxTagCandidates;
    }

    /** 最多保留的标签建议数。 */
    public int getMaxTags() {
        return maxTags;
    }

    /** 设置最多保留的标签建议数。 */
    public void setMaxTags(int maxTags) {
        this.maxTags = maxTags;
    }

    /** 内部调用 token。 */
    public String getInternalToken() {
        return internalToken;
    }

    /** 设置内部调用 token。 */
    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    /** 情感标签。 */
    public List<String> getSentimentLabels() {
        return sentimentLabels;
    }

    /** 设置情感标签。 */
    public void setSentimentLabels(List<String> sentimentLabels) {
        this.sentimentLabels = sentimentLabels == null ? List.of() : List.copyOf(sentimentLabels);
    }

    /** 默认标签候选。 */
    public List<String> getDefaultTagCandidates() {
        return defaultTagCandidates;
    }

    /** 设置默认标签候选。 */
    public void setDefaultTagCandidates(List<String> defaultTagCandidates) {
        this.defaultTagCandidates = defaultTagCandidates == null
                ? List.of() : List.copyOf(defaultTagCandidates);
    }
}
