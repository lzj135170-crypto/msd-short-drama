package com.msd.user.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.dto.IdBatchRequest;
import com.msd.common.dto.UserInfoDTO;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.user.dto.UpdateProfileRequest;
import com.msd.user.dto.UserProfile;
import com.msd.user.entity.User;
import com.msd.user.mapper.UserMapper;
import com.msd.user.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户信息接口，数据来自 t_user 表。
 */
@RestController
@RequestMapping("/internal/users")
public class UserController {

    // 用户表 Mapper
    private final UserMapper userMapper;
    // 认证服务
    private final AuthService authService;
    private final Integer instancePort; // 当前实例端口，用于演示负载均衡

    /**
     * 注入 Mapper、认证服务和当前实例端口。
     */
    public UserController(UserMapper userMapper,
                          AuthService authService,
                          @Value("${server.port}") Integer instancePort) {
        this.userMapper = userMapper;
        this.authService = authService;
        this.instancePort = instancePort;
    }

    /**
     * 按 ID 查询用户信息。
     */
    @GetMapping("/{id}")
    public Result<UserInfoDTO> getById(@PathVariable Long id) {
        // 1. 按主键查询用户
        User user = userMapper.selectById(id);
        // 2. 不存在或已禁用时统一返回 NOT_FOUND，避免暴露禁用账号
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            return Result.fail(ResultCode.NOT_FOUND);
        }
        // 3. 数据库角色值转成接口角色名
        String role = roleName(user.getRole());
        // 4. 附带实例端口，调用方可以据此观察命中的节点
        return Result.ok(new UserInfoDTO(user.getId(), user.getNickname(), user.getAvatarUrl(), role, instancePort));
    }

    /**
     * 查询当前登录用户资料。
     */
    @GetMapping("/me")
    public Result<UserProfile> me(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析网关注入的用户 ID
        Long userId = parseUserId(userIdHeader);

        // 2. 查询资料，禁用用户按未登录处理
        return Result.ok(authService.getProfile(userId));
    }

    /**
     * 更新当前登录用户资料。
     */
    @PutMapping("/me")
    public Result<UserProfile> updateMe(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @Valid @RequestBody UpdateProfileRequest request) {
        // 1. 解析网关注入的用户 ID
        Long userId = parseUserId(userIdHeader);

        // 2. 只更新请求中提供的字段
        return Result.ok(authService.updateProfile(userId, request));
    }

    /**
     * 批量查询用户公开信息，供互动列表补齐作者昵称/头像。
     */
    @PostMapping("/batch")
    public Result<List<UserInfoDTO>> batch(@Valid @RequestBody IdBatchRequest request) {
        // 1. 批量查询后只保留正常用户
        List<UserInfoDTO> users = userMapper.selectByIds(request.ids()).stream()
                .filter(user -> Integer.valueOf(1).equals(user.getStatus()))
                .map(user -> new UserInfoDTO(
                        user.getId(),
                        user.getNickname(),
                        user.getAvatarUrl(),
                        roleName(user.getRole()),
                        instancePort))
                .toList();

        // 2. 返回用户公开信息
        return Result.ok(users);
    }

    /**
     * 解析 X-User-Id，缺失或非法时按未登录处理。
     */
    private Long parseUserId(String userIdHeader) {
        // 1. 网关未注入用户 ID
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        // 2. 非数字同样视为未登录
        try {
            return Long.parseLong(userIdHeader);
        } catch (NumberFormatException ex) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }

    /**
     * 数据库角色值（0/1/2）转接口角色名。
     */
    private String roleName(Integer role) {
        // 1. 1 为创作者，2 为管理员，其余按普通用户处理
        return switch (role == null ? 0 : role) {
            case 1 -> "CREATOR";
            case 2 -> "ADMIN";
            default -> "USER";
        };
    }
}
