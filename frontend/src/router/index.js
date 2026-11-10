import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
    {
        path: '/login',
        name: 'login',
        component: () => import('../views/LoginView.vue'),
        meta: { public: true, title: '登录' }
    },
    {
        path: '/register',
        name: 'register',
        component: () => import('../views/RegisterView.vue'),
        meta: { public: true, title: '注册' }
    },
    {
        path: '/',
        name: 'home',
        component: () => import('../views/HomeView.vue'),
        meta: { tab: 'home', title: '首页' }
    },
    {
        path: '/history',
        name: 'history',
        component: () => import('../views/HistoryView.vue'),
        meta: { tab: 'history', title: '追剧' }
    },
    {
        path: '/search',
        name: 'search',
        component: () => import('../views/SearchView.vue'),
        meta: { title: '发现' }
    },
    {
        path: '/me',
        name: 'me',
        component: () => import('../views/PersonalCenterView.vue'),
        meta: { tab: 'me', title: '我的' }
    },
    {
        path: '/me/favorites',
        name: 'favorites',
        component: () => import('../views/FavoritesView.vue'),
        meta: { title: '我的收藏' }
    },
    {
        path: '/recharge',
        name: 'recharge',
        component: () => import('../views/RechargeView.vue'),
        meta: { title: '充值中心' }
    },
    {
        path: '/me/comments',
        name: 'my-comments',
        component: () => import('../views/MyCommentsView.vue'),
        meta: { title: '我的评论' }
    },
    {
        path: '/me/likes',
        name: 'liked-dramas',
        component: () => import('../views/LikedDramasView.vue'),
        meta: { title: '点赞的短剧' }
    },
    {
        path: '/creator/dramas',
        name: 'creator-dramas',
        component: () => import('../views/CreatorDramasView.vue'),
        meta: { title: '创作者中心', roles: ['CREATOR', 'ADMIN'] }
    },
    {
        path: '/creator/dramas/new',
        name: 'creator-drama-new',
        component: () => import('../views/CreatorDramaEditView.vue'),
        meta: { title: '新建短剧', roles: ['CREATOR', 'ADMIN'] }
    },
    {
        path: '/creator/dramas/:id/edit',
        name: 'creator-drama-edit',
        component: () => import('../views/CreatorDramaEditView.vue'),
        meta: { title: '编辑短剧', roles: ['CREATOR', 'ADMIN'] }
    },
    {
        path: '/admin/reviews',
        name: 'admin-reviews',
        component: () => import('../views/AdminReviewsView.vue'),
        meta: { title: '审核台', roles: ['ADMIN'] }
    },
    {
        path: '/admin/comments',
        name: 'admin-comments',
        component: () => import('../views/AdminCommentsView.vue'),
        meta: { title: '评论治理', roles: ['ADMIN'] }
    },
    {
        path: '/me/unlocks',
        name: 'unlocked-episodes',
        component: () => import('../views/UnlockedView.vue'),
        meta: { title: '已解锁剧集' }
    },
    {
        path: '/drama/:dramaId',
        name: 'drama-detail',
        component: () => import('../views/DramaDetailView.vue'),
        props: true,
        meta: { title: '短剧', immersive: true }
    },
    {
        path: '/watch/:episodeId',
        name: 'player',
        component: () => import('../views/PlayerView.vue'),
        props: true,
        meta: { title: '播放', immersive: true }
    },
    {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: () => import('../views/NotFoundView.vue'),
        meta: { public: true, title: '页面不存在' }
    }
]

const router = createRouter({
    history: createWebHistory(),
    routes,
    scrollBehavior(to, from, savedPosition) {
        return savedPosition || { top: 0 }
    }
})

router.beforeEach((to) => {
    const auth = useAuthStore()

    // 1. 登录/注册页对已登录用户直接回首页
    if (to.meta.public) {
        if ((to.name === 'login' || to.name === 'register') && auth.hasValidSession()) {
            return { name: 'home' }
        }
        return true
    }

    // 2. 受保护页面要求有效会话，过期时顺手清理本地状态
    if (!auth.hasValidSession()) {
        if (auth.token) {
            auth.clear()
        }
        return { name: 'login', query: { redirect: to.fullPath } }
    }

    // 3. 创作者/管理员页面做前端入口控制，后端仍会再次校验角色
    if (to.meta.roles && !to.meta.roles.includes(auth.user?.role)) {
        return { name: 'home' }
    }
    return true
})

router.afterEach((to) => {
    const title = to.meta.title
    document.title = title ? `${title} · 码上短剧` : '码上短剧'
})

export default router
