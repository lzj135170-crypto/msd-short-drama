package com.msd.agent.controller;

import com.msd.common.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 配置查询接口，用于验证 Nacos 配置热更新。
 */
@RefreshScope
@RestController
@RequestMapping("/internal/config")
public class AiConfigController {

    // 模型温度，来自 msd.ai.temperature，支持 Nacos 热刷新
    private final double temperature;

    /**
     * 注入 AI 模型温度。
     */
    public AiConfigController(@Value("${msd.ai.temperature:0.7}") double temperature) {
        this.temperature = temperature;
    }

    /**
     * 返回当前温度。
     */
    @GetMapping("/ai")
    public Result<Map<String, Object>> ai() {
        return Result.ok(Map.of("temperature", temperature));
    }
}
