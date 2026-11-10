<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppTabBar from './components/AppTabBar.vue'
import ToastHost from './components/ToastHost.vue'
import GlobalAssistant from './components/assistant/GlobalAssistant.vue'
import DesktopSidebar from './components/layout/DesktopSidebar.vue'
import DesktopTopbar from './components/layout/DesktopTopbar.vue'
import { useMediaQuery } from './composables/useMediaQuery'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const auth = useAuthStore()

const isDesktop = useMediaQuery('(min-width: 1024px)')
const activeTab = computed(() => route.meta.tab || '')
const immersive = computed(() => Boolean(route.meta.immersive))
const isPublic = computed(() => Boolean(route.meta.public))
const assistantOpen = ref(false)

// 1. 桌面端普通页面和播放页都保留侧栏，播放页不显示顶部栏
const showSidebar = computed(() => isDesktop.value && !isPublic.value)
const showTopbar = computed(() => showSidebar.value && !immersive.value)

// 2. 底部 TabBar 只在移动端出现，桌面端导航改由侧栏承担
const showTab = computed(() => Boolean(activeTab.value) && !isDesktop.value)

// 3. 全局找剧助手只对已登录的普通页面展示，播放页保持沉浸
const showAssistant = computed(() => (
    !isPublic.value && !immersive.value && auth.hasValidSession()
))
</script>

<template>
    <div class="app-root" :class="{ 'has-chrome': showSidebar }">
        <DesktopSidebar
            v-if="showSidebar"
            :assistant-enabled="!immersive"
            @assistant="assistantOpen = true"
        />

        <div
            class="app-frame"
            :class="{ 'has-tab': showTab, 'is-public': isPublic, 'is-immersive': immersive }"
        >
            <DesktopTopbar v-if="showTopbar"/>
            <RouterView :key="route.fullPath"/>
            <AppTabBar v-if="showTab" :active="activeTab"/>
        </div>

        <button
            v-if="showAssistant && !isDesktop"
            class="assistant-fab"
            type="button"
            @click="assistantOpen = true"
        >
            AI 找剧
        </button>

        <GlobalAssistant v-if="showAssistant" v-model="assistantOpen"/>

        <ToastHost :has-tab="showTab" :immersive="immersive"/>
    </div>
</template>

<style scoped>
.app-root {
    min-height: 100dvh;
}

.app-root.has-chrome {
    display: grid;
    grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
}

/* 桌面端取消移动壳的窄屏约束，内容宽度交给 .app-main 控制 */
.app-root.has-chrome .app-frame {
    width: 100%;
    max-width: none;
    margin: 0;
    border: 0;
    box-shadow: none;
    background: transparent;
}

.assistant-fab {
    position: fixed;
    right: 16px;
    bottom: calc(var(--tabbar-height, 64px) + env(safe-area-inset-bottom) + 14px);
    z-index: 35;
    height: 38px;
    padding: 0 14px;
    border: 1px solid rgba(184, 58, 38, 0.28);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.94);
    box-shadow: var(--shadow-soft);
    color: var(--brand-ink);
    font-size: 12px;
    font-weight: 700;
    backdrop-filter: blur(12px);
}
</style>
