/**
 * 把后端时间字符串转成 Date，兼容 7 位小数秒。
 */
function toDate(value) {
    if (!value) {
        return null
    }
    if (value instanceof Date) {
        return Number.isNaN(value.getTime()) ? null : value
    }
    const normalized = String(value).replace(/(\.\d{3})\d+/, '$1')
    const date = new Date(normalized)
    return Number.isNaN(date.getTime()) ? null : date
}

/**
 * 补零。
 */
function pad(value) {
    return String(value).padStart(2, '0')
}

/**
 * 2026-10-01 20:00
 */
export function formatDateTime(value) {
    const date = toDate(value)
    if (!date) {
        return value ? String(value) : ''
    }
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
        + `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 2026-10-01
 */
export function formatDate(value) {
    const date = toDate(value)
    if (!date) {
        return value ? String(value) : ''
    }
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/**
 * 刚刚 / 3 分钟前 / 2 小时前 / 5 天前 / 日期。
 */
export function formatRelative(value) {
    const date = toDate(value)
    if (!date) {
        return value ? String(value) : ''
    }
    const diff = Date.now() - date.getTime()
    if (diff < 0) {
        return formatDateTime(value)
    }
    const minute = 60 * 1000
    const hour = 60 * minute
    const day = 24 * hour
    if (diff < minute) {
        return '刚刚'
    }
    if (diff < hour) {
        return `${Math.floor(diff / minute)} 分钟前`
    }
    if (diff < day) {
        return `${Math.floor(diff / hour)} 小时前`
    }
    if (diff < 7 * day) {
        return `${Math.floor(diff / day)} 天前`
    }
    return formatDate(value)
}

/**
 * 秒数转 mm:ss 或 h:mm:ss。
 */
export function formatDuration(seconds) {
    const total = Math.max(0, Math.floor(Number(seconds) || 0))
    const hours = Math.floor(total / 3600)
    const minutes = Math.floor((total % 3600) / 60)
    const rest = total % 60
    return hours > 0
        ? `${hours}:${pad(minutes)}:${pad(rest)}`
        : `${pad(minutes)}:${pad(rest)}`
}

/**
 * 毫秒转播放时间。
 */
export function formatMs(ms) {
    return formatDuration((Number(ms) || 0) / 1000)
}

/**
 * 1.2万 / 3.4亿，小于一万直接展示。
 */
export function formatCount(value) {
    const number = Number(value) || 0
    if (number >= 100000000) {
        return `${trimZero(number / 100000000)}亿`
    }
    if (number >= 10000) {
        return `${trimZero(number / 10000)}万`
    }
    return String(number)
}

function trimZero(value) {
    return value.toFixed(1).replace(/\.0$/, '')
}

/**
 * 播放进度百分比，范围 0-100。
 */
export function progressPercent(positionMs, durationMs) {
    const duration = Number(durationMs) || 0
    if (duration <= 0) {
        return 0
    }
    return Math.min(100, Math.max(0, Math.round((Number(positionMs) || 0) / duration * 100)))
}

/**
 * 评论状态文案。
 */
export function commentStatusLabel(status) {
    return {
        0: '审核中',
        1: '已发布',
        2: '已隐藏',
        3: '已删除'
    }[status] || '未知'
}
