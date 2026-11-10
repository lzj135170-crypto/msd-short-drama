import { request } from './http'

const BASE = '/api/drama/internal/creator/dramas'

/**
 * 分页查询我的短剧。
 */
export function fetchMyDramas({ pageNo, pageSize }) {
    return request(BASE, { query: { pageNo, pageSize } })
}

/**
 * 查询短剧详情和剧集，供编辑页回填。
 */
export function fetchMyDrama(dramaId) {
    return request(`${BASE}/${dramaId}`)
}

/**
 * 创建草稿短剧。
 */
export function createDrama(payload) {
    return request(BASE, { method: 'POST', body: payload })
}

/**
 * 编辑草稿或驳回的短剧。
 */
export function updateDrama(dramaId, payload) {
    return request(`${BASE}/${dramaId}`, { method: 'PUT', body: payload })
}

/**
 * 删除草稿或驳回的短剧。
 */
export function deleteDrama(dramaId) {
    return request(`${BASE}/${dramaId}`, { method: 'DELETE' })
}

/**
 * 提交审核。
 */
export function submitDrama(dramaId) {
    return request(`${BASE}/${dramaId}/submit`, { method: 'POST' })
}

/**
 * 导入 SRT/VTT 字幕，覆盖该集同语言旧字幕。
 *
 * @param {number|string} dramaId 短剧 ID
 * @param {number|string} episodeId 剧集 ID
 * @param {{language: string, format: string, content: string}} payload 字幕内容
 */
export function importSubtitle(dramaId, episodeId, payload) {
    return request(`${BASE}/${dramaId}/episodes/${episodeId}/subtitles`, {
        method: 'POST',
        body: payload,
        timeoutMs: 30000
    })
}
