<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchPlayback, unlockEpisode } from '../../api/order'
import { useToastStore } from '../../stores/toast'
import { useVideoPlayer } from '../../composables/useVideoPlayer'
import { useWatchReporter } from '../../composables/useWatchReporter'
import { formatCount } from '../../utils/format'
import GradientCover from '../GradientCover.vue'
import MockPlayer from '../player/MockPlayer.vue'
import UnlockPanel from '../player/UnlockPanel.vue'
import ReactionButton from './ReactionButton.vue'

const props = defineProps({
    drama: {
        type: Object,
        required: true
    },
    episode: {
        type: Object,
        required: true
    },
    active: {
        type: Boolean,
        default: false
    },
    suspended: {
        type: Boolean,
        default: false
    },
    unlocked: {
        type: Boolean,
        default: false
    },
    liked: {
        type: Boolean,
        default: false
    },
    favorited: {
        type: Boolean,
        default: false
    },
    reactionsEnabled: {
        type: Boolean,
        default: true
    },
    likeCount: {
        type: Number,
        default: 0
    },
    favoriteCount: {
        type: Number,
        default: 0
    },
    points: {
        type: Object,
        default: null
    },
    pointsLoading: {
        type: Boolean,
        default: false
    },
    pointsError: {
        type: String,
        default: ''
    },
    initialStartMs: {
        type: [String, Number],
        default: 0
    },
    initialPlayback: {
        type: Object,
        default: null
    },
    seekRequest: {
        type: Object,
        default: null
    }
})

const emit = defineEmits([
    'like',
    'favorite',
    'comments',
    'recap',
    'finished',
    'companion',
    'info',
    'episodes',
    'unlocked',
    'retry-points',
    'recharge',
    'progress'
])

const toast = useToastStore()

const playback = ref(null)
const loading = ref(false)
const error = ref('')
const unlocking = ref(false)
const startApplied = ref(false)
const wasPlayingBeforeSuspend = ref(false)

let activationTimer = 0
let reportTimer = 0

const durationMs = computed(() => Number(playback.value?.durationSeconds || 0) * 1000)
const authorized = computed(() => Boolean(
    playback.value && (playback.value.free || playback.value.unlocked)
))
const commentCount = computed(() => Number(props.episode.commentCount) || 0)

const player = useVideoPlayer(durationMs)
// 优先使用视频元数据里的真实时长，接口时长作为回退
const effectiveDurationMs = computed(() => player.durationMs.value || durationMs.value)

/**
 * 上报快照始终读取当前播放器状态。
 */
function snapshot() {
    if (!playback.value) {
        return null
    }
    return {
        dramaId: playback.value.dramaId,
        episodeId: playback.value.episodeId,
        positionMs: player.positionMs.value,
        durationMs: effectiveDurationMs.value,
        finished: player.finished.value
    }
}

const reporterEnabled = computed(() => (
    authorized.value && (player.started.value || player.finished.value)
))
const reporter = useWatchReporter({ enabled: reporterEnabled, snapshot })

/**
 * 首次加载才应用历史续播位置，最多到 90%。
 */
function startPosition() {
    if (startApplied.value) {
        return 0
    }
    startApplied.value = true
    const raw = Number(props.initialStartMs)
    if (!Number.isFinite(raw) || raw <= 0) {
        return 0
    }
    return Math.min(raw, durationMs.value * 0.9)
}

function startTimer() {
    stopTimer()
    reportTimer = window.setInterval(() => {
        if (!props.active || props.suspended) {
            return
        }
        if (!player.playing.value || player.finished.value) {
            return
        }
        if (player.positionMs.value - lastReportPosition >= 5000) {
            lastReportPosition = player.positionMs.value
            reporter.report()
        }
    }, 1000)
}

function stopTimer() {
    if (reportTimer) {
        window.clearInterval(reportTimer)
        reportTimer = null
    }
}

let lastReportPosition = 0

function applyPlayback(info) {
    playback.value = info
    player.reset(startPosition())
    applySeekRequest()
    lastReportPosition = player.positionMs.value
    if (props.active && !props.suspended && (info.free || info.unlocked) && !player.finished.value) {
        startTimer()
        player.play()
    }
}

/**
 * 应用外部字幕引用跳转；剧集不匹配时忽略。
 */
function applySeekRequest() {
    const request = props.seekRequest
    if (!request || !props.episode) {
        return
    }
    if (Number(request.episodeId) !== Number(props.episode.episodeId)) {
        return
    }
    player.seek(Math.max(0, Number(request.positionMs) || 0))
}

/**
 * 播放进度变化时每秒上报一次，供伴看字幕防剧透过滤。
 */
