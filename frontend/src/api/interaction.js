import { request } from './http'

const BASE = '/api/interaction/internal/interactions'

/**
 * 当前用户对短剧的点赞/收藏状态。
 */
export function fetchDramaState(dramaId) {
    return request(`${BASE}/dramas/${dramaId}/state`)
}

/**
 * 点赞，targetType：1 短剧、2 剧集、3 评论。
 */
export function likeTarget(targetType, targetId) {
    return request(`${BASE}/likes`, {
        method: 'POST',
        body: { targetType, targetId }
    })
}

/**
 * 取消点赞。
 */
export function unlikeTarget(targetType, targetId) {
    return request(`${BASE}/likes/${targetType}/${targetId}`, {
        method: 'DELETE'
    })
}

/**
 * 查询异步计数事件状态；eventId 来自点赞/收藏/评论写接口。
 */
export function fetchEventStatus(eventId) {
    return request(`${BASE}/events/${eventId}`)
}

/**
 * 收藏短剧。
 */
export function favoriteDrama(dramaId) {
    return request(`${BASE}/favorites`, {
        method: 'POST',
        body: { dramaId }
    })
}

/**
 * 取消收藏。
 */
export function unfavoriteDrama(dramaId) {
    return request(`${BASE}/favorites/${dramaId}`, {
        method: 'DELETE'
    })
}

/**
 * 根评论分页；不传 episodeId 时查询短剧级评论。
 */
export function fetchComments({ dramaId, episodeId, pageNo = 1, pageSize = 20 }) {
    return request(`${BASE}/comments`, {
        query: { dramaId, episodeId, pageNo, pageSize }
    })
}

/**
 * 某个根评论下的回复分页。
 */
export function fetchReplies(rootId, { pageNo = 1, pageSize = 10 } = {}) {
    return request(`${BASE}/comments/${rootId}/replies`, {
        query: { pageNo, pageSize }
    })
}

/**
 * 我的评论分页。
 */
export function fetchMyComments({ pageNo = 1, pageSize = 20 } = {}) {
    return request(`${BASE}/comments/mine`, {
        query: { pageNo, pageSize }
    })
}

/**
 * 创建评论或回复。
 */
export function createComment(payload) {
    return request(`${BASE}/comments`, {
        method: 'POST',
        body: payload
    })
}

/**
 * 删除自己的评论。
 */
export function deleteComment(commentId) {
    return request(`${BASE}/comments/${commentId}`, {
        method: 'DELETE'
    })
}

/**
 * 我的收藏分页。
 */
export function fetchFavorites({ pageNo = 1, pageSize = 20 } = {}) {
    return request(`${BASE}/favorites`, {
        query: { pageNo, pageSize }
    })
}

/**
 * 我点赞的短剧分页。
 */
export function fetchLikedDramas({ pageNo = 1, pageSize = 20 } = {}) {
    return request(`${BASE}/likes/dramas`, {
        query: { pageNo, pageSize }
    })
}

/**
 * 观看历史分页。
 */
export function fetchHistory({ pageNo = 1, pageSize = 20 } = {}) {
    return request(`${BASE}/history`, {
        query: { pageNo, pageSize }
    })
}

/**
 * 首页继续观看：每部短剧取最近一条记录。
 */
export function fetchContinueWatching(limit = 3) {
    return request(`${BASE}/history/continue`, {
        query: { limit }
    })
}

/**
 * 上报观看进度；离开页面时可用 keepalive 尽量送达。
 */
export function reportHistory(payload, { keepalive = false } = {}) {
    return request(`${BASE}/history`, {
        method: 'POST',
        body: payload,
        keepalive
    })
}

/**
 * 删除单条观看历史。
 */
export function deleteHistoryEntry(episodeId) {
    return request(`${BASE}/history/${episodeId}`, {
        method: 'DELETE'
    })
}

/**
 * 清空全部观看历史，返回删除条数。
 */
export function clearHistory() {
    return request(`${BASE}/history`, {
        method: 'DELETE'
    })
}

/**
 * 个人中心互动统计。
 */
export function fetchStats() {
    return request(`${BASE}/stats`)
}

/**
 * 上报推荐行为：1 点击，2 播放，3 完播，4 不感兴趣。
 *
 * @param {{requestId: string, dramaId: number, eventType: number,
 *   position?: number}} payload 归因事件
 */
export function trackRecommendationEvent(payload) {
    return request('/api/interaction/internal/recommendations/events', {
        method: 'POST',
        body: payload
    })
}

/**
 * 查询评论课代表。
 *
 * @param {{dramaId: number, episodeId?: number}} params 短剧和剧集
 */
export function fetchCommentSummary({ dramaId, episodeId }) {
    return request('/api/interaction/internal/ai/comments/summary', {
        query: { dramaId, episodeId }
    })
}
