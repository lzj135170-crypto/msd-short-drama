package com.msd.drama.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多级缓存配置，默认值保证 application.yml 未配置时也能运行。
 */
@ConfigurationProperties(prefix = "msd.cache")
public class CacheProperties {

    // 是否启用多级缓存
    private boolean enabled = true;
    // L1 缓存最大条目数
    private long maxSize = 20_000;
    // 缓存命名空间到策略的映射
    private Map<String, Policy> policies = new LinkedHashMap<>();
    // 击穿锁参数
    private Lock lock = new Lock();
    // 写路径失效参数
    private Evict evict = new Evict();
    // 布隆过滤器参数
    private Bloom bloom = new Bloom();
    // Redis 故障退避参数
    private Redis redis = new Redis();
    // 压测 demo 参数
    private Demo demo = new Demo();

    /**
     * 初始化默认的 home、detail 两种缓存策略。
     */
    public CacheProperties() {
        policies.put("home", Policy.home());
        policies.put("detail", Policy.detail());
    }

    /**
     * 取策略，缺失时使用 detail 默认值。
     */
    public Policy policy(String name) {
        return policies.getOrDefault(name, Policy.defaults());
    }

    /**
     * 判断多级缓存是否启用。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用多级缓存。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取 L1 缓存最大条目数。
     */
    public long getMaxSize() {
        return maxSize;
    }

    /**
     * 设置 L1 缓存最大条目数。
     */
    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * 获取缓存策略映射。
     */
    public Map<String, Policy> getPolicies() {
        return policies;
    }

    /**
     * 设置缓存策略映射；传入 null 时重置为空映射。
     */
    public void setPolicies(Map<String, Policy> policies) {
        this.policies = policies == null ? new LinkedHashMap<>() : policies;
    }

    /**
     * 获取击穿锁参数。
     */
    public Lock getLock() {
        return lock;
    }

    /**
     * 设置击穿锁参数；传入 null 时重置为默认值。
     */
    public void setLock(Lock lock) {
        this.lock = lock == null ? new Lock() : lock;
    }

    /**
     * 获取写路径失效参数。
     */
    public Evict getEvict() {
        return evict;
    }

    /**
     * 设置写路径失效参数；传入 null 时重置为默认值。
     */
    public void setEvict(Evict evict) {
        this.evict = evict == null ? new Evict() : evict;
    }

    /**
     * 获取布隆过滤器参数。
     */
    public Bloom getBloom() {
        return bloom;
    }

    /**
     * 设置布隆过滤器参数；传入 null 时重置为默认值。
     */
    public void setBloom(Bloom bloom) {
        this.bloom = bloom == null ? new Bloom() : bloom;
    }

    /**
     * 获取 Redis 故障退避参数。
     */
    public Redis getRedis() {
        return redis;
    }

    /**
     * 设置 Redis 故障退避参数；传入 null 时重置为默认值。
     */
    public void setRedis(Redis redis) {
        this.redis = redis == null ? new Redis() : redis;
    }

    /**
     * 获取压测 demo 参数。
     */
    public Demo getDemo() {
        return demo;
    }

    /**
     * 设置压测 demo 参数；传入 null 时重置为默认值。
     */
    public void setDemo(Demo demo) {
        this.demo = demo == null ? new Demo() : demo;
    }

    /**
     * 单个缓存策略的 TTL。
     */
    public static class Policy {

        // L1 缓存过期时间
        private Duration l1Ttl = Duration.ofSeconds(10);
        // L2 缓存过期时间
        private Duration l2Ttl = Duration.ofSeconds(30);
        // 空值缓存过期时间，用于防止穿透
        private Duration nullTtl = Duration.ofSeconds(30);

        /**
         * 首页默认策略：短 TTL，尽快感知互动计数变化。
         */
        static Policy home() {
            Policy policy = new Policy();
            policy.setL1Ttl(Duration.ofSeconds(10));
            policy.setL2Ttl(Duration.ofSeconds(30));
            policy.setNullTtl(Duration.ofSeconds(30));
            return policy;
        }

        /**
         * 详情默认策略：较长 TTL，写路径通过失效器保证一致性。
         */
        static Policy detail() {
            Policy policy = new Policy();
            policy.setL1Ttl(Duration.ofSeconds(30));
            policy.setL2Ttl(Duration.ofSeconds(300));
            policy.setNullTtl(Duration.ofSeconds(30));
            return policy;
        }

