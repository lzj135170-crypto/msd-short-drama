package com.msd.interaction.controller;

import com.msd.common.dto.UserBehaviorDTO;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.interaction.service.UserBehaviorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间用户行为接口，只允许 Feign 直连，不经过网关。
 */
@RestController
@RequestMapping("/internal-source/users")
public class UserBehaviorController {

    // 用户行为聚合服务
    private final UserBehaviorService behaviorService;

    /**
     * 注入行为聚合服务。
     */
    public UserBehaviorController(UserBehaviorService behaviorService) {
        this.behaviorService = behaviorService;
    }

    /**
     * 查询用户短剧行为权重。
     *
     * @param userId 用户 ID，必须为正整数
     * @return 行为权重、最近互动和收藏剧 ID
     */
    @GetMapping("/{userId}/behavior")
    public Result<UserBehaviorDTO> behavior(@PathVariable("userId") Long userId) {
        // 1. 拒绝非法用户 ID，避免无效查询
        if (userId == null || userId <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户 ID 不合法");
        }
        // 2. 返回聚合结果；空行为也返回成功
        return Result.ok(behaviorService.getBehavior(userId));
    }
}
