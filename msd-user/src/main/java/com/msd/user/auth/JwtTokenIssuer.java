package com.msd.user.auth;

import com.msd.common.auth.JwtSecretGuard;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 登录 Token 签发器。
 *
 * <p>密钥、issuer 和声明必须与网关的 JwtTokenParser 完全一致，
 * 否则网关会拒绝用户服务签发的 Token。</p>
 */
@Component
public class JwtTokenIssuer {

    // HS256 签名密钥
    private final SecretKey key;
    // JWT 签发方，必须与网关配置一致
    private final String issuer;
    // Token 有效期（秒）
    private final long ttlSeconds;

    /**
     * 初始化 HS256 密钥、签发方和有效期。
     */
    public JwtTokenIssuer(@Value("${msd.auth.jwt.secret}") String secret,
                          @Value("${msd.auth.jwt.issuer:msd}") String issuer,
                          @Value("${msd.auth.jwt.ttl-seconds:86400}") long ttlSeconds,
                          Environment environment) {
        // 1. 非 dev/test 环境禁止使用公开默认密钥
        JwtSecretGuard.validate(secret,
                environment.acceptsProfiles(Profiles.of("dev", "test")));
        // 2. 密钥按 UTF-8 转字节，不足 32 字节直接启动失败
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        // 3. 包装成 HmacSHA256 密钥，并限制最短有效期
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.issuer = issuer;
        this.ttlSeconds = Math.max(60L, ttlSeconds);
    }

    /**
     * 签发 HS256 Token。
     */
    public String issue(Long userId, String role) {
        // 1. 以当前时间为签发时间
        Instant now = Instant.now();
        // 2. 写入与网关一致的 sub/role/iss/iat/exp
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofSeconds(ttlSeconds))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 返回登录 Token 的有效期（秒）。
     */
    public long ttlSeconds() {
        return ttlSeconds;
    }
}
