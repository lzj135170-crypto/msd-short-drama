package com.msd.user.dto;

import java.time.LocalDateTime;

/**
 * 当前用户资料，不包含密码哈希。
 *
 * @param id         用户 ID
 * @param phone      手机号
 * @param nickname   昵称
 * @param avatarUrl  头像地址
 * @param bio        个人简介
 * @param role       角色名：USER / CREATOR / ADMIN
 * @param createTime 注册时间
 */
public record UserProfile(
        Long id,
        String phone,
        String nickname,
        String avatarUrl,
        String bio,
        String role,
        LocalDateTime createTime
) {
}
