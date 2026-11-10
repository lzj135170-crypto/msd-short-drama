<script setup>
import { computed, useSlots } from 'vue'
import { useRouter } from 'vue-router'
import { useMediaQuery } from '../composables/useMediaQuery'

const props = defineProps({
    title: {
        type: String,
        default: ''
    },
    subtitle: {
        type: String,
        default: ''
    },
    showBack: {
        type: Boolean,
        default: true
    },
    backTo: {
        type: String,
        default: '/'
    }
})

const router = useRouter()
const slots = useSlots()
const isDesktop = useMediaQuery('(min-width: 1024px)')

// 1. 桌面端标题已由顶部栏承担；没有副标题和操作按钮时不再渲染这一行
const showHeader = computed(() => (
    !isDesktop.value || Boolean(props.subtitle) || Boolean(slots.right)
))

/**
 * 有站内历史时返回上一页，直接打开深链时回退到指定页面。
 */
function goBack() {
    if (window.history.state?.back) {
        router.back()
        return
    }
    router.replace(props.backTo)
}
</script>

<template>
    <header v-if="showHeader" class="page-header">
        <div class="page-header-inner">
            <button v-if="showBack" class="icon-btn" type="button" aria-label="返回" @click="goBack">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                     stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M15 5l-7 7 7 7"/>
                </svg>
            </button>
            <div class="page-header-copy">
                <h1>{{ title }}</h1>
                <span v-if="subtitle">{{ subtitle }}</span>
            </div>
            <div class="page-header-right">
                <slot name="right"/>
            </div>
        </div>
    </header>
</template>

<style scoped>
.page-header {
    position: sticky;
    top: 0;
    z-index: 30;
    border-bottom: 1px solid var(--line);
    background: rgba(255, 255, 255, 0.86);
    backdrop-filter: blur(18px);
}

.page-header-inner {
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 56px;
    padding: calc(env(safe-area-inset-top) + 8px) 14px 8px;
}

.icon-btn {
    display: inline-grid;
    flex: none;
    place-items: center;
    width: 36px;
    height: 36px;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface);
    color: var(--text);
}

.icon-btn svg {
    width: 18px;
    height: 18px;
}

.page-header-copy {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    gap: 1px;
}

.page-header-copy h1 {
    overflow: hidden;
    font-size: 16px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.page-header-copy span {
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.page-header-right {
    display: flex;
    flex: none;
    align-items: center;
    gap: 8px;
}

@media (min-width: 1024px) {
    /* 桌面端标题由顶部栏承担，这里退化成页面级操作条 */
    .page-header {
        position: static;
        border-bottom: none;
        background: none;
        backdrop-filter: none;
    }

    .page-header-inner {
        width: 100%;
        max-width: var(--content-max);
        min-height: 0;
        margin: 0 auto;
        padding: 18px var(--shell-gutter) 0;
    }

    .page-header .icon-btn {
        display: none;
    }

    .page-header-copy h1 {
        display: none;
    }

    .page-header-copy span {
        font-size: 13px;
    }
}
</style>
