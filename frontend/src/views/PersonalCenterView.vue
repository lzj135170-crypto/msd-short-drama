<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchPoints } from '../api/order'
import { fetchStats } from '../api/interaction'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'
import { formatDate } from '../utils/format'
import AppModal from '../components/AppModal.vue'
import PageHeader from '../components/PageHeader.vue'
import UserAvatar from '../components/UserAvatar.vue'
import NavIcon from '../components/layout/NavIcon.vue'

const auth = useAuthStore()
const toast = useToastStore()
const router = useRouter()

const points = ref(null)
const pointsLoading = ref(true)
const pointsError = ref('')

const stats = ref(null)
const statsLoading = ref(true)
const statsError = ref('')

const editOpen = ref(false)
const editForm = reactive({ nickname: '', avatarUrl: '', bio: '' })
const editError = ref('')
const saving = ref(false)

const joinedText = computed(() => formatDate(auth.user?.createTime))

const statTiles = computed(() => [
    { key: 'likes', label: '点赞短剧', value: stats.value?.likedDramaCount, to: '/me/likes', tone: 'heart' },
    { key: 'favorites', label: '我的收藏', value: stats.value?.favoriteCount, to: '/me/favorites', tone: 'star' },
    { key: 'comments', label: '我的评论', value: stats.value?.commentCount, to: '/me/comments', tone: 'comment' },
    { key: 'history', label: '观看记录', value: stats.value?.historyCount, to: '/history', tone: 'clock' }
])

// 1. 内容入口固定；工作台入口按角色追加，避免普通用户看到空分组
const menuGroups = computed(() => {
    const groups = [
        {
            key: 'content',
            title: '我的内容',
            items: [
                { key: 'history', label: '观看记录', icon: 'clock', to: '/history' },
                { key: 'favorites', label: '我的收藏', icon: 'star', to: '/me/favorites' },
                { key: 'likes', label: '点赞的短剧', icon: 'heart', to: '/me/likes' },
                { key: 'comments', label: '我的评论', icon: 'comment', to: '/me/comments' },
                { key: 'unlocks', label: '已解锁剧集', icon: 'unlock', to: '/me/unlocks' }
            ]
        },
        {
            key: 'account',
            title: '账户与工作台',
            items: [
                { key: 'recharge', label: '充值中心', icon: 'wallet', to: '/recharge' }
            ]
        }
    ]

    if (auth.user?.role === 'CREATOR' || auth.user?.role === 'ADMIN') {
        groups[1].items.push({
            key: 'creator',
            label: '创作者中心',
            icon: 'clapper',
            to: '/creator/dramas'
        })
    }
    if (auth.user?.role === 'ADMIN') {
        groups[1].items.push(
            { key: 'review', label: '审核台', icon: 'shield', to: '/admin/reviews' },
            { key: 'moderation', label: '评论治理', icon: 'filter', to: '/admin/comments' }
        )
    }
    return groups
})

async function loadProfile() {
    try {
        await auth.loadMe()
    } catch (cause) {
        if (cause?.status !== 401) {
            toast.push(cause?.message || '资料刷新失败')
        }
    }
}

async function loadPoints() {
    pointsLoading.value = true
    pointsError.value = ''
    try {
        points.value = await fetchPoints()
    } catch (cause) {
        pointsError.value = cause?.message || '积分加载失败'
    } finally {
        pointsLoading.value = false
    }
}

async function loadStats() {
    statsLoading.value = true
    statsError.value = ''
    try {
        stats.value = await fetchStats()
    } catch (cause) {
        statsError.value = cause?.message || '统计加载失败'
    } finally {
        statsLoading.value = false
    }
}

function openEdit() {
    editForm.nickname = auth.user?.nickname || ''
    editForm.avatarUrl = auth.user?.avatarUrl || ''
    editForm.bio = auth.user?.bio || ''
    editError.value = ''
    editOpen.value = true
}

/**
 * 校验与后端保持一致：昵称 1-20，头像 512，简介 255。
 */
async function saveProfile() {
    const nickname = editForm.nickname.trim()
    const avatarUrl = editForm.avatarUrl.trim()
    const bio = editForm.bio.trim()

    if (!nickname) {
        editError.value = '昵称不能为空'
        return
    }
    if (nickname.length > 20) {
        editError.value = '昵称最多 20 个字符'
        return
    }
    if (avatarUrl.length > 512) {
        editError.value = '头像 URL 最多 512 个字符'
        return
    }
    if (bio.length > 255) {
        editError.value = '简介最多 255 个字符'
        return
    }

    saving.value = true
    editError.value = ''
    try {
        await auth.updateProfile({ nickname, avatarUrl, bio })
        toast.success('资料已更新')
        editOpen.value = false
    } catch (cause) {
        editError.value = cause?.message || '保存失败'
    } finally {
        saving.value = false
    }
}

