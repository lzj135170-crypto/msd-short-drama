/**
 * 桌面端左侧栏导航配置。
 *
 * 条目字段：
 * - key：唯一标识，用于高亮
 * - label：显示文案
 * - to：跳转路径
 * - icon：NavIcon 的图标名
 * - roles：可见角色，缺省表示所有已登录用户可见
 * - match：额外参与高亮的路径前缀
 */

export const primaryNav = [
    { key: 'home', label: '首页', to: '/', icon: 'home' },
    { key: 'search', label: '发现', to: '/search', icon: 'search' },
    { key: 'history', label: '追剧', to: '/history', icon: 'play' },
    // “我的”只在个人中心首页高亮，子页面交给“我的内容”分组
    { key: 'me', label: '我的', to: '/me', icon: 'user', exact: true }
]

// 观看历史已由主菜单「追剧」承载，这里不再重复列出
export const contentNav = [
    { key: 'favorites', label: '我的收藏', to: '/me/favorites', icon: 'star' },
    { key: 'likes', label: '点赞的短剧', to: '/me/likes', icon: 'heart' },
    { key: 'comments', label: '我的评论', to: '/me/comments', icon: 'comment' },
    { key: 'unlocks', label: '已解锁', to: '/me/unlocks', icon: 'unlock' },
    { key: 'recharge', label: '充值中心', to: '/recharge', icon: 'wallet' }
]

export const studioNav = [
    {
        key: 'creator',
        label: '创作者中心',
        to: '/creator/dramas',
        icon: 'clapper',
        roles: ['CREATOR', 'ADMIN'],
        match: ['/creator/dramas']
    },
    {
        key: 'reviews',
        label: '审核台',
        to: '/admin/reviews',
        icon: 'shield',
        roles: ['ADMIN']
    },
    {
        key: 'governance',
        label: '评论治理',
        to: '/admin/comments',
        icon: 'filter',
        roles: ['ADMIN']
    }
]

/**
 * 按角色过滤入口，未知角色只看到公开条目。
 */
export function visibleTo(items, role) {
    return items.filter((item) => !item.roles || item.roles.includes(role))
}

/**
 * 判断某个入口是否处于当前路由。
 *
 * - 精确路径命中直接高亮
 * - 配置了 match 时按前缀命中
 * - 首页 '/' 只做精确匹配，避免吃掉所有路由
 */
export function isNavActive(item, path) {
    if (path === item.to) {
        return true
    }
    if (item.exact || item.to === '/') {
        return false
    }
    const prefixes = item.match || [item.to]
    return prefixes.some((prefix) => path === prefix || path.startsWith(`${prefix}/`))
}
