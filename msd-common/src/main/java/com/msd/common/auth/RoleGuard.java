package com.msd.common.auth;

import com.msd.common.constant.UserRole;
import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;

/**
 * 请求头角色与用户 ID 校验。
 *
 * <p>网关负责认证并注入 X-User-Id / X-User-Role；本类只做服务内的二次校验，
 * 防止内部接口被越权调用。生产环境仍需在网关和网络层做最终隔离。</p>
 */
public final class RoleGuard {

    /**
     * 工具类禁止实例化。
     */
    private RoleGuard() {
    }

    /**
     * 解析 X-User-Id，缺失或非数字按未登录处理。
     */
    public static Long requireUserId(String header) {
        // 1. 网关未注入用户 ID
        if (header == null || header.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        // 2. 非数字同样视为未登录
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException ex) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }

    /**
     * 要求管理员角色。
     */
    public static void requireAdmin(String role) {
        if (!UserRole.isAdmin(role)) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 要求创作者或管理员角色。
     */
    public static void requireCreatorOrAdmin(String role) {
        if (!UserRole.isCreatorOrAdmin(role)) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 请求头携带角色时要求管理员；不带角色时用于服务间直连或运维调用。
     */
    public static void requireAdminIfPresent(String role) {
        if (role != null && !role.isBlank() && !UserRole.isAdmin(role)) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 判断是否为创作者。
     */
    public static boolean isCreator(String role) {
        return UserRole.isCreator(role);
    }
}
