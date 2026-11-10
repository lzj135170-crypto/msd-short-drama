package com.msd.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 伴看配置，对应 Nacos 中的 content-config.yaml。
 *
 * <p>默认值保证 content-config.yaml 未发布时仍可降级运行。</p>
 */
@ConfigurationProperties(prefix = "msd.ai.companion")
public class CompanionProperties {

    /** 是否启用 AI 伴看能力。 */
    private boolean enabled = true;

    // 分集摘要生成配置
    private Summary summary = new Summary();
    // 前情检索配置
    private Context context = new Context();
    // 会话记忆配置
    private Memory memory = new Memory();
    // 伴看聊天配置
    private Chat chat = new Chat();
    // 前情提要配置
    private Recap recap = new Recap();
    // 轻量模型配置
    private LightModel lightModel = new LightModel();

    /** 是否启用 AI 伴看。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 设置是否启用 AI 伴看。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 分集摘要生成配置。 */
    public Summary getSummary() {
        return summary;
    }

    /** 设置分集摘要生成配置。 */
    public void setSummary(Summary summary) {
        this.summary = summary;
    }

    /** 前情检索配置。 */
    public Context getContext() {
        return context;
    }

    /** 设置前情检索配置。 */
    public void setContext(Context context) {
        this.context = context;
    }

    /** 会话记忆配置。 */
    public Memory getMemory() {
        return memory;
    }

    /** 设置会话记忆配置。 */
    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    /** 伴看聊天配置。 */
    public Chat getChat() {
        return chat;
    }

    /** 设置伴看聊天配置。 */
    public void setChat(Chat chat) {
        this.chat = chat;
    }

    /** 前情提要配置。 */
    public Recap getRecap() {
        return recap;
    }

    /** 设置前情提要配置。 */
    public void setRecap(Recap recap) {
        this.recap = recap;
    }

    /** 轻量模型配置。 */
    public LightModel getLightModel() {
        return lightModel;
    }

    /** 设置轻量模型配置。 */
    public void setLightModel(LightModel lightModel) {
        this.lightModel = lightModel;
    }

    /**
     * 分集摘要生成配置。
     */
    public static class Summary {

        // 摘要最大字符数，超出后截断
        private int maxChars = 120;

        /** 摘要最大字符数。 */
        public int getMaxChars() {
            return maxChars;
        }

