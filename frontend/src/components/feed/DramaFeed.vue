<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchDramaDetail, fetchDramaStats } from '../../api/drama'
import {
    favoriteDrama,
    fetchDramaState,
    likeTarget,
    trackRecommendationEvent,
    unfavoriteDrama,
    unlikeTarget
} from '../../api/interaction'
import { fetchDramaUnlocks, fetchPoints } from '../../api/order'
import { lockBodyScroll, unlockBodyScroll } from '../../composables/useBodyScrollLock'
import { useInteractionAck } from '../../composables/useInteractionAck'
import { useMediaQuery } from '../../composables/useMediaQuery'
import { useToastStore } from '../../stores/toast'
import AppModal from '../AppModal.vue'
import CommentSection from '../comments/CommentSection.vue'
import CompanionPanel from '../companion/CompanionPanel.vue'
import EpisodeRecap from '../companion/EpisodeRecap.vue'
import ErrorState from '../ErrorState.vue'
import DramaInfoSheet from './DramaInfoSheet.vue'
import EpisodeSheet from './EpisodeSheet.vue'
import FeedEndSlide from './FeedEndSlide.vue'
import FeedSlide from './FeedSlide.vue'
import FeedWorkspace from './FeedWorkspace.vue'

const props = defineProps({
    dramaId: {
        type: [String, Number],
        required: true
    },
    initialEpisodeId: {
        type: [String, Number],
        default: null
    },
    initialStartMs: {
        type: [String, Number],
        default: 0
    },
    initialPlayback: {
        type: Object,
        default: null
    }
})

const route = useRoute()
const router = useRouter()
const toast = useToastStore()

const detail = ref(null)
const state = ref(null)
const stateError = ref('')
const unlockedIds = ref(new Set())
const points = ref(null)
const pointsLoading = ref(true)
const pointsError = ref('')

const loading = ref(true)
const error = ref('')
const activeIndex = ref(0)
const initialSlideIndex = ref(0)

const infoOpen = ref(false)
const episodesOpen = ref(false)
const commentsOpen = ref(false)
const companionOpen = ref(false)
const recapOpen = ref(false)
const commentsScope = ref('episode')
const workspaceTab = ref('info')
const activePositionMs = ref(0)
const seekRequest = ref(null)
let seekSequence = 0

// 1. 桌面端断点：1024 起剧场居中，右侧常驻信息/工作区
const isDesktop = useMediaQuery('(min-width: 1024px)')

const pending = reactive({ like: false, favorite: false })
const likeAckId = ref('')
const favoriteAckId = ref('')
const ack = useInteractionAck()
const scroller = ref(null)

let slideObserver = null
let fallbackScrollBound = false
let programmatic = false
let programmaticTimer = 0

const episodes = computed(() => detail.value?.episodes || [])
const activeEpisode = computed(() => episodes.value[activeIndex.value] || null)
const indicator = computed(() => {
    const total = episodes.value.length
    if (!total) {
        return ''
    }
    return activeEpisode.value
        ? `${activeEpisode.value.episodeNo}/${total}`
        : `${total}/${total}`
})
const suspended = computed(() => (
    infoOpen.value || episodesOpen.value || commentsOpen.value
    || companionOpen.value || recapOpen.value
))
const reactionsEnabled = computed(() => Boolean(state.value))
const recommendationContext = computed(() => {
    const requestId = typeof route.query.rec_request === 'string'
        ? route.query.rec_request : ''
    const dramaId = Number(route.query.rec_drama)
    if (!requestId || dramaId !== Number(props.dramaId)) {
        return null
    }
    return {
        requestId,
        dramaId,
        position: Number(route.query.rec_pos) || 0
    }
})

/**
 * 上报推荐播放/完播；没有推荐上下文时静默跳过。
 */
