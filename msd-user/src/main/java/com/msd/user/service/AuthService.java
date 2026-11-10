package com.msd.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.user.auth.JwtTokenIssuer;
import com.msd.user.dto.AuthResult;
import com.msd.user.dto.LoginRequest;
import com.msd.user.dto.RegisterRequest;
import com.msd.user.dto.UpdateProfileRequest;
import com.msd.user.dto.UserProfile;
import com.msd.user.entity.User;
import com.msd.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户认证：注册、登录和当前用户资料。
 */
@Service
public class AuthService {

    /** 用户正常状态值。 */
    private static final int STATUS_ACTIVE = 1;

    // 用户表 Mapper
    private final UserMapper userMapper;
    // 密码编码器，负责 BCrypt 哈希与校验
    private final BCryptPasswordEncoder passwordEncoder;
    // Token 签发器
    private final JwtTokenIssuer tokenIssuer;

    /**
     * 注入用户 Mapper、密码编码器和 Token 签发器。
     */
    public AuthService(UserMapper userMapper,
                       BCryptPasswordEncoder passwordEncoder,
                       JwtTokenIssuer tokenIssuer) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * 注册新用户并直接签发登录 Token。
     */
    @Transactional
    public AuthResult register(RegisterRequest request) {
        // 1. 规整手机号和昵称
        String phone = request.phone().trim();
        String nickname = normalizeNickname(request.nickname(), phone);

        // 2. 密码必须同时包含字母和数字
        validatePassword(request.password());

        // 3. 先按手机号查重，给出明确的业务提示
        if (findByPhone(phone) != null) {
            throw new BizException(ResultCode.CONFLICT, "手机号已注册");
        }

        // 4. 组装用户，密码只保存 BCrypt 哈希
        User user = new User();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(nickname);
        user.setAvatarUrl("");
        user.setBio("");
        user.setRole(0);
        user.setStatus(STATUS_ACTIVE);
        user.setCreateTime(LocalDateTime.now());

        // 5. 插入用户；并发注册撞唯一键时同样转成 409
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BizException(ResultCode.CONFLICT, "手机号已注册");
        }

        // 6. 注册成功即登录
        return buildAuthResult(user);
    }

    /**
     * 校验手机号和密码，成功后更新登录信息并签发 Token。
     */
    public AuthResult login(LoginRequest request, String clientIp) {
        // 1. 按手机号查询用户
        User user = findByPhone(request.phone().trim());

        // 2. 用户不存在和密码错误返回同一个提示，避免暴露账号是否存在
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "手机号或密码错误");
        }

        // 3. 禁用账号不允许登录
        if (!Integer.valueOf(STATUS_ACTIVE).equals(user.getStatus())) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已禁用");
        }

        // 4. 记录本次登录时间和来源 IP
        updateLastLogin(user.getId(), clientIp);

        // 5. 签发 Token 并返回用户资料
        return buildAuthResult(user);
    }

    /**
     * 查询当前用户资料，禁用或不存在时按未登录处理。
     */
    public UserProfile getProfile(Long userId) {
        // 1. 按主键查询用户
        User user = userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(STATUS_ACTIVE).equals(user.getStatus())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户不存在或已禁用");
        }
        // 2. 只返回可公开字段
        return toProfile(user);
    }

    /**
     * 更新当前用户资料，只修改请求中提供的字段。
     */
    public UserProfile updateProfile(Long userId, UpdateProfileRequest request) {
        // 1. 查询用户并校验状态
        User user = userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(STATUS_ACTIVE).equals(user.getStatus())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户不存在或已禁用");
        }

        // 2. 归一化非空字段；null 表示不修改，空字符串表示清空头像/简介
        String nickname = normalizeNicknameForUpdate(request.nickname());
        String avatarUrl = request.avatarUrl() == null ? null : request.avatarUrl().trim();
        String bio = request.bio() == null ? null : request.bio().trim();

        // 3. 没有任何字段需要修改时直接返回当前资料
        if (nickname == null && avatarUrl == null && bio == null) {
            return toProfile(user);
        }

        // 4. 只更新非 null 字段，避免覆盖密码、手机号和角色
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId);
        if (nickname != null) {
            wrapper.set(User::getNickname, nickname);
        }
        if (avatarUrl != null) {
            wrapper.set(User::getAvatarUrl, avatarUrl);
        }
        if (bio != null) {
            wrapper.set(User::getBio, bio);
        }
        userMapper.update(null, wrapper);

        // 5. 重新查询并返回最新资料
        return toProfile(userMapper.selectById(userId));
    }

    /**
     * 数据库角色值转接口角色名。
     */
    public String roleName(Integer role) {
        // 1. 1 为创作者，2 为管理员，其余按普通用户处理
        return switch (role == null ? 0 : role) {
            case 1 -> "CREATOR";
            case 2 -> "ADMIN";
            default -> "USER";
        };
    }

    /**
     * 按手机号查询用户。
     */
    private User findByPhone(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone)
                .last("LIMIT 1"));
    }

    /**
     * 更新最近登录时间和 IP。
     */
    private void updateLastLogin(Long userId, String clientIp) {
        // 1. 只更新登录相关字段，避免把整行用户数据重新写回
        String safeIp = clientIp == null ? "" : clientIp.trim();
        if (safeIp.length() > 45) {
            safeIp = safeIp.substring(0, 45);
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getLastLoginTime, LocalDateTime.now())
                .set(User::getLastLoginIp, safeIp));
    }

    /**
     * 密码强度校验：至少包含一个字母和一个数字。
     */
    private void validatePassword(String password) {
        // 1. 长度由 DTO 校验，这里补充字符类型要求
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BizException(ResultCode.PARAM_ERROR, "密码需同时包含字母和数字");
        }
    }

    /**
     * 资料更新时的昵称校验：传 null 表示不修改，trim 后不能为空。
     */
    private String normalizeNicknameForUpdate(String nickname) {
        // 1. null 表示不修改
        if (nickname == null) {
            return null;
        }
        // 2. 去掉首尾空白后不能为空
        String value = nickname.trim();
        if (value.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "昵称不能为空");
        }
        // 3. 兜底长度校验
        if (value.length() > 20) {
            throw new BizException(ResultCode.PARAM_ERROR, "昵称最多 20 个字符");
        }
        return value;
    }

    /**
     * 昵称为空时使用手机号后四位生成默认昵称。
     */
    private String normalizeNickname(String nickname, String phone) {
        // 1. 去掉首尾空白
        String value = nickname == null ? "" : nickname.trim();

        // 2. 空昵称使用「剧迷 + 后四位」
        if (value.isEmpty()) {
            return "剧迷" + phone.substring(phone.length() - 4);
        }

        // 3. 兜底长度校验，避免绕过 DTO 校验
        if (value.length() > 20) {
            throw new BizException(ResultCode.PARAM_ERROR, "昵称最多 20 个字符");
        }
        return value;
    }

    /**
     * 组装认证结果并签发 Token。
     */
    private AuthResult buildAuthResult(User user) {
        // 1. 角色值转角色名
        String role = roleName(user.getRole());

        // 2. 签发与网关兼容的 JWT
        String token = tokenIssuer.issue(user.getId(), role);

        // 3. 返回 Token 有效期和用户资料
        return new AuthResult(token, tokenIssuer.ttlSeconds(), toProfile(user));
    }

    /**
     * 实体转用户资料 DTO。
     */
    private UserProfile toProfile(User user) {
        return new UserProfile(
                user.getId(),
                user.getPhone(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getBio(),
                roleName(user.getRole()),
                user.getCreateTime()
        );
    }
}