function logout() {
    auth.clear()
    toast.success('已退出登录')
    router.replace({ name: 'login' })
}

onMounted(() => {
    loadProfile()
    loadPoints()
    loadStats()
})
</script>

<template>
    <div class="tab-page">
        <PageHeader title="我的" :show-back="false"/>

        <main class="app-main profile-main">
            <aside class="profile-aside">
                <section class="profile-card">
                <span class="profile-banner" aria-hidden="true"></span>
                <div class="profile-head">
                    <span class="profile-avatar">
                        <UserAvatar
                            :name="auth.displayName"
                            :src="auth.user?.avatarUrl"
                            :size="60"
                        />
                    </span>
                    <div class="profile-copy">
                        <div class="name-row">
                            <h1>{{ auth.displayName }}</h1>
                            <span class="role-chip">{{ auth.roleLabel }}</span>
                        </div>
                        <p class="phone">{{ auth.maskedPhone || '—' }}</p>
                    </div>
                    <button class="edit-btn" type="button" @click="openEdit">编辑资料</button>
                </div>
                <p class="bio">{{ auth.user?.bio || '还没有填写简介' }}</p>
                <p class="joined">加入于 {{ joinedText || '—' }}</p>
            </section>

            <section class="points-card">
                <span class="points-icon" aria-hidden="true">
                    <NavIcon name="wallet"/>
                </span>
                <div class="points-copy">
                    <span class="points-label">积分余额</span>
                    <strong v-if="pointsLoading">…</strong>
                    <strong v-else-if="pointsError">加载失败</strong>
                    <strong v-else-if="points?.exists">{{ points.balance }}</strong>
                    <strong v-else>未开通</strong>
                    <span v-if="!pointsError" class="points-hint">
                        {{ points?.exists === false ? '充值后自动开通积分账户' : '用于解锁付费剧集' }}
                    </span>
                </div>
                <button v-if="pointsError" class="points-action is-ghost" type="button" @click="loadPoints">
                    重试
                </button>
                <button v-else class="points-action" type="button" @click="router.push('/recharge')">
                    充值
                </button>
            </section>
            </aside>

            <div class="profile-content">
            <section class="stats-section">
                <div class="section-title">
                    <h2>我的数据</h2>
                    <button v-if="statsError" class="text-btn" type="button" @click="loadStats">
                        重试
                    </button>
                </div>
                <div class="stats-grid">
                    <button
                        v-for="tile in statTiles"
                        :key="tile.key"
                        class="stat-card"
                        type="button"
                        @click="router.push(tile.to)"
                    >
                        <span class="stat-icon" :class="`tone-${tile.tone}`" aria-hidden="true">
                            <svg v-if="tile.tone === 'heart'" viewBox="0 0 24 24" fill="none"
                                 stroke="currentColor" stroke-width="1.8" stroke-linecap="round"
                                 stroke-linejoin="round">
                                <path d="M12 20s-7.5-4.6-7.5-10A4.5 4.5 0 0 1 12 6.8 4.5 4.5 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z"/>
                            </svg>
                            <svg v-else-if="tile.tone === 'star'" viewBox="0 0 24 24" fill="none"
                                 stroke="currentColor" stroke-width="1.8" stroke-linecap="round"
                                 stroke-linejoin="round">
                                <path d="M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z"/>
                            </svg>
                            <svg v-else-if="tile.tone === 'comment'" viewBox="0 0 24 24" fill="none"
                                 stroke="currentColor" stroke-width="1.8" stroke-linecap="round"
                                 stroke-linejoin="round">
                                <path d="M4 5h16v11H9l-5 4z"/>
                            </svg>
                            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                 stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                                <circle cx="12" cy="12" r="9"/>
                                <path d="M12 7.5v5l3.2 1.8"/>
                            </svg>
                        </span>
                        <strong>{{ statsLoading ? '—' : (statsError ? '—' : (tile.value ?? 0)) }}</strong>
                        <span class="stat-label">{{ tile.label }}</span>
                    </button>
                </div>
            </section>

            <section v-for="group in menuGroups" :key="group.key" class="menu-card">
                <div class="menu-head">
                    <h2>{{ group.title }}</h2>
                    <span>{{ group.items.length }} 项</span>
                </div>
                <div class="menu-grid">
                    <button
                        v-for="item in group.items"
                        :key="item.key"
                        class="menu-row"
                        type="button"
                        @click="router.push(item.to)"
                    >
                        <span class="menu-icon" aria-hidden="true">
                            <NavIcon :name="item.icon"/>
                        </span>
                        <span class="menu-label">{{ item.label }}</span>
                        <span class="chevron">›</span>
                    </button>
                </div>
            </section>

            <button class="btn btn-ghost btn-block logout-btn" type="button" @click="logout">
                退出登录
            </button>
            </div>
        </main>

        <AppModal v-model="editOpen" title="编辑资料">
            <form class="profile-form" @submit.prevent="saveProfile">
                <div class="avatar-preview">
                    <UserAvatar
                        :name="editForm.nickname || auth.displayName"
                        :src="editForm.avatarUrl"
                        :size="52"
                    />
                    <span>头像预览</span>
                </div>

                <label class="field">
                    <span>昵称</span>
                    <input v-model.trim="editForm.nickname" maxlength="20" placeholder="最多 20 个字符">
                </label>

                <label class="field">
                    <span>头像 URL</span>
                    <input v-model.trim="editForm.avatarUrl" maxlength="512" placeholder="留空使用昵称首字头像">
                </label>

                <label class="field">
                    <span>简介</span>
                    <textarea
                        v-model="editForm.bio"
                        maxlength="255"
                        rows="3"
                        placeholder="简单介绍一下自己"
                    ></textarea>
                    <small>{{ editForm.bio.length }}/255</small>
                </label>

                <p v-if="editError" class="form-error">{{ editError }}</p>
            </form>

            <template #footer>
                <button class="btn btn-ghost" type="button" @click="editOpen = false">取消</button>
                <button class="btn btn-primary" type="button" :disabled="saving" @click="saveProfile">
                    {{ saving ? '保存中…' : '保存' }}
                </button>
            </template>
        </AppModal>
    </div>
