package com.msd.gateway.dev;

import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.gateway.auth.JwtTokenParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Set;

/**
 * 本地测试台用的 Token 签发接口。
 * 仅在 dev profile 且显式开启开关时加载，不能用于测试/生产环境。
 */
@RestController
@Profile("dev")
@ConditionalOnProperty(prefix = "msd.auth.dev-token", name = "enabled", havingValue = "true")
@RequestMapping("/internal/dev")
public class DevTokenController {

    /** 允许签发的角色集合。 */
    private static final Set<String> ROLES = Set.of("USER", "CREATOR", "ADMIN");

    // Token 解析器，负责实际签发
    private final JwtTokenParser tokenParser;
    // 开发 Token 有效期（秒），已限制在 60 到 86400 之间
    private final long ttlSeconds;

    /**
     * 注入 Token 解析器和开发 Token 有效期。
     */
    public DevTokenController(JwtTokenParser tokenParser,
                              @Value("${msd.auth.dev-token.ttl-seconds:7200}") long ttlSeconds) {
        this.tokenParser = tokenParser;
        // 1. 限制有效期在 60 秒到 24 小时之间，避免误配置
        this.ttlSeconds = Math.max(60, Math.min(ttlSeconds, 86400));
    }

    /**
     * 为指定用户和角色签发 JWT。
     */
    @PostMapping("/token")
    public Result<DevToken> issue(@RequestBody(required = false) DevTokenRequest request) {
        // 1. 缺省使用 1003/USER，便于页面直接连接
        String rawUserId = request == null || request.userId() == null || request.userId().isBlank()
                ? "1003" : request.userId().trim();
        String role = request == null || request.role() == null || request.role().isBlank()
                ? "USER" : request.role().trim().toUpperCase();

        // 2. 校验用户 ID 和角色
        long userId;
        try {
            userId = Long.parseLong(rawUserId);
        } catch (NumberFormatException ex) {
            return Result.fail(ResultCode.PARAM_ERROR.getCode(), "userId 必须是数字");
        }
        if (userId <= 0) {
            return Result.fail(ResultCode.PARAM_ERROR.getCode(), "userId 必须大于 0");
        }
        if (!ROLES.contains(role)) {
            return Result.fail(ResultCode.PARAM_ERROR.getCode(), "role 只能是 USER/CREATOR/ADMIN");
        }

        // 3. 签发 Token 并返回有效期
        String token = tokenParser.issue(Long.toString(userId), role, Duration.ofSeconds(ttlSeconds));
        return Result.ok(new DevToken(token, ttlSeconds, userId, role, tokenParser.issuer()));
    }

    /**
     * Token 申请参数。
     *
     * @param userId 用户 ID，缺省使用 1003
     * @param role   角色，缺省使用 USER
     */
    public record DevTokenRequest(String userId, String role) {
    }

    /**
     * Token 返回结果。
     *
     * @param token            签发的 JWT
     * @param expiresInSeconds 有效期（秒）
     * @param userId           用户 ID
     * @param role             角色
     * @param issuer           签发方
     */
    public record DevToken(String token,
                           long expiresInSeconds,
                           long userId,
                           String role,
                           String issuer) {
    }
}
