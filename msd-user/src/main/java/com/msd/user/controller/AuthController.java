package com.msd.user.controller;

import com.msd.common.result.Result;
import com.msd.user.dto.AuthResult;
import com.msd.user.dto.LoginRequest;
import com.msd.user.dto.RegisterRequest;
import com.msd.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录注册接口。
 *
 * <p>网关白名单为 /api/user/auth/**，因此这两个接口不需要 Token。</p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    // 认证服务
    private final AuthService authService;

    /**
     * 注入认证服务。
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册新用户并返回登录 Token。
     */
    @PostMapping("/register")
    public Result<AuthResult> register(@Valid @RequestBody RegisterRequest request) {
        // 1. 创建用户并直接签发 Token
        return Result.ok(authService.register(request));
    }

    /**
     * 手机号加密码登录。
     */
    @PostMapping("/login")
    public Result<AuthResult> login(@Valid @RequestBody LoginRequest request,
                                    HttpServletRequest servletRequest) {
        // 1. 解析来源 IP，登录成功后会写入 t_user.last_login_ip
        String clientIp = resolveClientIp(servletRequest);

        // 2. 校验密码并签发 Token
        return Result.ok(authService.login(request, clientIp));
    }

    /**
     * 取 X-Forwarded-For 第一段，缺失时回退到直连地址。
     */
    private String resolveClientIp(HttpServletRequest request) {
        // 1. 网关透传的客户端地址在第一个逗号前
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }

        // 2. 直连时使用连接地址
        return request.getRemoteAddr();
    }
}
