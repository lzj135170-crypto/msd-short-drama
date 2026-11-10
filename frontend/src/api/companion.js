import { request, streamRequest } from './http'

/**
 * 发送一条 AI 伴看消息。
 *
 * @param {{message: string, sessionId: string, episodeId: number}} payload 当前消息、会话和剧集
 * @param {{signal?: AbortSignal, timeoutMs?: number}} options 取消与超时选项
 * @returns {Promise<{reply: string, sessionId: string, episodeId: number,
 *   degraded: boolean, degradedFlags: string[], elapsedMs: number,
 *   requestId: string, locked: boolean}>}
 */
export function chatWithCompanion(payload, options = {}) {
    return request('/api/content/internal/ai/companion/chat', {
        method: 'POST',
        body: payload,
        ...options
    })
}

/**
 * 流式发送一条 AI 伴看消息。
 *
 * @param {{message: string, sessionId: string, episodeId: number}} payload 当前消息、会话和剧集
 * @param {{signal?: AbortSignal, timeoutMs?: number,
 *   onEvent?: (event: string, data: any) => void}} options 取消、超时和事件回调
 * @returns {Promise<{streamed: boolean}>}
 */
export function streamCompanionChat(payload, options = {}) {
    return streamRequest('/api/content/internal/ai/companion/chat/stream', {
        method: 'POST',
        body: payload,
        timeoutMs: 35000,
        ...options
    })
}

/**
 * 查询前情提要和本集看点。
 *
 * @param {{dramaId: number|string, episodeNo: number}} params 短剧和当前集数
 * @returns {Promise<{dramaId: number, episodeNo: number, current: object|null,
 *   previous: Array, degraded: boolean, degradedFlags: string[]}>}
 */
export function fetchRecap({ dramaId, episodeNo }) {
    return request('/api/content/internal/ai/companion/recap', {
        query: { dramaId, episodeNo }
    })
}