        /** 设置摘要最大字符数。 */
        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }
    }

    /**
     * 前情检索配置。
     */
    public static class Context {

        // 默认返回的前情条数
        private int defaultTopK = 3;
        // 允许的最大前情条数
        private int maxTopK = 5;
        // 单条摘要返回给模型前截断的字符数
        private int maxSummaryChars = 200;

        /** 默认返回的前情条数。 */
        public int getDefaultTopK() {
            return defaultTopK;
        }

        /** 设置默认返回的前情条数。 */
        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        /** 允许的最大前情条数。 */
        public int getMaxTopK() {
            return maxTopK;
        }

        /** 设置允许的最大前情条数。 */
        public void setMaxTopK(int maxTopK) {
            this.maxTopK = maxTopK;
        }

        /** 单条摘要最大字符数。 */
        public int getMaxSummaryChars() {
            return maxSummaryChars;
        }

        /** 设置单条摘要最大字符数。 */
        public void setMaxSummaryChars(int maxSummaryChars) {
            this.maxSummaryChars = maxSummaryChars;
        }
    }

    /**
     * 会话记忆配置。
     */
    public static class Memory {

        // 窗口保留的最近消息数，20 条约等于 10 轮
        private int maxMessages = 20;
        // 累计多少条溢出消息后才调用模型压缩，避免只拿 1 条消息生成摘要
        private int compressionBatch = 4;
        // Redis 归档最多保留的消息数
        private int maxArchivedMessages = 200;
        // 归档过期天数
        private int ttlDays = 7;
        // 历史摘要最大字符数
        private int maxSummaryChars = 240;
        // 压缩提示中单条消息的最大字符数
        private int compressMessageChars = 300;
        // 跨实例压缩锁等待时间（毫秒）
        private long lockWaitMs = 200;
        // 跨实例压缩锁租期（毫秒）
        private long lockLeaseMs = 10_000;

        /** 窗口保留的最近消息数。 */
        public int getMaxMessages() {
            return maxMessages;
        }

        /** 设置窗口保留的最近消息数。 */
        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        /** 累计多少条溢出消息后才压缩。 */
        public int getCompressionBatch() {
            return compressionBatch;
        }

        /** 设置累计多少条溢出消息后才压缩。 */
        public void setCompressionBatch(int compressionBatch) {
            this.compressionBatch = compressionBatch;
        }

        /** Redis 归档最多保留的消息数。 */
        public int getMaxArchivedMessages() {
            return maxArchivedMessages;
        }

        /** 设置 Redis 归档最多保留的消息数。 */
        public void setMaxArchivedMessages(int maxArchivedMessages) {
            this.maxArchivedMessages = maxArchivedMessages;
        }

        /** 归档过期天数。 */
        public int getTtlDays() {
            return ttlDays;
        }

        /** 设置归档过期天数。 */
        public void setTtlDays(int ttlDays) {
            this.ttlDays = ttlDays;
        }

        /** 历史摘要最大字符数。 */
        public int getMaxSummaryChars() {
            return maxSummaryChars;
        }

        /** 设置历史摘要最大字符数。 */
        public void setMaxSummaryChars(int maxSummaryChars) {
            this.maxSummaryChars = maxSummaryChars;
        }

        /** 压缩提示中单条消息最大字符数。 */
        public int getCompressMessageChars() {
            return compressMessageChars;
        }

        /** 设置压缩提示中单条消息最大字符数。 */
        public void setCompressMessageChars(int compressMessageChars) {
            this.compressMessageChars = compressMessageChars;
        }

        /** 跨实例压缩锁等待时间（毫秒）。 */
        public long getLockWaitMs() {
            return lockWaitMs;
        }

        /** 设置跨实例压缩锁等待时间（毫秒）。 */
        public void setLockWaitMs(long lockWaitMs) {
            this.lockWaitMs = lockWaitMs;
        }

        /** 跨实例压缩锁租期（毫秒）。 */
        public long getLockLeaseMs() {
            return lockLeaseMs;
        }

        /** 设置跨实例压缩锁租期（毫秒）。 */
        public void setLockLeaseMs(long lockLeaseMs) {
            this.lockLeaseMs = lockLeaseMs;
        }
    }

    /**
     * 伴看聊天配置。
     */
    public static class Chat {

        // 聊天接口整体超时（毫秒）
        private long timeoutMs = 30_000;

        /** 聊天接口整体超时（毫秒）。 */
        public long getTimeoutMs() {
            return timeoutMs;
        }

        /** 设置聊天接口整体超时（毫秒）。 */
        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /**
     * 前情提要配置。
     */
    public static class Recap {

        // 最多返回的当前集之前的分集数
        private int maxPrevious = 5;

        /** 最多返回的当前集之前的分集数。 */
        public int getMaxPrevious() {
            return maxPrevious;
        }

        /** 设置最多返回的当前集之前的分集数。 */
        public void setMaxPrevious(int maxPrevious) {
            this.maxPrevious = maxPrevious;
        }
    }

    /**
     * 轻量模型配置，用于摘要生成和历史对话压缩。
     */
    public static class LightModel {

        // 模型名；qwen-turbo 在部分账号上限流，默认使用同价位的 qwen-flash
        private String model = "qwen-flash";
        // 采样温度
        private double temperature = 0.1;
        // 单次最大输出 token
        private int maxTokens = 256;
        // 单次调用超时（毫秒）
        private long timeoutMs = 4_000;
        // 失败重试次数
        private int maxRetries = 0;
        // OpenAI 兼容接口地址，为空时复用 spring.ai.openai.base-url
        private String baseUrl = "";
        // API Key，为空时复用 spring.ai.openai.api-key
        private String apiKey = "";

        /** 模型名。 */
        public String getModel() {
            return model;
        }

        /** 设置模型名。 */
        public void setModel(String model) {
            this.model = model;
        }

        /** 采样温度。 */
        public double getTemperature() {
            return temperature;
        }

        /** 设置采样温度。 */
        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        /** 单次最大输出 token。 */
        public int getMaxTokens() {
            return maxTokens;
        }

        /** 设置单次最大输出 token。 */
        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        /** 单次调用超时（毫秒）。 */
        public long getTimeoutMs() {
            return timeoutMs;
        }

        /** 设置单次调用超时（毫秒）。 */
        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        /** 失败重试次数。 */
        public int getMaxRetries() {
            return maxRetries;
        }

        /** 设置失败重试次数。 */
        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        /** OpenAI 兼容接口地址。 */
        public String getBaseUrl() {
            return baseUrl;
        }

        /** 设置 OpenAI 兼容接口地址。 */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /** API Key。 */
        public String getApiKey() {
            return apiKey;
        }

        /** 设置 API Key。 */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
