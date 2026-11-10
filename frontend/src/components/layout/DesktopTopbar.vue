<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import UserAvatar from '../UserAvatar.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// 1. 在搜索页时回填当前关键词，其他页面保持为空
const initialKeyword = route.name === 'search' && typeof route.query.keyword === 'string'
    ? route.query.keyword
    : ''
const keyword = ref(initialKeyword)

watch(() => route.fullPath, () => {
    if (route.name === 'search') {
        keyword.value = typeof route.query.keyword === 'string' ? route.query.keyword : ''
        return
    }
    keyword.value = ''
})

const pageTitle = computed(() => route.meta.title || '码上短剧')

/**
 * 顶部栏作为全局搜索入口，提交后统一跳到发现页。
 */
function submitSearch() {
    const value = keyword.value.trim()
    if (!value) {
        return
    }
    router.push({ name: 'search', query: { keyword: value } })
}
</script>

<template>
    <header class="topbar">
        <div class="topbar-inner">
            <h1 class="topbar-title">{{ pageTitle }}</h1>

            <div class="topbar-actions">
                <form class="topbar-search" role="search" @submit.prevent="submitSearch">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <circle cx="11" cy="11" r="7"/>
                        <path d="M20 20l-3.6-3.6"/>
                    </svg>
                    <input
                        v-model="keyword"
                        type="search"
                        placeholder="搜索剧名、演员"
                        aria-label="搜索短剧"
                    >
                </form>

                <RouterLink class="topbar-user" to="/me" :aria-label="`${auth.displayName}的个人中心`">
                    <UserAvatar :name="auth.displayName" :src="auth.user?.avatarUrl" :size="32"/>
                </RouterLink>
            </div>
        </div>
    </header>
</template>

<style scoped>
.topbar {
    position: sticky;
    top: 0;
    z-index: 50;
    border-bottom: 1px solid var(--line);
    background: rgba(255, 255, 255, 0.84);
    backdrop-filter: blur(18px);
}

.topbar-inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    max-width: var(--content-max);
    height: var(--topbar-height);
    margin: 0 auto;
    padding: 0 var(--shell-gutter);
}

.topbar-title {
    overflow: hidden;
    font-size: 17px;
    font-weight: 800;
    letter-spacing: 0.01em;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.topbar-actions {
    display: flex;
    flex: none;
    align-items: center;
    gap: 12px;
}

.topbar-search {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 280px;
    height: 38px;
    padding: 0 12px;
    border: 1px solid var(--line-strong);
    border-radius: 10px;
    background: var(--surface);
    color: var(--muted);
    transition: border-color 150ms ease, box-shadow 150ms ease;
}

.topbar-search:focus-within {
    border-color: rgba(184, 58, 38, 0.5);
    box-shadow: 0 0 0 3px rgba(184, 58, 38, 0.1);
}

.topbar-search svg {
    flex: none;
    width: 16px;
    height: 16px;
}

.topbar-search input {
    width: 100%;
    min-width: 0;
    border: none;
    background: transparent;
    color: var(--text);
    font-size: 13px;
    outline: none;
}

.topbar-search input::placeholder {
    color: var(--muted-soft);
}

.topbar-search input::-webkit-search-cancel-button {
    display: none;
}

.topbar-user {
    display: inline-flex;
    align-items: center;
    border-radius: 50%;
    outline-offset: 3px;
}
</style>
