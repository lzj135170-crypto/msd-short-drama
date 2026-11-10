import { request, streamRequest } from './http'

/**
 * 获取首页个性化短剧推荐。
 *
 * @param {number} limit 返回条数
 * @returns {Promise<{items: Array, personalized: boolean, coldStart: boolean,
 *   degraded: boolean}>}
 */
export function fetchPersonalized(limit = 6) {
    return request('/api/content/internal/ai/rag/personalized', {
        query: { limit }
    })
}

/**
 * 保存冷启动分类偏好。
 *
 * @param {number[]} categoryIds 选择的分类 ID
 * @returns {Promise<number[]>} 实际保存的分类 ID
 */
export function saveColdStartPreferences(categoryIds) {
    return request('/api/content/internal/ai/rag/preferences', {
        method: 'POST',
        body: { categoryIds }
    })
}

/**
 * 清理当前用户个性化缓存，负反馈后立即生效。
 */
export function evictPersonalized() {
    return request('/api/content/internal/ai/rag/personalized/evict', {
        method: 'POST'
    })
}

/**
 * 自然语言找剧。
 *
 * @param {{query: string, topK?: number}} payload 用户描述和期望条数
 * @returns {Promise<{requestId: string, answer: string, rewrittenQuery: string,
 *   sources: Array, degraded: boolean, degradedFlags: string[], elapsedMs: number}>}
 */
export function recommendDramas({ query, topK = 3 }) {
    return request('/api/content/internal/ai/rag/recommend', {
        method: 'POST',
        body: { query, topK },
        timeoutMs: 30000
    })
}

/**
 * 流式自然语言找剧。
 *
 * @param {{query: string, topK?: number}} payload 用户描述和期望条数
 * @param {{signal?: AbortSignal, timeoutMs?: number,
 *   onEvent?: (event: string, data: any) => void}} options 取消、超时和事件回调
 * @returns {Promise<{streamed: boolean}>}
 */
export function streamRecommendDramas({ query, topK = 3 }, options = {}) {
    return streamRequest('/api/content/internal/ai/rag/recommend/stream', {
        method: 'POST',
        body: { query, topK },
        timeoutMs: 75000,
        ...options
    })
}

/**
 * 查询相似短剧。
 *
 * @param {number|string} dramaId 当前短剧 ID
 * @param {number} limit 返回条数
 * @returns {Promise<{items: Array, source: string, degraded: boolean}>}
 */
export function fetchRelatedDramas(dramaId, limit = 6) {
    return request('/api/content/internal/ai/rag/related', {
        query: { dramaId, limit },
        timeoutMs: 8000
    })
}

/**
 * 创作者提交前的 AI 预检。
 *
 * @param {{title: string, subtitle?: string, description?: string,
 *   episodeTitles?: string[]}} payload 待检查的文案和分集标题
 * @returns {Promise<{requestId: string, verdict: object|null, degraded: boolean,
 *   degradedFlags: string[], suggestionDegraded: boolean, titleSuggestions: string[],
 *   subtitleSuggestions: string[], tagSuggestions: string[], tips: string[],
 *   cached: boolean, elapsedMs: number}>}
 */
export function precheckDrama(payload) {
    return request('/api/content/internal/ai/audit/precheck', {
        method: 'POST',
        body: payload,
        timeoutMs: 40000
    })
}

/**
 * 创作者 Copilot：生成多个标题/副标题/标签方案。
 *
 * @param {{title?: string, subtitle?: string, description?: string, actors?: string,
 *   episodeTitles?: string[], tone?: string, count?: number}} payload 创作素材
 * @returns {Promise<{requestId: string, versions: Array, tips: string[],
 *   degraded: boolean, degradedFlags: string[], cached: boolean, elapsedMs: number}>}
 */
export function generateCopilot(payload) {
    return request('/api/content/internal/ai/copilot/generate', {
        method: 'POST',
        body: payload,
        timeoutMs: 50000
    })
}
