<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { contentNav, isNavActive, primaryNav, studioNav, visibleTo } from '../../config/navigation'
import NavIcon from './NavIcon.vue'
import UserAvatar from '../UserAvatar.vue'

const route = useRoute()
const auth = useAuthStore()

defineProps({
    assistantEnabled: {
        type: Boolean,
        default: true
    }
})

const emit = defineEmits(['assistant'])

// 1. 工作台入口按当前用户角色过滤，前端只隐藏入口，后端仍做最终校验
const studioItems = computed(() => visibleTo(studioNav, auth.user?.role))
</script>

<template>
    <aside class="sidebar">
        <RouterLink class="sidebar-brand" to="/" aria-label="码上短剧首页">
            <span class="brand-logo">MSD</span>
            <span class="brand-name">码上短剧</span>
        </RouterLink>

        <nav class="sidebar-nav" aria-label="主导航">
            <RouterLink
                v-for="item in primaryNav"
                :key="item.key"
                :to="item.to"
                class="sidebar-link"
                :class="{ 'is-active': isNavActive(item, route.path) }"
                :aria-current="isNavActive(item, route.path) ? 'page' : undefined"
            >
                <NavIcon :name="item.icon"/>
                <span>{{ item.label }}</span>
            </RouterLink>
        </nav>

        <section class="sidebar-group">
            <h2 class="sidebar-group-title">我的内容</h2>
            <nav class="sidebar-nav" aria-label="我的内容">
                <RouterLink
                    v-for="item in contentNav"
                    :key="item.key"
                    :to="item.to"
                    class="sidebar-link"
                    :class="{ 'is-active': isNavActive(item, route.path) }"
                    :aria-current="isNavActive(item, route.path) ? 'page' : undefined"
                >
                    <NavIcon :name="item.icon"/>
                    <span>{{ item.label }}</span>
                </RouterLink>
            </nav>
        </section>

        <section v-if="studioItems.length" class="sidebar-group">
            <h2 class="sidebar-group-title">工作台</h2>
            <nav class="sidebar-nav" aria-label="工作台">
                <RouterLink
                    v-for="item in studioItems"
                    :key="item.key"
                    :to="item.to"
                    class="sidebar-link"
                    :class="{ 'is-active': isNavActive(item, route.path) }"
                    :aria-current="isNavActive(item, route.path) ? 'page' : undefined"
                >
                    <NavIcon :name="item.icon"/>
                    <span>{{ item.label }}</span>
                </RouterLink>
            </nav>
        </section>

        <button
            v-if="assistantEnabled"
            class="sidebar-assistant"
            type="button"
            @click="emit('assistant')"
        >
            <span class="sidebar-assistant-badge">AI</span>
            <span>AI 找剧</span>
        </button>

        <RouterLink class="sidebar-user" to="/me">
            <UserAvatar :name="auth.displayName" :src="auth.user?.avatarUrl" :size="34"/>
            <span class="sidebar-user-copy">
                <strong>{{ auth.displayName }}</strong>
                <small>{{ auth.roleLabel }}</small>
            </span>
        </RouterLink>
    </aside>
</template>

<style scoped>
.sidebar {
    position: sticky;
    top: 0;
    display: flex;
    flex-direction: column;
    gap: 20px;
    width: var(--sidebar-width);
    height: 100dvh;
    padding: 20px 14px 18px;
    overflow-y: auto;
    border-right: 1px solid var(--line);
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(246, 250, 243, 0.88));
    backdrop-filter: blur(18px);
}

.sidebar-brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 2px 8px 8px;
    color: var(--text);
}

.sidebar-brand .brand-logo {
    width: 36px;
    height: 36px;
    border-radius: 11px;
    font-size: 11px;
}

.sidebar-brand .brand-name {
    font-size: 15px;
}

.sidebar-nav {
    display: grid;
    gap: 2px;
}

.sidebar-group {
    display: grid;
    gap: 6px;
}

.sidebar-group-title {
    padding: 0 10px;
    color: var(--muted-soft);
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.14em;
    text-transform: uppercase;
}

.sidebar-link {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 38px;
    padding: 0 10px;
    border-radius: 10px;
    color: var(--muted);
    font-size: 13px;
    font-weight: 600;
    transition: background 140ms ease, color 140ms ease;
}

.sidebar-link:hover {
    background: var(--surface-muted);
    color: var(--text);
}

.sidebar-link.is-active {
    background: var(--brand-soft);
    color: var(--brand-ink);
}

.sidebar-link.is-active :deep(.nav-icon) {
    filter: none;
}

.sidebar-assistant {
    display: flex;
    align-items: center;
    gap: 9px;
    margin-top: auto;
    height: 40px;
    padding: 0 12px;
    border: 1px solid rgba(184, 58, 38, 0.24);
    border-radius: 11px;
    background: linear-gradient(135deg, #ffe7e0, #fff2d8);
    color: var(--brand-ink);
    font-size: 12px;
    font-weight: 700;
    text-align: left;
}

.sidebar-assistant:hover {
    border-color: rgba(184, 58, 38, 0.45);
}

.sidebar-assistant-badge {
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border-radius: 7px;
    background: rgba(255, 255, 255, 0.72);
    font-size: 9px;
    letter-spacing: 0.04em;
}

.sidebar-user {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface);
    color: var(--text);
    transition: border-color 140ms ease, box-shadow 140ms ease;
}

.sidebar-user:hover {
    border-color: rgba(184, 58, 38, 0.32);
    box-shadow: var(--shadow-soft);
}

.sidebar-user-copy {
    display: grid;
    min-width: 0;
    gap: 1px;
}

.sidebar-user-copy strong {
    overflow: hidden;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.sidebar-user-copy small {
    color: var(--muted);
    font-size: 10px;
}
</style>
