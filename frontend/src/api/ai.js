import { request } from './http'

/**
 * 提交 AI 回答反馈。
 *
 * @param {{scene: string, requestId: string, rating: number, reason?: string,
 *   comment?: string, targetId?: number, sessionId?: string}} payload
 * @returns {Promise<{id: number, duplicate: boolean}>}
 */
export function sendAiFeedback({
    scene,
    requestId,
    rating,
    reason = '',
    comment = '',
    targetId,
    sessionId
}) {
    return request('/api/interaction/internal/ai/feedback', {
        method: 'POST',
        body: {
            scene,
            requestId,
            rating,
            reason,
            comment,
            targetId,
            sessionId
        }
    })
}
