import { request } from './http'

/**
 * 调用 AgentScope Supervisor 全局助手。
 *
 * @param {{message: string, sessionId?: string}} payload 用户消息和会话 ID
 * @returns {Promise<{reply: string, sessionId: string, route: string,
 *   routeSource: string, routeConfidence: number, routeLatencyMs: number,
 *   cards: Array<{dramaId: number, title: string, subtitle: string, snippet: string}>}>}
 */
export function chatWithAssistant({ message, sessionId = '' }) {
    return request('/api/agent/internal/ai/agent/chat', {
        method: 'POST',
        body: { message, sessionId },
        timeoutMs: 130000
    })
}
