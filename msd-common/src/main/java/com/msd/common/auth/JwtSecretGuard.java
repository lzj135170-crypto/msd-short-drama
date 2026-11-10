package com.msd.common.auth;

/**
 * JWT 密钥的启动保护。
 *
 * <p>仓库里保留一个公开的本地开发默认密钥，但非 dev/test 环境必须用
 * {@code MSD_JWT_SECRET} 覆盖；否则任何人都能伪造 ADMIN Token。</p>
 */
public final class JwtSecretGuard {

    /** 仓库中公开的本地开发默认密钥，禁止用于测试/生产环境。 */
    public static final String DEV_SECRET = "msd-dev-jwt-secret-change-me-32bytes-min";

    /**
     * 工具类不允许实例化。
     */
    private JwtSecretGuard() {
    }

    /**
     * 校验当前环境是否可以使用该密钥。
     *
     * @param secret    配置的 JWT 密钥
     * @param devOrTest 当前是否处于 dev/test profile
     * @throws IllegalStateException 非 dev/test 环境使用了空密钥或公开默认密钥
     */
    public static void validate(String secret, boolean devOrTest) {
        // 1. 本地开发和测试继续允许默认值，保证开箱即用
        if (devOrTest) {
            return;
        }
        // 2. 其他环境必须提供非空且不等于公开默认值的密钥
        if (secret == null || secret.isBlank() || DEV_SECRET.equals(secret.trim())) {
            throw new IllegalStateException(
                    "MSD_JWT_SECRET must be set to a non-default value outside dev/test profiles");
        }
    }
}