        /**
         * 未配置策略名时使用的默认策略。
         */
        static Policy defaults() {
            return detail();
        }

        /**
         * 获取 L1 缓存过期时间。
         */
        public Duration getL1Ttl() {
            return l1Ttl;
        }

        /**
         * 设置 L1 缓存过期时间。
         */
        public void setL1Ttl(Duration l1Ttl) {
            this.l1Ttl = l1Ttl;
        }

        /**
         * 获取 L2 缓存过期时间。
         */
        public Duration getL2Ttl() {
            return l2Ttl;
        }

        /**
         * 设置 L2 缓存过期时间。
         */
        public void setL2Ttl(Duration l2Ttl) {
            this.l2Ttl = l2Ttl;
        }

        /**
         * 获取空值缓存过期时间。
         */
        public Duration getNullTtl() {
            return nullTtl;
        }

        /**
         * 设置空值缓存过期时间。
         */
        public void setNullTtl(Duration nullTtl) {
            this.nullTtl = nullTtl;
        }
    }

    /**
     * 击穿锁参数。
     */
    public static class Lock {

        // 获取锁的最长等待时间
        private Duration wait = Duration.ofSeconds(2);
        // 自旋重试间隔
        private Duration spin = Duration.ofMillis(50);
        // 锁租期，避免实例宕机后锁无法释放
        private Duration lease = Duration.ofSeconds(30);

        /**
         * 获取锁的最长等待时间。
         */
        public Duration getWait() {
            return wait;
        }

        /**
         * 设置锁的最长等待时间。
         */
        public void setWait(Duration wait) {
            this.wait = wait;
        }

        /**
         * 获取自旋重试间隔。
         */
        public Duration getSpin() {
            return spin;
        }

        /**
         * 设置自旋重试间隔。
         */
        public void setSpin(Duration spin) {
            this.spin = spin;
        }

        /**
         * 获取锁租期。
         */
        public Duration getLease() {
            return lease;
        }

        /**
         * 设置锁租期。
         */
        public void setLease(Duration lease) {
            this.lease = lease;
        }
    }

    /**
     * 写路径失效参数。
     */
    public static class Evict {

        // 第二次删除的默认延迟
        private Duration delay = Duration.ofMillis(500);
        // 首页失效防抖窗口
        private Duration homeDebounce = Duration.ofSeconds(30);
        // 详情失效防抖窗口
        private Duration detailDebounce = Duration.ofSeconds(2);
        // 是否广播失效消息给其他实例
        private boolean broadcastEnabled = false;

        /**
         * 获取第二次删除的默认延迟。
         */
        public Duration getDelay() {
            return delay;
        }

        /**
         * 设置第二次删除的默认延迟。
         */
        public void setDelay(Duration delay) {
            this.delay = delay;
        }

        /**
         * 获取首页失效防抖窗口。
         */
        public Duration getHomeDebounce() {
            return homeDebounce;
        }

        /**
         * 设置首页失效防抖窗口。
         */
        public void setHomeDebounce(Duration homeDebounce) {
            this.homeDebounce = homeDebounce;
        }

        /**
         * 获取详情失效防抖窗口。
         */
        public Duration getDetailDebounce() {
            return detailDebounce;
        }

        /**
         * 设置详情失效防抖窗口。
         */
        public void setDetailDebounce(Duration detailDebounce) {
            this.detailDebounce = detailDebounce;
        }

        /**
         * 判断是否启用失效消息广播。
         */
        public boolean isBroadcastEnabled() {
            return broadcastEnabled;
        }

        /**
         * 设置是否启用失效消息广播。
         */
        public void setBroadcastEnabled(boolean broadcastEnabled) {
            this.broadcastEnabled = broadcastEnabled;
        }
    }

    /**
     * 布隆过滤器参数。
     */
    public static class Bloom {

        // 是否启用布隆过滤器
        private boolean enabled = true;
        // Redis 中布隆过滤器 key 的前缀
        private String keyPrefix = "msd:drama:bloom";
        // 预期插入数量，用于计算位数组大小
        private long expectedInsertions = 100_000L;
        // 允许的误判率
        private double falseProbability = 0.01D;
        // active 过滤器过期时间
        private Duration filterTtl = Duration.ofHours(24);
        // 被替换的旧过滤器保留时间，保证重建期间读请求仍可用
        private Duration oldFilterTtl = Duration.ofMinutes(10);
        // active 指针本地缓存时间，减少 Redis 往返
        private Duration pointerCacheTtl = Duration.ofSeconds(10);
        // 重建时每批读取的剧 ID 数量
        private int pageSize = 1_000;

