package com.msd.drama.demo;

/**
 * 并发扇出压测结果；分位统计覆盖全部调用，包含失败调用。
 *
 * @param calls          请求的并发调用数
 * @param mode           线程模式：virtual 或 platform
 * @param pool           平台线程池大小；虚拟线程模式下为请求值
 * @param success        成功调用数
 * @param failed         失败调用数
 * @param degraded       下游降级返回数
 * @param elapsedMs      整批调用耗时，单位毫秒
 * @param p50Ms          延迟 P50，单位毫秒
 * @param p95Ms          延迟 P95，单位毫秒
 * @param maxMs          最大延迟，单位毫秒
 * @param requestVirtual 处理本次请求的线程是否虚拟线程
 * @param requestThread  处理本次请求的线程名
 */
public record AiFanoutResult(
        int calls,
        String mode,
        int pool,
        int success,
        int failed,
        int degraded,
        long elapsedMs,
        long p50Ms,
        long p95Ms,
        long maxMs,
        boolean requestVirtual,
        String requestThread
) {
}
