<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchPlayback } from '../api/order'
import DramaFeed from '../components/feed/DramaFeed.vue'
import ErrorState from '../components/ErrorState.vue'

const props = defineProps({
    episodeId: {
        type: String,
        required: true
    }
})

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const playback = ref(null)

const initialStartMs = computed(() => {
    const value = route.query.start
    const raw = Array.isArray(value) ? value[0] : value
    return Number(raw) || 0
})

/**
 * /watch 旧入口只负责把 episode 解析成 dramaId，播放交给统一 Feed。
 */
async function load() {
    loading.value = true
    error.value = ''
    try {
        playback.value = await fetchPlayback(props.episodeId)
    } catch (cause) {
        playback.value = null
        error.value = cause?.message || '播放信息加载失败'
    } finally {
        loading.value = false
    }
}

function goBack() {
    if (window.history.state?.back) {
        router.back()
        return
    }
    router.replace('/')
}

onMounted(load)
</script>

<template>
    <div class="watch-page">
        <template v-if="loading || error">
            <header class="watch-bar">
                <button class="watch-back" type="button" aria-label="返回" @click="goBack">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <path d="M15 5l-7 7 7 7"/>
                    </svg>
                </button>
                <strong>播放</strong>
            </header>

            <div v-if="loading" class="watch-state">
                <span class="watch-spinner" aria-hidden="true"></span>
                <span>解析剧集…</span>
            </div>

            <div v-else class="watch-state">
                <ErrorState :message="error" @retry="load"/>
            </div>
        </template>

        <DramaFeed
            v-else-if="playback"
            :drama-id="String(playback.dramaId)"
            :initial-episode-id="String(playback.episodeId)"
            :initial-start-ms="initialStartMs"
            :initial-playback="playback"
        />
    </div>
</template>

<style scoped>
.watch-page {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 50%;
    z-index: 0;
    width: min(100%, var(--app-width));
    overflow: hidden;
    background: #000;
    transform: translateX(-50%);
}

.watch-bar {
    position: absolute;
    top: 0;
    right: 0;
    left: 0;
    z-index: 2;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: calc(env(safe-area-inset-top) + 10px) 12px 18px;
    background: linear-gradient(180deg, rgba(0, 0, 0, 0.62), transparent);
    color: #fff;
}

.watch-bar strong {
    font-size: 14px;
}

.watch-back {
    display: grid;
    flex: none;
    place-items: center;
    width: 36px;
    height: 36px;
    padding: 0;
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: 50%;
    background: rgba(0, 0, 0, 0.32);
    color: #fff;
}

.watch-back svg {
    width: 18px;
    height: 18px;
}

.watch-state {
    position: absolute;
    inset: 0;
    display: grid;
    align-content: center;
    justify-items: center;
    gap: 10px;
    padding: 80px 20px;
    color: var(--muted);
    font-size: 12px;
}

.watch-spinner {
    width: 28px;
    height: 28px;
    border: 2px solid rgba(255, 255, 255, 0.2);
    border-top-color: var(--brand-2);
    border-radius: 50%;
    animation: watch-spin 800ms linear infinite;
}

/* 桌面端取消 480 移动壳，为常驻侧栏让出空间 */
@media (min-width: 1024px) {
    .watch-page {
        left: var(--sidebar-width);
        width: calc(100% - var(--sidebar-width));
        background: var(--bg);
        transform: none;
    }

    .watch-bar {
        padding: 18px 24px;
        background: transparent;
        color: var(--text);
    }

    .watch-back {
        border-color: var(--line-strong);
        background: var(--surface);
        color: var(--text-soft);
        box-shadow: var(--shadow-soft);
    }

    .watch-state {
        color: var(--muted);
    }

    .watch-spinner {
        border-color: var(--line-strong);
        border-top-color: var(--brand);
    }
}

@keyframes watch-spin {
    to {
        transform: rotate(360deg);
    }
}

@media (prefers-reduced-motion: reduce) {
    .watch-spinner {
        animation: none;
    }
}
</style>
