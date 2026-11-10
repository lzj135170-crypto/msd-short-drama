import { request } from './http'

/**
 * 当前用户积分余额。
 */
export function fetchPoints() {
    return request('/api/order/internal/orders/points')
}

/**
 * 当前用户在指定短剧下已解锁的付费剧集。
 */
export function fetchDramaUnlocks(dramaId) {
    return request(`/api/order/internal/orders/dramas/${dramaId}/unlocks`)
}

/**
 * 播放信息：免费/解锁状态和视频地址（未解锁时 videoUrl 为 null）。
 */
export function fetchPlayback(episodeId) {
    return request(`/api/order/internal/playback/episodes/${episodeId}`)
}

/**
 * 用积分解锁付费剧集。
 */
export function unlockEpisode(episodeId) {
    return request(`/api/order/internal/orders/episodes/${episodeId}/unlock`, {
        method: 'POST'
    })
}

/**
 * 上架充值档位。
 */
export function fetchRechargePlans() {
    return request('/api/order/internal/recharge/plans')
}

/**
 * 创建充值订单；同一 clientRequestId 返回同一订单。
 */
export function createRechargeOrder({ planId, clientRequestId }) {
    return request('/api/order/internal/recharge/orders', {
        method: 'POST',
        body: { planId, clientRequestId }
    })
}

/**
 * 模拟支付并到账。
 */
export function mockPayOrder(orderId) {
    return request(`/api/order/internal/recharge/orders/${orderId}/mock-pay`, {
        method: 'POST'
    })
}

/**
 * 当前用户积分流水分页。
 */
export function fetchPointsLedger({ pageNo, pageSize }) {
    return request('/api/order/internal/orders/points/ledger', {
        query: { pageNo, pageSize }
    })
}

/**
 * 当前用户已解锁剧集分页。
 */
export function fetchUnlockedEpisodes({ pageNo, pageSize }) {
    return request('/api/order/internal/orders/unlocks', {
        query: { pageNo, pageSize }
    })
}
