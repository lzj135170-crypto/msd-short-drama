package com.msd.drama.demo;

import com.msd.common.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 虚拟线程 / RestClient 压测接口，仅 dev 环境且 demo 开关打开时注册。
 *
 * <p>路径不在网关路由内，只能直连 8102。生产 profile 不加载。</p>
 */
@RestController
@RequestMapping("/internal-cache/demo")
@Profile("dev")
@ConditionalOnProperty(prefix = "msd.cache.demo", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CacheDemoController {

    // 并发压测服务
    private final AiFanoutDemoService aiFanoutDemoService;

    /**
     * 注入压测服务。
     */
    public CacheDemoController(AiFanoutDemoService aiFanoutDemoService) {
        this.aiFanoutDemoService = aiFanoutDemoService;
    }

    /**
     * 单次 AI 调用，验证请求线程是否为虚拟线程。
     */
    @GetMapping("/ai/single")
    public Result<AiSingleResult> single(
            @RequestParam(defaultValue = "讲个笑话") String prompt) {
        return Result.ok(aiFanoutDemoService.single(prompt));
    }

    /**
     * 并发 AI 调用，对比虚拟线程和固定线程池的吞吐。
     */
    @GetMapping("/ai/fanout")
    public Result<AiFanoutResult> fanout(
            @RequestParam(defaultValue = "50") int calls,
            @RequestParam(defaultValue = "virtual") String mode,
            @RequestParam(defaultValue = "8") int pool,
            @RequestParam(defaultValue = "讲个笑话") String prompt) {
        return Result.ok(aiFanoutDemoService.fanout(calls, mode, pool, prompt));
    }
}
