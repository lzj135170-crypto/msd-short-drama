package com.msd.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 创作者 Copilot 配置。
 *
 * <p>生成结果只读，不写审核状态和标签关系；每日额度和短时缓存用于控制成本。</p>
 */
@ConfigurationProperties(prefix = "msd.ai.copilot")
public class CreatorCopilotProperties {

    /** 是否启用创作者 Copilot。 */
    private boolean enabled = true;

    /** 每个用户每天最多生成次数。 */
    private int dailyLimit = 20;

    /** 结果缓存分钟数。 */
    private int cacheMinutes = 10;

    /** 结果缓存最大条目数。 */
    private int cacheMaxEntries = 300;

    /** 最多生成的版本数。 */
    private int maxVersions = 3;

    /** 标题最大字符数。 */
    private int maxTitleChars = 30;

    /** 副标题最大字符数。 */
    private int maxSubtitleChars = 60;

    /** 标签最大字符数。 */
    private int maxTagChars = 20;

    /** 推荐理由最大字符数。 */
    private int maxRationaleChars = 120;

    /** 提醒最大条数。 */
    private int maxTips = 3;

    /** 是否启用创作者 Copilot。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 设置是否启用创作者 Copilot。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 每个用户每天最多生成次数。 */
    public int getDailyLimit() {
        return dailyLimit;
    }

    /** 设置每个用户每天最多生成次数。 */
    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    /** 结果缓存分钟数。 */
    public int getCacheMinutes() {
        return cacheMinutes;
    }

    /** 设置结果缓存分钟数。 */
    public void setCacheMinutes(int cacheMinutes) {
        this.cacheMinutes = cacheMinutes;
    }

    /** 结果缓存最大条目数。 */
    public int getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    /** 设置结果缓存最大条目数。 */
    public void setCacheMaxEntries(int cacheMaxEntries) {
        this.cacheMaxEntries = cacheMaxEntries;
    }

    /** 最多生成的版本数。 */
    public int getMaxVersions() {
        return maxVersions;
    }

    /** 设置最多生成的版本数。 */
    public void setMaxVersions(int maxVersions) {
        this.maxVersions = maxVersions;
    }

    /** 标题最大字符数。 */
    public int getMaxTitleChars() {
        return maxTitleChars;
    }

    /** 设置标题最大字符数。 */
    public void setMaxTitleChars(int maxTitleChars) {
        this.maxTitleChars = maxTitleChars;
    }

    /** 副标题最大字符数。 */
    public int getMaxSubtitleChars() {
        return maxSubtitleChars;
    }

    /** 设置副标题最大字符数。 */
    public void setMaxSubtitleChars(int maxSubtitleChars) {
        this.maxSubtitleChars = maxSubtitleChars;
    }

    /** 标签最大字符数。 */
    public int getMaxTagChars() {
        return maxTagChars;
    }

    /** 设置标签最大字符数。 */
    public void setMaxTagChars(int maxTagChars) {
        this.maxTagChars = maxTagChars;
    }

    /** 推荐理由最大字符数。 */
    public int getMaxRationaleChars() {
        return maxRationaleChars;
    }

    /** 设置推荐理由最大字符数。 */
    public void setMaxRationaleChars(int maxRationaleChars) {
        this.maxRationaleChars = maxRationaleChars;
    }

    /** 提醒最大条数。 */
    public int getMaxTips() {
        return maxTips;
    }

    /** 设置提醒最大条数。 */
    public void setMaxTips(int maxTips) {
        this.maxTips = maxTips;
    }
}
