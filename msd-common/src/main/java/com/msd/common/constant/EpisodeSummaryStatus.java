package com.msd.common.constant;

/**
 * 分集摘要状态，对应 t_drama_episode.summary_status。
 */
public final class EpisodeSummaryStatus {

    /**
     * 工具类禁止实例化。
     */
    private EpisodeSummaryStatus() {
    }

    /** 缺失，可以被认领生成。 */
    public static final int MISSING = 0;

    /** 生成中，超过 60 秒视为陈旧并可被重新认领。 */
    public static final int GENERATING = 1;

    /** 可用，摘要已写入。 */
    public static final int READY = 2;

    /** 生成失败，可以被重新认领。 */
    public static final int FAILED = 3;
}
