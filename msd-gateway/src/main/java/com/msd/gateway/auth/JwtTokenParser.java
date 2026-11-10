package com.msd.gateway.auth;

import com.msd.common.auth.JwtSecretGuard;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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
 * JWT 解析与校验。
 */
@Component
public class JwtTokenParser {

    private final SecretKey key; // 密钥
    private final String issuer; // JWT 标准声明 iss（issuer）的期望值

    /**
     * 初始化 HS256 密钥和签发方。
     */
    public JwtTokenParser(@Value("${msd.auth.jwt.secret}") String secret,
                          @Value("${msd.auth.jwt.issuer:msd}") String issuer,
                          Environment environment) {
        // 1. 非 dev/test 环境禁止使用公开默认密钥
        JwtSecretGuard.validate(secret,
                environment.acceptsProfiles(Profiles.of("dev", "test")));
        // 2. 把字符串密钥按 UTF-8 转成字节数组
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        // 3. 把原始字节包装成密钥，并声明算法为 HmacSHA256
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.issuer = issuer;
    }

    /**
     * 签发 HS256 Token，仅供本地 dev 测试台使用。
     */
    public String issue(String userId, String role, Duration ttl) {
        // 1. 以当前时间为签发时间，按传入 TTL 计算过期时间
        Instant now = Instant.now();
        // 2. 写入 sub/role/iss/iat/exp，使用与解析器相同的密钥签名
        return Jwts.builder()
                .subject(userId)
                .claim("role", role)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 返回当前签发方，便于测试台展示。
     */
    public String issuer() {
        return issuer;
    }

    /**
     * 校验签名、签发方和过期时间，返回用户信息。
     */
    public JwtUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)          //设置验签密钥
                .requireIssuer(issuer)    //强制校验 iss 声明
                .build()
                .parseSignedClaims(token) //解析并验签
                .getPayload();            //取出 Claims(本质是 Map<String, Object>)
        String userId = claims.getSubject();  //取标准声明 sub
        String role = claims.get("role", String.class);  //取自定义声明 role，并显式指定类型
        if (userId == null || role == null || claims.getExpiration() == null) {
            throw new JwtException("missing sub, role or exp claim");
        }
        return new JwtUser(userId, role);
    }
}
