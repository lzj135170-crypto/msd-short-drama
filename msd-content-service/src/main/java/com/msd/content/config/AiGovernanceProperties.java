package com.msd.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 治理配置：功能灰度、A/B 实验、token 预算和 RAG 缓存。
 *
 * <p>默认全部保守关闭或放行，保证 Nacos 未发布时不影响现有 AI 链路。</p>
 */
@ConfigurationProperties(prefix = "msd.ai.governance")
public class AiGovernanceProperties {

    // 按功能开关和灰度比例，key 为功能名，例如 rag-search
    private Map<String, Feature> features = new LinkedHashMap<>();
    // 按实验名配置，key 为实验名，例如 rag-model
    private Map<String, Experiment> experiments = new LinkedHashMap<>();
    // 按用户按天的 token 预算
    private Budget budget = new Budget();
    // RAG 精确/语义缓存
    private Cache cache = new Cache();

    /** 功能开关配置。 */
    public Map<String, Feature> getFeatures() {
        return features;
    }

    /** 设置功能开关配置。 */
    public void setFeatures(Map<String, Feature> features) {
        this.features = features == null ? new LinkedHashMap<>() : features;
    }

    /** 实验配置。 */
    public Map<String, Experiment> getExperiments() {
        return experiments;
    }

    /** 设置实验配置。 */
    public void setExperiments(Map<String, Experiment> experiments) {
        this.experiments = experiments == null ? new LinkedHashMap<>() : experiments;
    }

    /** token 预算配置。 */
    public Budget getBudget() {
        return budget;
    }

    /** 设置 token 预算配置。 */
    public void setBudget(Budget budget) {
        this.budget = budget == null ? new Budget() : budget;
    }

    /** RAG 缓存配置。 */
    public Cache getCache() {
        return cache;
    }

    /** 设置 RAG 缓存配置。 */
    public void setCache(Cache cache) {
        this.cache = cache == null ? new Cache() : cache;
    }

    /**
     * 单个功能开关。
     */
    public static class Feature {

        // 是否开启该功能
        private boolean enabled = true;
        // 灰度比例 0-100，100 表示全量
        private int grayPercent = 100;
        // 未登录用户是否参与灰度
        private boolean anonymousIncluded = true;

        /** 是否开启。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 设置是否开启。 */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** 灰度比例。 */
        public int getGrayPercent() {
            return grayPercent;
        }

        /** 设置灰度比例。 */
        public void setGrayPercent(int grayPercent) {
            this.grayPercent = grayPercent;
        }

        /** 未登录用户是否参与灰度。 */
        public boolean isAnonymousIncluded() {
            return anonymousIncluded;
        }

        /** 设置未登录用户是否参与灰度。 */
        public void setAnonymousIncluded(boolean anonymousIncluded) {
            this.anonymousIncluded = anonymousIncluded;
        }
    }

    /**
     * 单个 A/B 实验。
     */
    public static class Experiment {

        // 是否开启该实验
        private boolean enabled = false;
        // 分桶盐值，变更后可重新洗牌
        private String salt = "msd";
        // 未登录用户是否参与实验
        private boolean anonymousIncluded = false;
        // 变体列表，按顺序累计百分比
        private List<Variant> variants = new ArrayList<>();

        /** 是否开启。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 设置是否开启。 */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** 分桶盐值。 */
        public String getSalt() {
            return salt;
        }

        /** 设置分桶盐值。 */
        public void setSalt(String salt) {
            this.salt = salt;
        }

        /** 未登录用户是否参与实验。 */
        public boolean isAnonymousIncluded() {
            return anonymousIncluded;
        }

        /** 设置未登录用户是否参与实验。 */
        public void setAnonymousIncluded(boolean anonymousIncluded) {
            this.anonymousIncluded = anonymousIncluded;
        }

        /** 变体列表。 */
        public List<Variant> getVariants() {
            return variants;
        }

        /** 设置变体列表。 */
        public void setVariants(List<Variant> variants) {
            this.variants = variants == null ? new ArrayList<>() : variants;
        }
    }

    /**
     * 实验变体；model 为空时沿用默认模型。
     */
    public static class Variant {

        // 变体名，写入日志和指标
        private String name = "";
        // 变体百分比 0-100
        private int percent;
        // 覆盖的模型名，为空表示不切换
        private String model = "";
        // 覆盖的温度，未设置时沿用默认值
        private double temperature = 0.3;

        /** 变体名。 */
        public String getName() {
            return name;
        }

