package com.msd.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * @param phone    手机号
 * @param password 密码，注册时还需通过字母加数字的强度校验
 * @param nickname 昵称，为空时使用手机号后四位自动生成
 */
public record RegisterRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 32, message = "密码长度需为 8-32 位")
        String password,

        @Size(max = 20, message = "昵称最多 20 个字符")
        String nickname
) {
}
