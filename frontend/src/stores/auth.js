import { defineStore } from 'pinia'
import { login as loginApi, register as registerApi } from '../api/auth'
import { fetchMe, updateMe } from '../api/user'

const STORAGE_KEY = 'msd.auth'

/**
 * 读取本地会话，解析失败时按未登录处理。
 */
function readStored() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY)
        return raw ? JSON.parse(raw) : null
    } catch {
        return null
    }
}

/**
 * 写入本地会话。
 */
function persist(payload) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
}

export const useAuthStore = defineStore('auth', {
    state: () => ({
        token: '',
        expiresAt: 0,
        user: null
    }),

    getters: {
        displayName: (state) => state.user?.nickname || '剧迷',
        roleLabel: (state) => ({
            USER: '普通用户',
            CREATOR: '创作者',
            ADMIN: '管理员'
        }[state.user?.role] || '普通用户'),
        maskedPhone: (state) => {
            const phone = state.user?.phone || ''
            return phone.length === 11
                ? `${phone.slice(0, 3)} **** ${phone.slice(7)}`
                : phone
        }
    },

    actions: {
        /**
         * 启动时恢复本地会话，过期则直接清空。
         */
        restore() {
            const stored = readStored()
            if (!stored) {
                return
            }
            this.token = stored.token || ''
            this.expiresAt = Number(stored.expiresAt) || 0
            this.user = stored.user || null
            if (!this.hasValidSession()) {
                this.clear()
            }
        },

        /**
         * 每次调用都重新比较当前时间，避免 getter 缓存导致过期判断失效。
         */
        hasValidSession() {
            return Boolean(this.token) && Number(this.expiresAt) > Date.now()
        },

        /**
         * 登录或注册成功后保存 Token 和用户资料。
         */
        apply(result) {
            this.token = result.token
            this.expiresAt = Date.now() + Number(result.expiresInSeconds || 0) * 1000
            this.user = result.user
            persist({
                token: this.token,
                expiresAt: this.expiresAt,
                user: this.user
            })
        },

        async login(payload) {
            const result = await loginApi(payload)
            this.apply(result)
            return result
        },

        async register(payload) {
            const result = await registerApi(payload)
            this.apply(result)
            return result
        },

        /**
         * 刷新页面后重新拉取用户资料，顺便验证 Token 仍然有效。
         */
        async loadMe() {
            const user = await fetchMe()
            this.user = user
            persist({
                token: this.token,
                expiresAt: this.expiresAt,
                user: this.user
            })
            return user
        },

        /**
         * 修改资料并同步本地会话。
         */
        async updateProfile(payload) {
            const user = await updateMe(payload)
            this.user = user
            persist({
                token: this.token,
                expiresAt: this.expiresAt,
                user: this.user
            })
            return user
        },

        /**
         * 本地退出；当前版本没有服务端 Token 吊销。
         */
        clear() {
            this.token = ''
            this.expiresAt = 0
            this.user = null
            localStorage.removeItem(STORAGE_KEY)
        }
    }
})