</template>

<style scoped>
.profile-main {
    gap: 16px;
}

.profile-card,
.points-card,
.stats-section,
.menu-card {
    padding: 18px;
    border: 1px solid var(--line);
    border-radius: 18px;
    background: var(--panel);
    box-shadow: var(--shadow-soft);
}

/* ---------- 资料卡 ---------- */

.profile-card {
    position: relative;
    overflow: hidden;
    padding-top: 20px;
}

.profile-banner {
    position: absolute;
    top: 0;
    right: 0;
    left: 0;
    height: 92px;
    background:
        radial-gradient(220px 120px at 12% 0%, rgba(47, 174, 122, 0.28), transparent 70%),
        radial-gradient(220px 120px at 100% 0%, rgba(255, 107, 74, 0.22), transparent 72%),
        linear-gradient(120deg, #eaf7ef, #fff7f1);
    pointer-events: none;
}

.profile-head {
    position: relative;
    display: flex;
    align-items: center;
    gap: 13px;
    margin-top: 22px;
}

.profile-avatar {
    display: grid;
    flex: none;
    padding: 3px;
    border-radius: 50%;
    background: var(--surface);
    box-shadow: var(--shadow-soft);
}

.profile-copy {
    display: grid;
    min-width: 0;
    flex: 1;
    gap: 4px;
}

.name-row {
    display: flex;
    align-items: center;
    gap: 8px;
}

.name-row h1 {
    overflow: hidden;
    font-size: 21px;
    font-weight: 800;
    letter-spacing: -0.01em;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.role-chip {
    flex: none;
    padding: 2px 8px;
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-size: 9px;
}

.phone {
    color: var(--muted);
    font-size: 11px;
    letter-spacing: 0.04em;
}

.edit-btn {
    flex: none;
    padding: 7px 12px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: var(--surface);
    box-shadow: var(--shadow-soft);
    color: var(--brand-ink);
    font-size: 11px;
    font-weight: 700;
}

.bio {
    position: relative;
    margin-top: 16px;
    color: var(--text-soft);
    font-size: 12px;
    line-height: 1.7;
}

.joined {
    position: relative;
    margin-top: 6px;
    color: var(--muted-soft);
    font-size: 10px;
}

/* ---------- 积分卡 ---------- */

.points-card {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 12px;
    background:
        radial-gradient(240px 140px at 100% 0%, rgba(47, 174, 122, 0.18), transparent 70%),
        linear-gradient(135deg, var(--surface), #fff6f1);
}

.points-icon {
    display: grid;
    place-items: center;
    width: 42px;
    height: 42px;
    border-radius: 13px;
    background: var(--surface);
    color: var(--brand-ink);
    box-shadow: var(--shadow-soft);
}

.points-icon :deep(.nav-icon) {
    width: 22px;
    height: 22px;
}

.points-copy {
    display: grid;
    min-width: 0;
    gap: 1px;
}

.points-label {
    color: var(--muted);
    font-size: 11px;
}

.points-copy strong {
    color: var(--brand-ink);
    font-size: 26px;
    font-weight: 900;
    letter-spacing: -0.02em;
    line-height: 1.15;
}

.points-hint {
    overflow: hidden;
    color: var(--muted-soft);
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.points-action {
    flex: none;
    height: 34px;
    padding: 0 14px;
    border: 1px solid rgba(184, 58, 38, 0.25);
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-size: 12px;
    font-weight: 700;
}

.points-action.is-ghost {
    border-color: var(--line-strong);
    background: var(--surface);
}

.text-btn {
    padding: 0;
    border: none;
    background: none;
    color: var(--brand-ink);
    font-size: 11px;
    text-decoration: underline;
}

/* ---------- 数据统计 ---------- */

.stats-section {
    display: grid;
    gap: 12px;
}

.stats-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 9px;
}

.stat-card {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    grid-template-rows: auto auto;
    align-items: center;
    justify-items: start;
    gap: 1px 10px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 13px;
    background: var(--surface-soft);
    color: var(--text);
    text-align: left;
    transition: border-color 140ms ease, background 140ms ease;
}

.stat-card:hover {
    border-color: rgba(184, 58, 38, 0.25);
    background: var(--surface);
}

.stat-icon {
    display: grid;
    grid-row: span 2;
    place-items: center;
    width: 36px;
    height: 36px;
    border-radius: 11px;
}

.stat-icon svg {
    width: 18px;
    height: 18px;
}

.tone-heart {
    background: var(--brand-soft);
    color: var(--brand-ink);
}

.tone-star {
    background: var(--warning-soft);
    color: var(--warning-ink);
}

.tone-comment {
    background: #eaf1fb;
    color: #2f5f9e;
}

.tone-clock {
    background: var(--ok-soft);
    color: var(--ok);
}

.stat-card strong {
    font-size: 20px;
    font-weight: 900;
    letter-spacing: -0.01em;
    line-height: 1.1;
}

.stat-label {
    color: var(--muted);
    font-size: 10px;
    white-space: nowrap;
}

/* ---------- 菜单 ---------- */

.menu-card {
    display: grid;
    gap: 10px;
    padding: 16px;
}

.menu-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 10px;
}

.menu-head h2 {
    font-size: 14px;
    font-weight: 800;
}

.menu-head span {
    color: var(--muted-soft);
    font-size: 10px;
}

.menu-grid {
    display: grid;
    gap: 8px;
}

.menu-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface-soft);
    color: var(--text);
    font-size: 13px;
    text-align: left;
    transition: border-color 140ms ease, background 140ms ease;
}

