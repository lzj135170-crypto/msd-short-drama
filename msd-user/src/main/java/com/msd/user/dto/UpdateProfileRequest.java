package com.msd.user.dto;

import jakarta.validation.constraints.Size;

/**
 * 资料更新请求。
 *
 * <p>字段为 null 表示不修改；头像和简介传空字符串表示清空。</p>
 *
 * @param nickname  昵称，null 表示不修改
 * @param avatarUrl 头像地址，空字符串表示清空
 * @param bio       个人简介，空字符串表示清空
 */
public record UpdateProfileRequest(
        @Size(max = 20, message = "昵称最多 20 个字符")
        String nickname,

        @Size(max = 512, message = "头像地址最多 512 个字符")
        String avatarUrl,

        @Size(max = 255, message = "简介最多 255 个字符")
        String bio
) {
}
