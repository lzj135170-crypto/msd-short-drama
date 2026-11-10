package com.msd.user.dto;

/**
 * 登录或注册成功后的认证结果。
 *
 * @param token            签发的 JWT
 * @param expiresInSeconds 有效期（秒）
 * @param user             用户资料
 */
public record AuthResult(
        String token,
        long expiresInSeconds,
        UserProfile user
) {
}
