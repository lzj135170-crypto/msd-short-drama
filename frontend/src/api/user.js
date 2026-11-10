import { request } from './http'

/**
 * 查询当前登录用户资料。
 */
export function fetchMe() {
    return request('/api/user/internal/users/me')
}

/**
 * 修改当前用户资料；null 表示不修改，空字符串表示清空头像/简介。
 */
export function updateMe(payload) {
    return request('/api/user/internal/users/me', {
        method: 'PUT',
        body: payload
    })
}
