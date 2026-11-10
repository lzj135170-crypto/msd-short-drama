package com.msd.common.dto;

/**
 * 用户信息，供服务间 Feign 调用复用。
 *
 * @param id           用户 ID
 * @param nickname     昵称
 * @param avatarUrl    头像地址
 * @param role         角色：USER / CREATOR / ADMIN
 * @param instancePort 响应实例端口，仅用于演示负载均衡
 */
public record UserInfoDTO(Long id, String nickname, String avatarUrl, String role, Integer instancePort) {
}
