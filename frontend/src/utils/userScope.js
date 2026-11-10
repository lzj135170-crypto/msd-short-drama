/**
 * 本地 AI 会话的存储作用域：优先使用已加载的用户 ID，缺失时从 JWT 的 sub 解析。
 * 同一浏览器切换账号后必须换 key，避免读到上一个人的会话。
 */
export function currentUserScope(auth) {
    const userId = auth?.user?.userId
    if (userId) {
        return `u${userId}`
    }

    const token = auth?.token
    if (!token) {
        return 'u0'
    }

    try {
        const payload = token.split('.')[1]
        if (!payload) {
            return 'u0'
        }
        const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
        const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
        const decoded = JSON.parse(atob(padded))
        return decoded?.sub ? `u${decoded.sub}` : 'u0'
    } catch {
        return 'u0'
    }
}