let lastProgressEmit = 0
watch(() => player.positionMs.value, (value) => {
    if (!props.active) {
        return
    }
    const now = Date.now()
    if (now - lastProgressEmit < 1000) {
        return
    }
    lastProgressEmit = now
    emit('progress', {
        episodeId: Number(props.episode.episodeId),
        positionMs: Math.max(0, Math.round(Number(value) || 0))
    })
})

watch(() => props.seekRequest, () => {
    applySeekRequest()
}, { immediate: true })

/**
 * 激活后才加载播放信息，减少滑动中的无效请求。
 */
async function loadPlayback(force = false) {
    if (loading.value) {
        return
    }
    if (playback.value && !force) {
        return
    }
    if (!force && props.initialPlayback
        && Number(props.initialPlayback.episodeId) === Number(props.episode.episodeId)) {
        applyPlayback(props.initialPlayback)
        return
    }

    loading.value = true
    error.value = ''
    try {
        const info = await fetchPlayback(props.episode.episodeId)
        applyPlayback(info)
    } catch (cause) {
        error.value = cause?.message || '播放信息加载失败'
    } finally {
        loading.value = false
    }
}

function cancelActivation() {
    if (activationTimer) {
        window.clearTimeout(activationTimer)
        activationTimer = 0
    }
}

/**
 * 稳定 active 后再加载/播放，避免快速滑动时连续请求。
 */
function scheduleActivation() {
    cancelActivation()
    activationTimer = window.setTimeout(() => {
        activationTimer = 0
        if (!props.active) {
            return
        }
        loadPlayback()
        startTimer()
        resumePlayback()
    }, 150)
}

function resumePlayback() {
    if (props.suspended || !authorized.value || player.finished.value) {
        return
    }
    if (!player.playing.value) {
        player.play()
    }
}

function pauseAndFlush() {
    stopTimer()
    player.pause()
    if (player.started.value) {
        reporter.report({ force: true })
        void reporter.flush({ timeoutMs: 1200 })
    }
}

watch(() => props.active, (active) => {
    if (active) {
        scheduleActivation()
    } else {
        cancelActivation()
        pauseAndFlush()
    }
}, { immediate: true })

watch(() => props.suspended, (suspended) => {
    if (!props.active) {
        return
    }
    if (suspended) {
        wasPlayingBeforeSuspend.value = player.playing.value
        stopTimer()
        player.pause()
        if (player.started.value && !player.finished.value) {
            reporter.report({ force: true })
        }
        return
    }

    startTimer()
    if (!playback.value) {
        loadPlayback()
    } else if (wasPlayingBeforeSuspend.value && authorized.value && !player.finished.value) {
        player.play()
    } else if (!player.started.value && authorized.value && !player.finished.value) {
        player.play()
    }
    wasPlayingBeforeSuspend.value = false
})

watch(() => player.finished.value, (finished) => {
    if (finished) {
        lastReportPosition = durationMs.value
        reporter.finish()
        emit('finished', props.episode)
    }
})

watch(() => player.playing.value, (playing) => {
    if (!playing && player.started.value && !player.finished.value
        && props.active && !props.suspended) {
        reporter.report({ force: true })
    }
})

async function unlock() {
    if (unlocking.value || !playback.value) {
        return
    }
    unlocking.value = true
    try {
        const result = await unlockEpisode(playback.value.episodeId)
        toast.success(result.alreadyUnlocked
            ? '该集已解锁'
            : `解锁成功，消耗 ${result.pointsCost} 积分`)

        const info = await fetchPlayback(props.episode.episodeId)
        playback.value = info
        if (!(info.free || info.unlocked)) {
            toast.push('解锁状态同步失败，请重试')
            return
        }
        emit('unlocked', {
            episodeId: info.episodeId,
            balanceAfter: result.balanceAfter
        })
        player.reset(0)
        lastReportPosition = 0
        if (props.active && !props.suspended) {
            startTimer()
            player.play()
        }
    } catch (cause) {
        toast.push(cause?.message || '解锁失败')
    } finally {
        unlocking.value = false
    }
}

function togglePlay() {
    player.toggle()
}

function onSeek(value) {
    player.seek(value)
}

function onSeekCommit(value) {
    player.seek(value)
    if (player.playing.value) {
        reporter.report({ force: true })
    }
}

function replay() {
    player.restart()
}

async function share() {
    const url = `${window.location.origin}/drama/${props.drama.id}?episode=${props.episode.episodeId}`
    try {
        await navigator.clipboard.writeText(url)
        toast.success('链接已复制')
    } catch {
        toast.push('复制失败，请手动复制地址')
    }
}

