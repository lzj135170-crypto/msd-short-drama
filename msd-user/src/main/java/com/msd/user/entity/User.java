package com.msd.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 t_user。
 *
 * <p>密码哈希只用于登录校验，任何接口都不能把它序列化给客户端。</p>
 */
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    // 用户 ID，主键自增
    private Long id;
    // 手机号，登录账号且唯一
    private String phone;
    // BCrypt 密码哈希
    private String passwordHash;
    // 昵称
    private String nickname;
    // 头像地址
    private String avatarUrl;
    // 个人简介
    private String bio;
    // 角色：0 普通用户，1 创作者，2 管理员
    private Integer role;
    // 状态：1 正常，0 禁用
    private Integer status;
    // 最近登录时间
    private LocalDateTime lastLoginTime;
    // 最近登录 IP
    private String lastLoginIp;
    // 注册时间
    private LocalDateTime createTime;

    /**
     * 获取用户 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置用户 ID。
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取手机号。
     */
    public String getPhone() {
        return phone;
    }

    /**
     * 设置手机号。
     */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    /**
     * 获取密码哈希。
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * 设置密码哈希。
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * 获取昵称。
     */
    public String getNickname() {
        return nickname;
    }

    /**
     * 设置昵称。
     */
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 获取头像地址。
     */
    public String getAvatarUrl() {
        return avatarUrl;
    }

    /**
     * 设置头像地址。
     */
    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    /**
     * 获取个人简介。
     */
    public String getBio() {
        return bio;
    }

    /**
     * 设置个人简介。
     */
    public void setBio(String bio) {
        this.bio = bio;
    }

    /**
     * 获取角色值。
     */
    public Integer getRole() {
        return role;
    }

    /**
     * 设置角色值。
     */
    public void setRole(Integer role) {
        this.role = role;
    }

    /**
     * 获取状态值。
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 设置状态值。
     */
    public void setStatus(Integer status) {
        this.status = status;
    }

    /**
     * 获取最近登录时间。
     */
    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    /**
     * 设置最近登录时间。
     */
    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    /**
     * 获取最近登录 IP。
     */
    public String getLastLoginIp() {
        return lastLoginIp;
    }

    /**
     * 设置最近登录 IP。
     */
    public void setLastLoginIp(String lastLoginIp) {
        this.lastLoginIp = lastLoginIp;
    }

    /**
     * 获取注册时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置注册时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
