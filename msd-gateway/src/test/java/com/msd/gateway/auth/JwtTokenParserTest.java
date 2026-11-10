package com.msd.gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 校验网关 JWT 密钥在非开发环境下的启动保护。
 */
class JwtTokenParserTest {

    /** 仓库中公开的本地开发默认密钥。 */
    private static final String DEV_SECRET = "msd-dev-jwt-secret-change-me-32bytes-min";
    /** 生产环境应使用的自定义强密钥。 */
    private static final String STRONG_SECRET = "prod-only-jwt-secret-0123456789abcdef";

    /**
     * dev 环境允许使用默认密钥，方便本地启动。
     */
    @Test
    void devProfileAllowsDefaultSecret() {
        assertDoesNotThrow(() -> new JwtTokenParser(DEV_SECRET, "msd", environment("dev")));
    }

    /**
     * 非 dev/test 环境必须拒绝公开的默认密钥。
     */
    @Test
    void prodProfileRejectsDefaultSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtTokenParser(DEV_SECRET, "msd", environment("prod")));
    }

    /**
     * 非 dev/test 环境必须拒绝空密钥。
     */
    @Test
    void prodProfileRejectsBlankSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtTokenParser("", "msd", environment("prod")));
    }

    /**
     * 非 dev/test 环境允许自定义强密钥。
     */
    @Test
    void prodProfileAllowsCustomSecret() {
        assertDoesNotThrow(() -> new JwtTokenParser(STRONG_SECRET, "msd", environment("prod")));
    }

    /**
     * 构造指定激活环境的 Spring 测试环境。
     */
    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
