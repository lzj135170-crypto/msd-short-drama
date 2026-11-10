package com.msd.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 创作者 AI 预检配置。
 *
 * <p>预检只给建议，不写审核状态；每日额度和结果缓存用于控制模型成本。</p>
 */
@ConfigurationProperties(prefix = "msd.ai.precheck")
public class DramaPrecheckProperties {

    /** 是否启用创作者 AI 预检。 */
    private boolean enabled = true;

    /** 每个用户每天最多预检次数。 */
    private int dailyLimit = 10;

    /** 预检结果缓存分钟数。 */
    private int cacheMinutes = 10;

    /** 预检结果缓存最大条目数。 */
    private int cacheMaxEntries = 500;

    /** 文案建议等待超时（毫秒）。 */
    private long suggestionTimeoutMs = 8_000;

    /** 标题建议最大条数。 */
    private int maxTitleSuggestions = 3;

    /** 副标题建议最大条数。 */
    private int maxSubtitleSuggestions = 3;

    /** 标签建议最大条数。 */
    private int maxTagSuggestions = 5;

    /** 整改建议最大条数。 */
    private int maxTips = 3;

    /** 单条建议最大字符数。 */
    private int maxSuggestionChars = 80;

    /** 是否启用创作者 AI 预检。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 设置是否启用创作者 AI 预检。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 每个用户每天最多预检次数。 */
    public int getDailyLimit() {
        return dailyLimit;
    }

    /** 设置每个用户每天最多预检次数。 */
    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    /** 预检结果缓存分钟数。 */
    public int getCacheMinutes() {
        return cacheMinutes;
    }

    /** 设置预检结果缓存分钟数。 */
    public void setCacheMinutes(int cacheMinutes) {
        this.cacheMinutes = cacheMinutes;
    }

    /** 预检结果缓存最大条目数。 */
    public int getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    /** 设置预检结果缓存最大条目数。 */
    public void setCacheMaxEntries(int cacheMaxEntries) {
        this.cacheMaxEntries = cacheMaxEntries;
    }

    /** 文案建议等待超时（毫秒）。 */
    public long getSuggestionTimeoutMs() {
        return suggestionTimeoutMs;
    }

    /** 设置文案建议等待超时（毫秒）。 */
    public void setSuggestionTimeoutMs(long suggestionTimeoutMs) {
        this.suggestionTimeoutMs = suggestionTimeoutMs;
    }

    /** 标题建议最大条数。 */
    public int getMaxTitleSuggestions() {
        return maxTitleSuggestions;
    }

    /** 设置标题建议最大条数。 */
    public void setMaxTitleSuggestions(int maxTitleSuggestions) {
        this.maxTitleSuggestions = maxTitleSuggestions;
    }

    /** 副标题建议最大条数。 */
    public int getMaxSubtitleSuggestions() {
        return maxSubtitleSuggestions;
    }

    /** 设置副标题建议最大条数。 */
    public void setMaxSubtitleSuggestions(int maxSubtitleSuggestions) {
        this.maxSubtitleSuggestions = maxSubtitleSuggestions;
    }

    /** 标签建议最大条数。 */
    public int getMaxTagSuggestions() {
        return maxTagSuggestions;
    }

    /** 设置标签建议最大条数。 */
    public void setMaxTagSuggestions(int maxTagSuggestions) {
        this.maxTagSuggestions = maxTagSuggestions;
    }

    /** 整改建议最大条数。 */
    public int getMaxTips() {
        return maxTips;
    }

    /** 设置整改建议最大条数。 */
    public void setMaxTips(int maxTips) {
        this.maxTips = maxTips;
    }

    /** 单条建议最大字符数。 */
    public int getMaxSuggestionChars() {
        return maxSuggestionChars;
    }

    /** 设置单条建议最大字符数。 */
    public void setMaxSuggestionChars(int maxSuggestionChars) {
        this.maxSuggestionChars = maxSuggestionChars;
    }
}