        /** 设置变体名。 */
        public void setName(String name) {
            this.name = name;
        }

        /** 变体百分比。 */
        public int getPercent() {
            return percent;
        }

        /** 设置变体百分比。 */
        public void setPercent(int percent) {
            this.percent = percent;
        }

        /** 覆盖的模型名。 */
        public String getModel() {
            return model;
        }

        /** 设置覆盖的模型名。 */
        public void setModel(String model) {
            this.model = model;
        }

        /** 覆盖的温度。 */
        public double getTemperature() {
            return temperature;
        }

        /** 设置覆盖的温度。 */
        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    /**
     * 用户每日 token 预算。
     */
    public static class Budget {

        // 是否启用预算限制
        private boolean enabled = false;
        // 每个用户每天最多消耗的估算 token
        private long dailyTokensPerUser = 200_000;
        // 估算字符转 token 的比例，中文约 1.5 字符/token
        private double charsPerToken = 1.5;
        // 单次调用预留的输出 token
        private int expectedOutputTokens = 500;

        /** 是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 设置是否启用。 */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** 每日 token 上限。 */
        public long getDailyTokensPerUser() {
            return dailyTokensPerUser;
        }

        /** 设置每日 token 上限。 */
        public void setDailyTokensPerUser(long dailyTokensPerUser) {
            this.dailyTokensPerUser = dailyTokensPerUser;
        }

        /** 字符转 token 比例。 */
        public double getCharsPerToken() {
            return charsPerToken;
        }

        /** 设置字符转 token 比例。 */
        public void setCharsPerToken(double charsPerToken) {
            this.charsPerToken = charsPerToken;
        }

        /** 单次调用预留输出 token。 */
        public int getExpectedOutputTokens() {
            return expectedOutputTokens;
        }

        /** 设置单次调用预留输出 token。 */
        public void setExpectedOutputTokens(int expectedOutputTokens) {
            this.expectedOutputTokens = expectedOutputTokens;
        }
    }

    /**
     * RAG 缓存配置。
     */
    public static class Cache {

        // 是否启用精确缓存
        private boolean exactEnabled = true;
        // 精确缓存分钟数
        private int exactMinutes = 5;
        // 精确缓存最大条目数
        private int exactMaxEntries = 500;
        // 是否启用语义缓存
        private boolean semanticEnabled = false;
        // 语义缓存分钟数
        private int semanticMinutes = 10;
        // 语义缓存最大条目数
        private int semanticMaxEntries = 200;
        // 语义相似度阈值
        private double semanticThreshold = 0.95;

        /** 是否启用精确缓存。 */
        public boolean isExactEnabled() {
            return exactEnabled;
        }

        /** 设置是否启用精确缓存。 */
        public void setExactEnabled(boolean exactEnabled) {
            this.exactEnabled = exactEnabled;
        }

        /** 精确缓存分钟数。 */
        public int getExactMinutes() {
            return exactMinutes;
        }

        /** 设置精确缓存分钟数。 */
        public void setExactMinutes(int exactMinutes) {
            this.exactMinutes = exactMinutes;
        }

        /** 精确缓存最大条目数。 */
        public int getExactMaxEntries() {
            return exactMaxEntries;
        }

        /** 设置精确缓存最大条目数。 */
        public void setExactMaxEntries(int exactMaxEntries) {
            this.exactMaxEntries = exactMaxEntries;
        }

        /** 是否启用语义缓存。 */
        public boolean isSemanticEnabled() {
            return semanticEnabled;
        }

        /** 设置是否启用语义缓存。 */
        public void setSemanticEnabled(boolean semanticEnabled) {
            this.semanticEnabled = semanticEnabled;
        }

        /** 语义缓存分钟数。 */
        public int getSemanticMinutes() {
            return semanticMinutes;
        }

        /** 设置语义缓存分钟数。 */
        public void setSemanticMinutes(int semanticMinutes) {
            this.semanticMinutes = semanticMinutes;
        }

        /** 语义缓存最大条目数。 */
        public int getSemanticMaxEntries() {
            return semanticMaxEntries;
        }

        /** 设置语义缓存最大条目数。 */
        public void setSemanticMaxEntries(int semanticMaxEntries) {
            this.semanticMaxEntries = semanticMaxEntries;
        }

        /** 语义相似度阈值。 */
        public double getSemanticThreshold() {
            return semanticThreshold;
        }

        /** 设置语义相似度阈值。 */
        public void setSemanticThreshold(double semanticThreshold) {
            this.semanticThreshold = semanticThreshold;
        }
    }
}
