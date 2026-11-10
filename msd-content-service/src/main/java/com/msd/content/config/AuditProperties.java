package com.msd.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 结构化审核配置。
 */
@ConfigurationProperties(prefix = "msd.ai.audit")
public class AuditProperties {

    /** 是否启用结构化审核。 */
    private boolean enabled = true;

    /** 单次审核超时（毫秒）。 */
    private long timeoutMs = 10_000;

    /** 单次请求最多尝试次数，默认重试一次。 */
    private int maxAttempts = 2;

    /** 是否启用结构化审核。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 设置是否启用结构化审核。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 单次审核超时（毫秒）。 */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /** 设置单次审核超时（毫秒）。 */
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** 单次请求最多尝试次数。 */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** 设置单次请求最多尝试次数。 */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