function onPageHide() {
    if (!props.active) {
        return
    }
    player.pause()
    reporter.report({ force: true, keepalive: true })
}

function onVisibilityChange() {
    if (!props.active || !document.hidden) {
        return
    }
    player.pause()
    if (!player.finished.value) {
        reporter.report({ force: true })
    }
}

onMounted(() => {
    window.addEventListener('pagehide', onPageHide)
    document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
    cancelActivation()
    stopTimer()
    window.removeEventListener('pagehide', onPageHide)
    document.removeEventListener('visibilitychange', onVisibilityChange)
    player.pause()
    if (player.started.value) {
        reporter.report({ force: true, keepalive: true })
    }
})
</script>

<template>
    <div class="feed-slide-content">
        <template v-if="playback && authorized">
            <MockPlayer
                immersive
                :show-head="false"
                :seed="episode.episodeId"
                :title="drama.title"
                :cover-url="drama.coverUrl"
                :video-url="playback.videoUrl"
                :muted="player.muted.value"
                :attach="player.attach"
                :episode-title="episode.title"
                :duration-ms="effectiveDurationMs"
                :position-ms="player.positionMs.value"
                :playing="player.playing.value"
                :finished="player.finished.value"
                @toggle="togglePlay"
                @seek="onSeek"
                @seek-commit="onSeekCommit"
                @replay="replay"
                @toggle-muted="player.toggleMuted"
            />
        </template>

        <template v-else>
            <GradientCover
                class="feed-poster"
                :seed="episode.episodeId"
                :title="drama.title"
                :src="drama.coverUrl"
                ratio="poster"
                :rounded="false"
            />
            <span class="feed-poster-scrim" aria-hidden="true"></span>

            <div v-if="loading" class="feed-center">
                <span class="feed-spinner" aria-hidden="true"></span>
                <span>加载播放信息…</span>
            </div>

            <div v-else-if="error" class="feed-center feed-error">
                <p>{{ error }}</p>
                <button class="btn btn-ghost btn-small" type="button" @click="loadPlayback(true)">
                    重试
                </button>
            </div>

            <div v-else-if="playback && !authorized" class="feed-lock">
                <UnlockPanel
                    :playback="playback"
                    :points="points"
                    :points-loading="pointsLoading"
                    :points-error="pointsError"
                    :unlocking="unlocking"
                    @unlock="unlock"
                    @retry-points="emit('retry-points')"
                    @recharge="emit('recharge')"
                />
            </div>
        </template>

        <span class="feed-top-scrim" aria-hidden="true"></span>

        <aside class="feed-rail" aria-label="互动操作">
            <ReactionButton
                type="like"
                label="点赞"
                :active="liked"
                :count="likeCount"
                :disabled="!reactionsEnabled"
                @toggle="emit('like')"
            />

            <ReactionButton
                type="favorite"
                label="收藏"
                :active="favorited"
                :count="favoriteCount"
                :disabled="!reactionsEnabled"
                @toggle="emit('favorite')"
            />

            <button class="rail-btn" type="button" aria-label="评论" @click="emit('comments')">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                     stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M4 5h16v11H9l-5 4z"/>
                </svg>
                <span>{{ formatCount(commentCount) }}</span>
            </button>

            <button class="rail-btn" type="button" aria-label="前情提要" @click="emit('recap')">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                     stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z"/>
                    <path d="M8 8h7M8 12h7"/>
                </svg>
                <span>看点</span>
            </button>

            <button class="rail-btn" type="button" aria-label="AI 伴看" @click="emit('companion')">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                     stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M4 5h16v11H9l-5 4z"/>
                    <path d="M12 8l.7 1.6 1.6.7-1.6.7-.7 1.6-.7-1.6-1.6-.7 1.6-.7z"
                          fill="currentColor" stroke="none"/>
                </svg>
                <span>伴看</span>
            </button>

            <button class="rail-btn" type="button" aria-label="复制分享链接" @click="share">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                     stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M14 4h6v6M20 4l-9 9"/>
                    <path d="M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5"/>
                </svg>
                <span>分享</span>
            </button>
        </aside>

        <div class="feed-copy">
            <button class="feed-title-btn" type="button" @click="emit('info')">
                <span class="feed-episode-badge">第{{ episode.episodeNo }}集</span>
                <strong>{{ episode.title }}</strong>
            </button>
            <button class="feed-drama-btn" type="button" @click="emit('info')">
                {{ drama.title }}
            </button>
            <p class="feed-desc">{{ drama.description || drama.subtitle || '' }}</p>
            <button class="feed-select-btn" type="button" @click="emit('episodes')">
                选集 · 共{{ drama.episodes?.length || 0 }}集
            </button>
        </div>

        <p v-if="player.finished.value" class="feed-finish-hint">上滑继续</p>
    </div>
