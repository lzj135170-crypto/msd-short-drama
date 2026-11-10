import { request } from './http'

/**
 * 手机号加密码登录。
 */
export function login(payload) {
    return request('/api/user/auth/login', {
        method: 'POST',
        body: payload,
        auth: false
    })
}

/**
 * 注册并直接获得登录 Token。
 */
export function register(payload) {
    return request('/api/user/auth/register', {
        method: 'POST',
        body: payload,
        auth: false
    })
}
