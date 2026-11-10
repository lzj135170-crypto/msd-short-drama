import { request } from './http'

/**
 * 首页聚合：轮播、热播/最新区块、分类。
 */
export function fetchHome() {
    return request('/api/drama/internal/home')
}

/**
 * 短剧详情和在线剧集，不包含视频地址。
 */
export function fetchDramaDetail(dramaId) {
    return request(`/api/drama/internal/dramas/${dramaId}`)
}

/**
 * 短剧计数；点赞/收藏等异步事件完成后用于校准 UI。
 */
export function fetchDramaStats(dramaId) {
    return request(`/api/drama/internal/dramas/${dramaId}/stats`)
}

/**
 * 启用中的短剧分类，供搜索页筛选。
 */
export function fetchCategories() {
    return request('/api/drama/internal/categories')
}

/**
 * 搜索或分类浏览已上架短剧，分页返回。
 */
export function searchDramas({ keyword, categoryId, sort, pageNo, pageSize } = {}) {
    return request('/api/drama/internal/search/dramas', {
        query: { keyword, categoryId, sort, pageNo, pageSize }
    })
}
