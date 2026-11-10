<script setup>
defineProps({
    active: {
        type: String,
        default: 'home'
    }
})

const tabs = [
    { key: 'home', label: '首页', to: '/' },
    { key: 'history', label: '追剧', to: '/history' },
    { key: 'me', label: '我的', to: '/me' }
]
</script>

<template>
    <nav class="tabbar" aria-label="主导航">
        <RouterLink
            v-for="tab in tabs"
            :key="tab.key"
            :to="tab.to"
            class="tab-item"
            :class="{ 'is-active': active === tab.key }"
            :aria-current="active === tab.key ? 'page' : undefined"
        >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <template v-if="tab.key === 'home'">
                    <path d="M3 10.5 12 3l9 7.5"/>
                    <path d="M5 9.5V20a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V9.5"/>
                </template>
                <template v-else-if="tab.key === 'history'">
                    <circle cx="12" cy="12" r="9"/>
                    <path d="M10 8.5l6 3.5-6 3.5z"/>
                </template>
                <template v-else>
                    <circle cx="12" cy="8" r="3.6"/>
                    <path d="M4.5 20c.8-3.6 3.7-5.6 7.5-5.6s6.7 2 7.5 5.6"/>
                </template>
            </svg>
            <span>{{ tab.label }}</span>
        </RouterLink>
    </nav>
</template>

<style scoped>
.tabbar {
    position: fixed;
    bottom: 0;
    left: 50%;
    z-index: 40;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    width: min(100%, var(--app-width, 480px));
    padding-bottom: env(safe-area-inset-bottom);
    border-top: 1px solid var(--line);
    background: rgba(255, 255, 255, 0.92);
    backdrop-filter: blur(18px);
    transform: translateX(-50%);
}

.tab-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    height: var(--tabbar-height, 64px);
    color: var(--muted);
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.04em;
    -webkit-tap-highlight-color: transparent;
}

.tab-item svg {
    width: 22px;
    height: 22px;
}

.tab-item.is-active {
    color: var(--brand-ink);
}

.tab-item.is-active svg {
    filter: none;
}
</style>