</template>

<style scoped>
.feed-slide-content {
    position: absolute;
    inset: 0;
    overflow: hidden;
    background: #000;
}

.feed-slide-content .feed-poster {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    aspect-ratio: auto;
    border-radius: 0;
}

.feed-poster-scrim {
    position: absolute;
    inset: 0;
    background:
        linear-gradient(180deg, rgba(0, 0, 0, 0.28) 0%, transparent 26%, transparent 58%, rgba(0, 0, 0, 0.8) 100%);
}

.feed-top-scrim {
    position: absolute;
    top: 0;
    right: 0;
    left: 0;
    z-index: 14;
    height: 150px;
    background: linear-gradient(180deg, rgba(0, 0, 0, 0.62), transparent);
    pointer-events: none;
}

.feed-center {
    position: absolute;
    inset: 0;
    z-index: 4;
    display: grid;
    align-content: center;
    justify-items: center;
    gap: 10px;
    color: rgba(255, 255, 255, 0.68);
    font-size: 12px;
}

.feed-spinner {
    width: 28px;
    height: 28px;
    border: 2px solid rgba(255, 255, 255, 0.2);
    border-top-color: var(--brand-2);
    border-radius: 50%;
    animation: feed-spin 800ms linear infinite;
}

.feed-error p {
    max-width: 28ch;
    text-align: center;
}

.feed-lock {
    position: absolute;
    inset: 0;
    z-index: 4;
    display: grid;
    align-content: center;
    justify-items: center;
    overflow-y: auto;
    padding: 70px 68px 140px 16px;
    background: rgba(0, 0, 0, 0.34);
}

.feed-lock :deep(.unlock-panel) {
    width: 100%;
    max-width: 360px;
}

.feed-lock :deep(.lock-stage) {
    display: none;
}

.feed-rail {
    position: absolute;
    right: 10px;
    bottom: calc(env(safe-area-inset-bottom) + 172px);
    z-index: 15;
    display: grid;
    gap: 14px;
}

.rail-btn {
    display: grid;
    justify-items: center;
    gap: 3px;
    width: 48px;
    padding: 0;
    border: none;
    background: none;
    color: #fff;
    font-size: 10px;
    text-shadow: 0 2px 8px rgba(0, 0, 0, 0.55);
}

.rail-btn svg {
    width: 27px;
    height: 27px;
    filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.5));
}

.rail-btn.is-active {
    color: var(--brand-2);
}

.rail-btn:disabled {
    opacity: 0.45;
}

.feed-copy {
    position: absolute;
    right: 76px;
    bottom: calc(env(safe-area-inset-bottom) + 78px);
    left: 14px;
    z-index: 15;
    display: grid;
    gap: 5px;
    pointer-events: none;
    text-shadow: 0 2px 10px rgba(0, 0, 0, 0.55);
}

.feed-copy button {
    pointer-events: auto;
}

.feed-title-btn {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
    padding: 0;
    border: none;
    background: none;
    color: #fff;
    text-align: left;
}

.feed-title-btn strong {
    overflow: hidden;
    font-size: 18px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.feed-episode-badge {
    flex: none;
    padding: 2px 6px;
    border-radius: 6px;
    background: rgba(255, 179, 63, 0.92);
    color: #20120a;
    font-size: 9px;
    font-weight: 800;
}

.feed-drama-btn {
    justify-self: start;
    padding: 0;
    border: none;
    background: none;
    color: rgba(255, 255, 255, 0.72);
    font-size: 12px;
}

.feed-desc {
    display: -webkit-box;
    overflow: hidden;
    color: rgba(255, 255, 255, 0.64);
    font-size: 11px;
    line-height: 1.55;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}

.feed-select-btn {
    justify-self: start;
    margin-top: 2px;
    padding: 4px 9px;
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: 999px;
    background: rgba(0, 0, 0, 0.3);
    color: #fff;
    font-size: 10px;
    pointer-events: auto;
    backdrop-filter: blur(6px);
}

.feed-finish-hint {
    position: absolute;
    bottom: calc(env(safe-area-inset-bottom) + 134px);
    left: 50%;
    z-index: 16;
    color: rgba(255, 255, 255, 0.62);
    font-size: 11px;
    transform: translateX(-50%);
}

@keyframes feed-spin {
    to {
        transform: rotate(360deg);
    }
}

@media (prefers-reduced-motion: reduce) {
    .feed-spinner {
        animation: none;
    }
}
</style>