.menu-row:hover {
    border-color: rgba(184, 58, 38, 0.25);
    background: var(--surface);
}

.menu-icon {
    display: grid;
    flex: none;
    place-items: center;
    width: 30px;
    height: 30px;
    border-radius: 9px;
    background: var(--surface);
    color: var(--brand-ink);
}

.menu-icon :deep(.nav-icon) {
    width: 16px;
    height: 16px;
}

.menu-label {
    min-width: 0;
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.chevron {
    flex: none;
    color: var(--muted-soft);
    font-size: 17px;
    line-height: 1;
}

.logout-btn {
    color: var(--danger);
    border-color: #f0c6c6;
    background: var(--danger-soft);
    box-shadow: none;
}

.profile-form {
    display: grid;
    gap: 12px;
}

.avatar-preview {
    display: flex;
    align-items: center;
    gap: 10px;
    color: var(--muted);
    font-size: 11px;
}

.field small {
    color: var(--muted-soft);
    font-size: 10px;
    text-align: right;
}

.profile-aside,
.profile-content {
    display: grid;
    gap: 14px;
}

/* ---------- 桌面端（≥1024）：左侧资料卡 + 右侧内容 ---------- */

@media (min-width: 1024px) {
    .profile-main {
        grid-template-columns: 340px minmax(0, 1fr);
        align-items: start;
        gap: 26px;
    }

    .profile-aside {
        position: sticky;
        top: calc(var(--topbar-height) + 24px);
    }

    .profile-card,
    .points-card {
        padding: 20px;
    }

    /* 积分卡在窄侧栏里改为两行：图标 + 余额，操作按钮换行 */
    .points-card {
        grid-template-columns: auto minmax(0, 1fr);
        row-gap: 6px;
    }

    .points-icon {
        grid-row: span 2;
    }

    .points-action {
        grid-column: 2;
        justify-self: start;
    }

    .points-copy strong {
        font-size: 30px;
    }

    .stats-section {
        padding: 20px;
    }

    .stats-grid {
        grid-template-columns: repeat(4, minmax(0, 1fr));
        gap: 12px;
    }

    .stat-card {
        padding: 16px;
    }

    .stat-card strong {
        font-size: 22px;
    }

    .stat-label {
        font-size: 11px;
    }

    .menu-card {
        padding: 18px;
    }

    .menu-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 10px;
    }

    .menu-row {
        padding: 11px 12px;
    }

    .menu-label {
        font-size: 13px;
    }

    .profile-content > .btn {
        justify-self: start;
        width: auto;
        min-width: 160px;
    }
}
</style>
