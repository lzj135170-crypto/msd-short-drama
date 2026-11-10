package com.msd.common.constant;

/**
 * 用户角色常量。
 *
 * <p>网关从 JWT 解析角色后写入 X-User-Role；下游服务按这些字符串做权限判断。</p>
 */
public final class UserRole {

    /**
     * 工具类禁止实例化。
     */
    private UserRole() {
    }

    /** 普通用户。 */
    public static final String USER = "USER";

    /** 创作者。 */
    public static final String CREATOR = "CREATOR";

    /** 平台管理员。 */
    public static final String ADMIN = "ADMIN";

    /**
     * 判断是否为管理员，忽略大小写。
     */
    public static boolean isAdmin(String role) {
        return ADMIN.equalsIgnoreCase(normalize(role));
    }

    /**
     * 判断是否为创作者，忽略大小写。
     */
    public static boolean isCreator(String role) {
        return CREATOR.equalsIgnoreCase(normalize(role));
    }

    /**
     * 判断是否为创作者或管理员，忽略大小写。
     */
    public static boolean isCreatorOrAdmin(String role) {
        return isCreator(role) || isAdmin(role);
    }

    /**
     * 去掉角色两端空白，null 归一为空字符串。
     */
    private static String normalize(String role) {
        return role == null ? "" : role.trim();
    }
}
