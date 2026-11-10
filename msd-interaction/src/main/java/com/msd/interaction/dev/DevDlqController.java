package com.msd.interaction.dev;

import com.msd.common.event.KafkaTopics;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.interaction.dto.DlqInjectResult;
import com.msd.interaction.dto.DlqInspection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 本地测试台用的 DLQ 故障接口，仅在 dev 且显式开启时加载。
 */
@RestController
@Profile("dev")
@ConditionalOnProperty(prefix = "msd.dev.faults", name = "enabled", havingValue = "true")
@RequestMapping("/internal/interactions/dev")
public class DevDlqController {

    // Kafka 模板，用于向业务 topic 投递非法消息
    private final KafkaTemplate<String, String> kafkaTemplate;
    // DLQ 只读巡检器
    private final DlqInspector dlqInspector;

    /**
     * 注入 Kafka 客户端和 DLQ 巡检器。
     */
    public DevDlqController(KafkaTemplate<String, String> kafkaTemplate,
                            DlqInspector dlqInspector) {
        this.kafkaTemplate = kafkaTemplate;
        this.dlqInspector = dlqInspector;
    }

    /**
     * 向点赞 topic 投递一条非法 JSON，触发消费者重试和 DLQ。
     */
    @PostMapping("/dlq-event")
    public Result<DlqInjectResult> inject() {
        // 1. 构造非法消息并同步发送，确保返回时消息已写入 Kafka
        String key = "dev-dlq-" + System.currentTimeMillis();
        String payload = "{\"broken\":";
        try {
            kafkaTemplate.send(KafkaTopics.INTERACTION_LIKE, key, payload)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "投递失败: " + ex.getMessage());
        }
        // 2. 返回提示，页面随后轮询 DLQ
        return Result.ok(new DlqInjectResult(KafkaTopics.INTERACTION_LIKE, key,
                "已投递，约 4-8 秒后进入 DLQ；请确认 drama 消费组正在运行"));
    }

    /**
     * 只读查看 DLQ 最近消息。
     */
    @GetMapping("/dlq-messages")
    public Result<DlqInspection> messages(
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        // 1. 读取 DLQ 尾部消息
        return Result.ok(dlqInspector.inspect(limit));
    }
}