        /**
         * 判断是否启用布隆过滤器。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用布隆过滤器。
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取布隆过滤器 key 前缀。
         */
        public String getKeyPrefix() {
            return keyPrefix;
        }

        /**
         * 设置布隆过滤器 key 前缀。
         */
        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        /**
         * 获取预期插入数量。
         */
        public long getExpectedInsertions() {
            return expectedInsertions;
        }

        /**
         * 设置预期插入数量。
         */
        public void setExpectedInsertions(long expectedInsertions) {
            this.expectedInsertions = expectedInsertions;
        }

        /**
         * 获取允许的误判率。
         */
        public double getFalseProbability() {
            return falseProbability;
        }

        /**
         * 设置允许的误判率。
         */
        public void setFalseProbability(double falseProbability) {
            this.falseProbability = falseProbability;
        }

        /**
         * 获取 active 过滤器过期时间。
         */
        public Duration getFilterTtl() {
            return filterTtl;
        }

        /**
         * 设置 active 过滤器过期时间。
         */
        public void setFilterTtl(Duration filterTtl) {
            this.filterTtl = filterTtl;
        }

        /**
         * 获取旧过滤器保留时间。
         */
        public Duration getOldFilterTtl() {
            return oldFilterTtl;
        }

        /**
         * 设置旧过滤器保留时间。
         */
        public void setOldFilterTtl(Duration oldFilterTtl) {
            this.oldFilterTtl = oldFilterTtl;
        }

        /**
         * 获取 active 指针本地缓存时间。
         */
        public Duration getPointerCacheTtl() {
            return pointerCacheTtl;
        }

        /**
         * 设置 active 指针本地缓存时间。
         */
        public void setPointerCacheTtl(Duration pointerCacheTtl) {
            this.pointerCacheTtl = pointerCacheTtl;
        }

        /**
         * 获取重建时每批读取的剧 ID 数量。
         */
        public int getPageSize() {
            return pageSize;
        }

        /**
         * 设置重建时每批读取的剧 ID 数量。
         */
        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }
    }

    /**
     * 虚拟线程 / RestClient 压测 demo 参数。
     */
    public static class Demo {

        // 是否启用压测 demo 接口
        private boolean enabled = true;
        // msd-agent-service 直连地址
        private String aiBaseUrl = "http://127.0.0.1:8105";
        // 建立连接超时
        private Duration connectTimeout = Duration.ofSeconds(2);
        // 读取响应超时
        private Duration readTimeout = Duration.ofSeconds(10);
        // 单次扇出允许的最大调用数
        private int maxCalls = 200;

        /**
         * 判断是否启用压测 demo 接口。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用压测 demo 接口。
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取 msd-agent-service 直连地址。
         */
        public String getAiBaseUrl() {
            return aiBaseUrl;
        }

        /**
         * 设置 msd-agent-service 直连地址。
         */
        public void setAiBaseUrl(String aiBaseUrl) {
            this.aiBaseUrl = aiBaseUrl;
        }

        /**
         * 获取建立连接超时。
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /**
         * 设置建立连接超时。
         */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /**
         * 获取读取响应超时。
         */
        public Duration getReadTimeout() {
            return readTimeout;
        }

        /**
         * 设置读取响应超时。
         */
        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        /**
         * 获取单次扇出允许的最大调用数。
         */
        public int getMaxCalls() {
            return maxCalls;
        }

        /**
         * 设置单次扇出允许的最大调用数。
         */
        public void setMaxCalls(int maxCalls) {
            this.maxCalls = maxCalls;
        }
    }

    /**
     * Redis 不可用时的退避时间。
     */
    public static class Redis {

        // Redis 失败后的退避时间
        private Duration failureBackoff = Duration.ofSeconds(15);

        /**
         * 获取 Redis 失败后的退避时间。
         */
        public Duration getFailureBackoff() {
            return failureBackoff;
        }

        /**
         * 设置 Redis 失败后的退避时间。
         */
        public void setFailureBackoff(Duration failureBackoff) {
            this.failureBackoff = failureBackoff;
        }
    }
}
