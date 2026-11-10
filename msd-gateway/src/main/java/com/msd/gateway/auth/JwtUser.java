package com.msd.gateway.auth;

/**
 * JWT 中解析出的用户信息。
 *
 * @param userId 用户 ID，对应 JWT 标准声明 sub
 * @param role   用户角色，对应自定义声明 role
 */
public record JwtUser(String userId, String role) {
}