function reportRecommendation(eventType) {
    const context = recommendationContext.value
    if (!context) {
        return
    }
    trackRecommendationEvent({
        requestId: context.requestId,
        dramaId: context.dramaId,
        eventType,
        position: context.position
    }).catch(() => {
        // 归因失败不影响播放
    })
}

/**
 * 当前短剧播放完成。
 */
function onRecommendationFinish() {
    reportRecommendation(3)
}

function resolveInitialIndex() {
    const target = Number(props.initialEpisodeId)
    if (!Number.isFinite(target) || target <= 0) {
        return 0
    }
    const index = episodes.value.findIndex((episode) => Number(episode.episodeId) === target)
    return index >= 0 ? index : 0
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

/**
 * 加载详情、状态、解锁列表和积分；非详情失败都允许降级。
 */
async function load() {
    loading.value = true
    error.value = ''
    state.value = null
    stateError.value = ''

    try {
        detail.value = await fetchDramaDetail(props.dramaId)
    } catch (cause) {
        detail.value = null
        error.value = cause?.message || '短剧加载失败'
        loading.value = false
        return
    }

    if (!episodes.value.length) {
        error.value = '暂无可播放剧集'
        loading.value = false
        return
    }

    const [stateResult, unlockResult, pointsResult] = await Promise.allSettled([
        fetchDramaState(props.dramaId),
        fetchDramaUnlocks(props.dramaId),
        fetchPoints()
    ])

    if (stateResult.status === 'fulfilled') {
        state.value = stateResult.value
    } else {
        stateError.value = stateResult.reason?.message || '互动状态加载失败'
    }

    if (unlockResult.status === 'fulfilled') {
        unlockedIds.value = new Set(unlockResult.value?.unlockedEpisodeIds || [])
    }

    if (pointsResult.status === 'fulfilled') {
        points.value = pointsResult.value
        pointsError.value = ''
    } else {
        pointsError.value = pointsResult.reason?.message || '积分加载失败'
    }
    pointsLoading.value = false

    initialSlideIndex.value = resolveInitialIndex()
    activeIndex.value = initialSlideIndex.value
    loading.value = false
    reportRecommendation(2)

    await nextTick()
    setupScroller()
}

function setupScroller() {
    const element = scroller.value
    if (!element) {
        return
    }
    window.requestAnimationFrame(() => {
        if (!scroller.value) {
            return
        }
        scroller.value.scrollTop = initialSlideIndex.value * scroller.value.clientHeight
        observeSlides()
    })
}

function observeSlides() {
    const element = scroller.value
    if (!element) {
        return
    }
    slideObserver?.disconnect()

    if (!('IntersectionObserver' in window)) {
        if (!fallbackScrollBound) {
            element.addEventListener('scroll', onFallbackScroll, { passive: true })
            fallbackScrollBound = true
        }
        return
    }

    slideObserver = new IntersectionObserver((entries) => {
        if (programmatic) {
            return
        }
        entries.forEach((entry) => {
            if (!entry.isIntersecting || entry.intersectionRatio < 0.6) {
                return
            }
            const index = Number(entry.target.dataset.index)
            if (Number.isFinite(index)) {
                activeIndex.value = index
            }
        })
    }, { root: element, threshold: [0.6] })

    element.querySelectorAll('.feed-slide').forEach((slide) => {
        slideObserver.observe(slide)
    })
}

function onFallbackScroll() {
    const element = scroller.value
    if (!element || programmatic) {
        return
    }
    const height = element.clientHeight || 1
    activeIndex.value = Math.min(
        episodes.value.length,
        Math.max(0, Math.round(element.scrollTop / height))
    )
}

function onResize() {
    const element = scroller.value
    if (!element) {
        return
    }
    element.scrollTop = activeIndex.value * element.clientHeight
}

function scrollToIndex(index, { smooth = true } = {}) {
    const element = scroller.value
    if (!element) {
        return
    }
    programmatic = true
    window.clearTimeout(programmaticTimer)
    activeIndex.value = index
    element.scrollTo({
        top: index * element.clientHeight,
        behavior: smooth ? 'smooth' : 'auto'
    })
    programmaticTimer = window.setTimeout(() => {
        programmatic = false
        activeIndex.value = index
    }, 500)
}

/**
 * 用户手势打断平滑滚动时，恢复观察器判定。
 */
function cancelProgrammatic() {
    if (!programmatic) {
        return
    }
    programmatic = false
    window.clearTimeout(programmaticTimer)
    programmaticTimer = 0
}

function goBack() {
    if (window.history.state?.back) {
        router.back()
        return
    }
    router.replace('/')
}

function goHome() {
    router.push('/')
}

function replayFirst() {
    scrollToIndex(0)
}

function onRecapSelect(episodeId) {
    recapOpen.value = false
    selectEpisode(episodeId)
}

/**
 * 记录当前集播放位置，供伴看字幕防剧透过滤。
 */
function onSlideProgress(payload) {
    if (!payload || !activeEpisode.value) {
        return
    }
    if (Number(payload.episodeId) !== Number(activeEpisode.value.episodeId)) {
        return
    }
    activePositionMs.value = Math.max(0, Number(payload.positionMs) || 0)
}

/**
 * 点击伴看字幕引用：切到目标集并请求 seek。
 */
function onCompanionSeek(payload) {
    const episodeId = Number(payload?.episodeId)
    if (!episodeId) {
        return
    }
    const index = episodes.value.findIndex((episode) => Number(episode.episodeId) === episodeId)
    if (index < 0) {
        return
    }
    if (index !== activeIndex.value) {
        selectEpisode(episodeId)
    }
    seekSequence += 1
    seekRequest.value = {
        episodeId,
        positionMs: Math.max(0, Number(payload.positionMs) || 0),
        nonce: seekSequence
    }
}

watch(activeIndex, () => {
    activePositionMs.value = 0
})

function selectEpisode(episodeId) {
    const index = episodes.value.findIndex((episode) => Number(episode.episodeId) === Number(episodeId))
    if (index < 0) {
        return
    }
    // 1. 桌面端工作区常驻，切集只滚动 Feed，不关闭面板
    if (isDesktop.value) {
        nextTick(() => scrollToIndex(index))
        return
    }
    episodesOpen.value = false
    nextTick(() => scrollToIndex(index))
}

/**
 * 简介入口：桌面切右侧工作区简介标签，移动端打开底部弹层。
 */
function openInfo() {
    if (isDesktop.value) {
        workspaceTab.value = 'info'
        return
    }
    infoOpen.value = true
}

/**
 * 选集入口：桌面切工作区标签，移动端打开底部弹层。
 */
function openEpisodes() {
    if (isDesktop.value) {
        workspaceTab.value = 'episodes'
        return
    }
    episodesOpen.value = true
}

/**
 * 前情提要入口：桌面切工作区标签，移动端打开底部弹层。
 */
function openRecap() {
    if (!activeEpisode.value) {
        return
    }
    if (isDesktop.value) {
        workspaceTab.value = 'recap'
        return
    }
    recapOpen.value = true
}

function openComments() {
    if (!activeEpisode.value) {
        return
    }
    commentsScope.value = 'episode'
    if (isDesktop.value) {
        workspaceTab.value = 'comments'
        return
    }
    commentsOpen.value = true
}

/**
 * 当前集免费或已解锁时才允许打开伴看，避免剧透未解锁内容。
 */
function openCompanion() {
    const episode = activeEpisode.value
    if (!episode) {
        return
    }
    const unlocked = Boolean(episode.free) || unlockedIds.value.has(Number(episode.episodeId))
    if (!unlocked) {
        toast.push('解锁本集后可使用伴看')
        return
    }
    if (isDesktop.value) {
        workspaceTab.value = 'companion'
        return
    }
    companionOpen.value = true
}

function onUnlocked({ episodeId, balanceAfter }) {
    unlockedIds.value = new Set([...unlockedIds.value, Number(episodeId)])
    points.value = {
        ...(points.value || { userId: null, exists: true }),
        exists: true,
        balance: balanceAfter
    }
    pointsError.value = ''
}

function adjustCounter(key, delta) {
    if (!detail.value?.counters) {
        return
    }
    detail.value.counters[key] = Math.max(0, Number(detail.value.counters[key] || 0) + delta)
}

/**
 * 异步计数完成后拉取 drama 统计，覆盖本地乐观值。
 */
async function refreshCounters() {
    try {
        const stats = await fetchDramaStats(Number(props.dramaId))
        if (!detail.value?.counters || !stats) {
            return
        }
        detail.value.counters.likeCount = stats.likeCount
        detail.value.counters.favoriteCount = stats.favoriteCount
        detail.value.counters.commentCount = stats.commentCount
        detail.value.counters.viewCount = stats.viewCount
    } catch {
        // 计数校准失败不影响已完成的互动
    }
}

/**
 * 事件终态为 DEAD 时回滚乐观状态；只回滚当前事件，避免覆盖后续点击。
 */
function rollbackLike(eventId, previous) {
    if (likeAckId.value !== eventId) {
        return
    }
    likeAckId.value = ''
    state.value = { ...state.value, liked: previous }
    adjustCounter('likeCount', previous ? 1 : -1)
    toast.push('点赞同步失败，已恢复状态')
}

function rollbackFavorite(eventId, previous) {
    if (favoriteAckId.value !== eventId) {
        return
    }
    favoriteAckId.value = ''
    state.value = { ...state.value, favorited: previous }
    adjustCounter('favoriteCount', previous ? 1 : -1)
    toast.push('收藏同步失败，已恢复状态')
}

async function toggleLike() {
    if (!state.value || pending.like) {
        return
    }
    const previous = state.value.liked
    pending.like = true
    state.value = { ...state.value, liked: !previous }
    adjustCounter('likeCount', previous ? -1 : 1)

    try {
        const result = previous
            ? await unlikeTarget(1, Number(props.dramaId))
            : await likeTarget(1, Number(props.dramaId))
        state.value = { ...state.value, liked: result.active }
        if (result.eventId) {
            likeAckId.value = result.eventId
            ack.track(result.eventId, {
                onConsumed: refreshCounters,
                onTimeout: refreshCounters,
                onDead: () => rollbackLike(result.eventId, previous)
            })
        }
    } catch (cause) {
        state.value = { ...state.value, liked: previous }
        adjustCounter('likeCount', previous ? 1 : -1)
        toast.push(cause?.message || '点赞失败')
    } finally {
        pending.like = false
    }
}

async function toggleFavorite() {
    if (!state.value || pending.favorite) {
        return
    }
    const previous = state.value.favorited
    pending.favorite = true
    state.value = { ...state.value, favorited: !previous }
    adjustCounter('favoriteCount', previous ? -1 : 1)

    try {
        const result = previous
            ? await unfavoriteDrama(Number(props.dramaId))
            : await favoriteDrama(Number(props.dramaId))
        state.value = { ...state.value, favorited: result.active }
        if (result.eventId) {
            favoriteAckId.value = result.eventId
            ack.track(result.eventId, {
                onConsumed: refreshCounters,
                onTimeout: refreshCounters,
                onDead: () => rollbackFavorite(result.eventId, previous)
            })
        }
    } catch (cause) {
        state.value = { ...state.value, favorited: previous }
        adjustCounter('favoriteCount', previous ? 1 : -1)
        toast.push(cause?.message || '收藏失败')
    } finally {
        pending.favorite = false
    }
}

// 2. 切到未解锁剧集时，伴看标签自动退回选集
watch(activeIndex, () => {
    if (!isDesktop.value || workspaceTab.value !== 'companion') {
        return
    }
    const episode = activeEpisode.value
    if (!episode) {
        return
    }
    const unlocked = Boolean(episode.free) || unlockedIds.value.has(Number(episode.episodeId))
    if (!unlocked) {
        workspaceTab.value = 'episodes'
        toast.push('解锁本集后可使用伴看')
    }
})

// 3. 进入桌面布局时收起移动端弹层，保证评论和伴看只有一份实例
watch(isDesktop, (desktop) => {
    if (!desktop) {
        return
    }
    infoOpen.value = false
    episodesOpen.value = false
    commentsOpen.value = false
    companionOpen.value = false
})

onMounted(() => {
    lockBodyScroll()
    window.addEventListener('resize', onResize)
    load()
})

onBeforeUnmount(() => {
    unlockBodyScroll()
    window.clearTimeout(programmaticTimer)
    window.removeEventListener('resize', onResize)
    if (fallbackScrollBound && scroller.value) {
        scroller.value.removeEventListener('scroll', onFallbackScroll)
    }
    slideObserver?.disconnect()
    slideObserver = null
})
</script>

<template>
    <div class="feed-shell" :class="{ 'has-workspace': isDesktop }">
        <div v-if="isDesktop && detail" class="feed-ambient" aria-hidden="true">
            <img v-if="detail.coverUrl" class="feed-ambient-img" :src="detail.coverUrl" alt="">
            <span class="feed-ambient-veil"></span>
        </div>

        <div class="feed-stage" :class="{ 'is-desktop': isDesktop }">
        <header class="feed-top">
            <button class="feed-back" type="button" aria-label="返回" @click="goBack">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                     stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M15 5l-7 7 7 7"/>
                </svg>
            </button>
            <div class="feed-top-copy">
                <strong>{{ detail?.title || '短剧' }}</strong>
                <span v-if="indicator">{{ indicator }}</span>
            </div>
        </header>

        <div v-if="loading" class="feed-state-body">
            <span class="feed-spinner" aria-hidden="true"></span>
            <span>加载短剧中…</span>
        </div>

        <div v-else-if="error" class="feed-state-body">
            <ErrorState :message="error" @retry="load"/>
        </div>

        <template v-else-if="detail">
            <div
                ref="scroller"
                class="feed-scroller"
                tabindex="0"
                @wheel.passive="cancelProgrammatic"
                @touchstart.passive="cancelProgrammatic"
            >
                <section
                    v-for="(episode, index) in episodes"
                    :key="episode.episodeId"
                    class="feed-slide"
                    :data-index="index"
                    :inert="index !== activeIndex ? '' : null"
                    :aria-hidden="index !== activeIndex ? 'true' : 'false'"
                >
                    <FeedSlide
                        :drama="detail"
                        :episode="episode"
                        :active="index === activeIndex"
                        :suspended="suspended"
                        :unlocked="episode.free || unlockedIds.has(episode.episodeId)"
                        :liked="Boolean(state?.liked)"
                        :favorited="Boolean(state?.favorited)"
                        :reactions-enabled="reactionsEnabled"
                        :like-count="Number(detail.counters?.likeCount || 0)"
                        :favorite-count="Number(detail.counters?.favoriteCount || 0)"
                        :points="points"
                        :points-loading="pointsLoading"
                        :points-error="pointsError"
                        :initial-start-ms="index === initialSlideIndex ? initialStartMs : 0"
                        :initial-playback="initialPlayback
                            && Number(initialPlayback.episodeId) === Number(episode.episodeId)
                            ? initialPlayback
                            : null"
                        :seek-request="seekRequest"
                        @progress="onSlideProgress"
                        @like="toggleLike"
                        @favorite="toggleFavorite"
                        @comments="openComments"
                        @recap="openRecap"
                        @finished="onRecommendationFinish"
                        @companion="openCompanion"
                        @info="openInfo"
                        @episodes="openEpisodes"
                        @unlocked="onUnlocked"
                        @retry-points="loadPoints"
                        @recharge="router.push('/recharge')"
                    />
                </section>

                <section
                    class="feed-slide"
                    :data-index="episodes.length"
                    :inert="activeIndex !== episodes.length ? '' : null"
                    :aria-hidden="activeIndex !== episodes.length ? 'true' : 'false'"
                >
                    <FeedEndSlide :drama="detail" @home="goHome" @replay="replayFirst"/>
                </section>
            </div>

            <p v-if="stateError" class="feed-state-error">{{ stateError }}</p>
        </template>
        </div>

        <FeedWorkspace
            v-if="isDesktop && detail"
            :tab="workspaceTab"
            :comments-scope="commentsScope"
            :detail="detail"
            :episodes="episodes"
            :active-episode="activeEpisode"
            :unlocked-ids="unlockedIds"
            :position-ms="activePositionMs"
            @update:tab="workspaceTab = $event"
            @update:comments-scope="commentsScope = $event"
            @select="selectEpisode"
            @seek="onCompanionSeek"
        />

        <template v-if="detail">
            <DramaInfoSheet v-if="!isDesktop" v-model="infoOpen" :detail="detail"/>

            <template v-if="!isDesktop">
            <EpisodeSheet
                v-model="episodesOpen"
                :episodes="episodes"
                :current-id="activeEpisode?.episodeId || 0"
                :unlocked-ids="unlockedIds"
                @select="selectEpisode"
            />

            <AppModal v-model="commentsOpen" title="评论">
                <div class="comment-tabs">
                    <button
                        class="comment-tab"
                        :class="{ 'is-active': commentsScope === 'episode' }"
                        type="button"
                        @click="commentsScope = 'episode'"
                    >
                        本集
                    </button>
                    <button
                        class="comment-tab"
                        :class="{ 'is-active': commentsScope === 'drama' }"
                        type="button"
                        @click="commentsScope = 'drama'"
                    >
                        全剧
                    </button>
                </div>

                <CommentSection
                    v-if="commentsOpen && activeEpisode"
                    :key="commentsScope"
                    :drama-id="detail.id"
                    :episode-id="commentsScope === 'episode' ? activeEpisode.episodeId : null"
                    :show-title="false"
                />
            </AppModal>

            <CompanionPanel
                v-model="companionOpen"
                :drama-id="detail.id"
                :drama-title="detail.title"
                :episode="activeEpisode"
                :position-ms="activePositionMs"
                @seek="onCompanionSeek"
            />

            <AppModal v-model="recapOpen" title="前情提要">
                <EpisodeRecap
                    v-if="activeEpisode"
                    :drama-id="detail.id"
                    :episode-no="activeEpisode.episodeNo"
                    @select="onRecapSelect"
                />
            </AppModal>
            </template>
        </template>
    </div>
</template>

<style scoped>
.feed-shell {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 50%;
    z-index: 1;
    width: min(100%, var(--app-width));
    overflow: hidden;
    background: #000;
    transform: translateX(-50%);
}

/* 移动端 stage 铺满整个壳；桌面端收窄成 9:16 剧场卡 */
.feed-stage {
    position: relative;
    height: 100%;
    overflow: hidden;
    background: var(--media-bg);
}

/* 桌面端封面氛围层：模糊封面 + 奶白薄纱，移动端不渲染 */
.feed-ambient {
    display: none;
}

.feed-top {
    position: absolute;
    top: 0;
    right: 0;
    left: 0;
    z-index: 20;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: calc(env(safe-area-inset-top) + 10px) 12px 18px;
    background: linear-gradient(180deg, rgba(0, 0, 0, 0.62), transparent);
    pointer-events: none;
}

.feed-back {
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
    pointer-events: auto;
    backdrop-filter: blur(8px);
}

.feed-back svg {
    width: 18px;
    height: 18px;
}

.feed-top-copy {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 1px;
    text-shadow: 0 2px 10px rgba(0, 0, 0, 0.55);
}

.feed-top-copy strong {
    overflow: hidden;
    color: #fff;
    font-size: 14px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.feed-top-copy span {
    color: rgba(255, 255, 255, 0.6);
    font-size: 10px;
    letter-spacing: 0.08em;
}

.feed-scroller {
    position: absolute;
    inset: 0;
    overflow-x: hidden;
    overflow-y: auto;
    scroll-snap-type: y mandatory;
    overscroll-behavior: contain;
    scrollbar-width: none;
}

.feed-scroller::-webkit-scrollbar {
    display: none;
}

.feed-slide {
    position: relative;
    height: 100%;
    scroll-snap-align: start;
    scroll-snap-stop: always;
}

.feed-state-body {
    position: absolute;
    inset: 0;
    display: grid;
    align-content: center;
    justify-items: center;
    gap: 10px;
    padding: 80px 20px;
    color: var(--on-media-muted);
    font-size: 12px;
}

.feed-spinner {
    width: 28px;
    height: 28px;
    border: 2px solid rgba(255, 255, 255, 0.2);
    border-top-color: var(--brand-2-bright);
    border-radius: 50%;
    animation: feed-spin 800ms linear infinite;
}

.feed-state-error {
    position: absolute;
    right: 16px;
    bottom: calc(env(safe-area-inset-bottom) + 16px);
    left: 16px;
    z-index: 21;
    padding: 8px 10px;
    border-radius: 9px;
    background: rgba(0, 0, 0, 0.6);
    color: var(--brand-2-bright);
    font-size: 10px;
    text-align: center;
}

.comment-tabs {
    position: sticky;
    top: 0;
    z-index: 1;
    display: flex;
    gap: 8px;
    padding-bottom: 10px;
    background: var(--panel-strong);
}

.comment-tab {
    height: 30px;
    padding: 0 13px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: transparent;
    color: var(--muted);
    font-size: 12px;
}

.comment-tab.is-active {
    border-color: rgba(184, 58, 38, 0.35);
    background: var(--brand-soft);
    color: var(--brand-ink);
}

/* ---------- 桌面端（≥1024）：宽屏剧场 + 右侧信息栏 ---------- */

@media (min-width: 1024px) {
    .feed-shell {
        display: grid;
        width: calc(100% - var(--sidebar-width));
        max-width: none;
        grid-template-columns: minmax(0, 1fr) var(--feed-workspace-width);
        gap: clamp(16px, 2vw, 32px);
        padding: 20px clamp(16px, 2vw, 32px);
        left: var(--sidebar-width);
        background: transparent;
        transform: none;
    }

    .feed-ambient {
        position: absolute;
        inset: 0;
        display: block;
        overflow: hidden;
        pointer-events: none;
    }

    .feed-ambient-img {
        position: absolute;
        inset: -8%;
        width: 116%;
        height: 116%;
        object-fit: cover;
        filter: blur(48px) saturate(0.85);
        opacity: 0.5;
        transform: scale(1.05);
    }

    .feed-ambient-veil {
        position: absolute;
        inset: 0;
        background:
            radial-gradient(900px 620px at 10% -10%, rgba(47, 174, 122, 0.18), transparent 62%),
            radial-gradient(760px 520px at 96% 4%, rgba(255, 107, 74, 0.14), transparent 58%),
            linear-gradient(180deg, rgba(244, 251, 247, 0.86), rgba(244, 251, 247, 0.93));
    }

    .feed-stage {
        grid-column: 1;
        align-self: center;
        justify-self: center;
        width: min(var(--feed-stage-max), calc((100dvh - 40px) * 9 / 16), 100%);
        height: auto;
        aspect-ratio: 9 / 16;
        max-height: calc(100dvh - 40px);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-media);
        isolation: isolate;
    }

    .feed-workspace {
        grid-column: 2;
        align-self: center;
    }

    /* 桌面端右侧已有选集入口，隐藏视频内的“上滑继续” */
    .feed-stage.is-desktop :deep(.feed-finish-hint) {
        display: none;
    }
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
