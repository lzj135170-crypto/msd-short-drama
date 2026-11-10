<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { evictPersonalized, fetchPersonalized, saveColdStartPreferences } from '../api/content'
import { fetchHome } from '../api/drama'
import { fetchContinueWatching, trackRecommendationEvent } from '../api/interaction'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'
import { formatMs } from '../utils/format'
import BannerCarousel from '../components/BannerCarousel.vue'
import DramaCard from '../components/DramaCard.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import GradientCover from '../components/GradientCover.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import UserAvatar from '../components/UserAvatar.vue'

const router = useRouter()
const auth = useAuthStore()
const toast = useToastStore()

const home = ref(null)
const loading = ref(true)
const error = ref('')
const activeCategory = ref(null)
const continueItem = ref(null)
const personalizedFeed = ref(null)
const personalizedLoading = ref(true)
const personalizedFailed = ref(false)
const coldStartSaving = ref(false)
const dislikePending = ref(0)

const categories = computed(() => home.value?.categories || [])
const sections = computed(() => home.value?.sections || [])

/**
 * 推荐接口失败时用首页热播兜底，避免区块加载后突然消失。
 */
const personalizedDisplay = computed(() => {
    if (personalizedFeed.value) {
        return personalizedFeed.value
    }
    if (!personalizedFailed.value) {
        return null
    }
    const fallback = sections.value.find((section) => section.items?.length)
    if (!fallback) {
        return null
    }
    return {
        items: fallback.items.slice(0, 6).map((item) => ({ ...item, reason: '热门推荐' })),
        personalized: false,
        coldStart: false,
        degraded: true,
        requestId: ''
    }
})

/**
 * 分类只在首页已加载的推荐里做本地筛选，后端没有分类列表接口。
 */
const visibleSections = computed(() => {
    if (!activeCategory.value) {
        return sections.value.filter((section) => section.items?.length)
    }
    return sections.value
        .map((section) => ({
            ...section,
            items: (section.items || []).filter((item) => item.categoryId === activeCategory.value)
        }))
        .filter((section) => section.items.length)
})

const greeting = computed(() => {
    const hour = new Date().getHours()
    if (hour < 6) {
        return '夜深了'
    }
    if (hour < 12) {
        return '早上好'
    }
    if (hour < 18) {
        return '下午好'
    }
    return '晚上好'
})

/**
 * 加载首页聚合数据。
 */
async function load() {
    loading.value = true
    error.value = ''
    try {
        home.value = await fetchHome()
    } catch (cause) {
        error.value = cause?.message || '首页加载失败'
    } finally {
        loading.value = false
    }
}

/**
 * 再次点击已选分类时取消筛选。
 */
function selectCategory(categoryId) {
    activeCategory.value = activeCategory.value === categoryId ? null : categoryId
}

/**
 * 构造推荐归因上下文；降级兜底卡片没有 requestId，不上报。
 */
function recommendationContext(index) {
    const feed = personalizedDisplay.value
    if (!feed?.requestId) {
        return null
    }
    return {
        requestId: feed.requestId,
        position: index + 1
    }
}

/**
 * 上报推荐点击；失败静默，不影响跳转。
 */
function trackClick(item, index) {
    const context = recommendationContext(index)
    if (!context) {
        return
    }
    trackRecommendationEvent({
        requestId: context.requestId,
        dramaId: item.dramaId,
        eventType: 1,
        position: context.position
    }).catch(() => {
        // 归因失败不影响用户操作
    })
}

/**
 * 标记不感兴趣并立即从当前列表移除。
 */
async function dislike(item, index) {
    const context = recommendationContext(index)
    if (!context || dislikePending.value) {
        return
    }
    dislikePending.value = item.dramaId
    try {
        await trackRecommendationEvent({
            requestId: context.requestId,
            dramaId: item.dramaId,
            eventType: 4,
            position: context.position
        })
        if (personalizedFeed.value) {
            const items = personalizedFeed.value.items.filter(
                (card) => card.dramaId !== item.dramaId
            )
            personalizedFeed.value = items.length
                ? { ...personalizedFeed.value, items }
                : null
        }
        // 清理服务端缓存，避免下次加载又出现已负反馈的短剧
        evictPersonalized().catch(() => {
            // 缓存清理失败只影响下次刷新时机
        })
        toast.success('已减少这类推荐')
    } catch {
        toast.push('操作失败，请稍后重试')
    } finally {
        dislikePending.value = 0
    }
}

/**
 * 保存冷启动分类偏好并刷新推荐。
 */
async function selectColdCategory(categoryId) {
    if (coldStartSaving.value) {
        return
    }
    coldStartSaving.value = true
    try {
        await saveColdStartPreferences([categoryId])
        await loadPersonalized()
        toast.success('已记住你的偏好')
    } catch {
        toast.push('偏好保存失败，请稍后重试')
    } finally {
        coldStartSaving.value = false
    }
}

/**
 * 加载首页“继续观看”，失败时静默隐藏。
 */
async function loadContinue() {
    try {
        const list = await fetchContinueWatching(1)
        continueItem.value = Array.isArray(list) ? list[0] || null : null
    } catch {
        continueItem.value = null
    }
}

/**
 * 加载首页个性化推荐；冷启动和降级时也保留热播卡片与说明。
 */
async function loadPersonalized() {
    personalizedLoading.value = true
    personalizedFailed.value = false
    try {
        const feed = await fetchPersonalized(6)
        personalizedFeed.value = feed?.items?.length ? feed : null
        personalizedFailed.value = !personalizedFeed.value
    } catch {
        personalizedFeed.value = null
        personalizedFailed.value = true
    } finally {
        personalizedLoading.value = false
    }
}

/**
 * 从最近观看位置继续播放。
 */
function resume(item) {
    if (!item) {
        return
    }
    router.push({
        name: 'drama-detail',
        params: { dramaId: item.dramaId },
        query: {
            episode: item.episodeId,
            start: Number(item.positionMs) || 0
        }
    })
}

onMounted(() => {
    load()
    loadContinue()
    loadPersonalized()
})
</script>

<template>
    <div class="app-main home-main">
        <header class="home-header">
            <div class="home-greeting">
                <span class="eyebrow">MSD SHORT DRAMA</span>
                <h1>{{ greeting }}，{{ auth.displayName }}</h1>
            </div>
            <RouterLink class="home-avatar" to="/me" aria-label="进入个人中心">
                <UserAvatar :name="auth.displayName" :src="auth.user?.avatarUrl" :size="38"/>
            </RouterLink>
        </header>

        <RouterLink class="search-entry" to="/search" aria-label="搜索短剧">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" aria-hidden="true">
                <circle cx="11" cy="11" r="7"/>
                <path d="M16.5 16.5L21 21"/>
            </svg>
            <span>搜索短剧，或让 AI 帮你找</span>
        </RouterLink>

        <template v-if="loading">
            <div class="home-hero">
                <SkeletonBlock height="196px" radius="20px"/>
            </div>
            <div class="skeleton-chips">
                <SkeletonBlock width="58px" height="32px" radius="999px"/>
                <SkeletonBlock width="70px" height="32px" radius="999px"/>
                <SkeletonBlock width="64px" height="32px" radius="999px"/>
            </div>
            <div class="skeleton-shelf">
                <SkeletonBlock v-for="index in 3" :key="index" width="136px" height="181px" radius="14px"/>
            </div>
        </template>

        <ErrorState
            v-else-if="error"
            :message="error"
            @retry="load"
        />

        <template v-else-if="home">
            <div class="home-hero" :class="{ 'has-continue': continueItem }">
                <BannerCarousel :banners="home.banners"/>

                <button v-if="continueItem" class="continue-card" type="button" @click="resume(continueItem)">
                    <GradientCover
                        class="continue-cover"
                        :seed="continueItem.dramaId"
                        :title="continueItem.dramaTitle"
                        :src="continueItem.dramaCoverUrl"
                        ratio="poster"
                    />
                    <span class="continue-copy">
                        <span class="eyebrow">继续观看</span>
                        <strong>{{ continueItem.dramaTitle || '短剧' }}</strong>
                        <span class="continue-episode">
                            第{{ continueItem.episodeNo || '?' }}集 · {{ continueItem.episodeTitle || '' }}
                        </span>
                        <span class="continue-progress">
                            看到 {{ formatMs(continueItem.positionMs) }}
                        </span>
                    </span>
                    <span class="continue-arrow" aria-hidden="true">›</span>
                </button>
            </div>

            <section
                v-if="personalizedLoading && !activeCategory"
                class="home-section personalized-section"
                aria-label="正在加载为你推荐"
            >
                <div class="section-title">
                    <h2>为你推荐</h2>
                    <span>正在准备</span>
                </div>
                <div class="shelf">
                    <SkeletonBlock
                        v-for="index in 3"
                        :key="index"
                        width="136px"
                        height="181px"
                        radius="14px"
                    />
                </div>
            </section>

            <section
                v-else-if="personalizedDisplay && !activeCategory"
                class="home-section personalized-section"
            >
                <div class="section-title">
                    <h2>为你推荐</h2>
                    <span>{{ personalizedDisplay.items.length }} 部</span>
                </div>
                <p v-if="personalizedDisplay.degraded" class="personalized-note">
                    推荐依据暂时不可用，先按热播展示。
                </p>
                <p v-else-if="personalizedDisplay.coldStart" class="personalized-note">
                    多看几部，推荐会更懂你。
                </p>
                <div
                    v-if="personalizedDisplay.coldStart && categories.length"
                    class="personalized-chips"
                >
                    <button
                        v-for="category in categories.slice(0, 5)"
                        :key="category.id"
                        class="chip"
                        type="button"
                        :disabled="coldStartSaving"
                        @click="selectColdCategory(category.id)"
                    >
                        {{ category.name }}
                    </button>
                </div>
                <div class="shelf">
                    <div
                        v-for="(item, index) in personalizedDisplay.items"
                        :key="item.dramaId"
                        class="recommend-card-wrap"
                    >
                        <DramaCard
                            :drama="item"
                            :reason="item.reason"
                            :recommendation="recommendationContext(index)"
                            @click="trackClick(item, index)"
                        />
                        <button
                            class="dislike-btn"
                            type="button"
                            :disabled="dislikePending === item.dramaId"
                            @click.stop="dislike(item, index)"
                        >
                            不感兴趣
                        </button>
                    </div>
                </div>
            </section>

            <div v-if="categories.length" class="category-strip" aria-label="分类筛选">
                <button
                    class="chip"
                    :class="{ 'is-active': activeCategory === null }"
                    type="button"
                    @click="activeCategory = null"
                >
                    全部
                </button>
                <button
                    v-for="category in categories"
                    :key="category.id"
                    class="chip"
                    :class="{ 'is-active': activeCategory === category.id }"
                    type="button"
                    @click="selectCategory(category.id)"
                >
                    {{ category.name }}
                </button>
            </div>

            <section v-for="section in visibleSections" :key="section.key" class="home-section">
                <div class="section-title">
                    <h2>{{ section.title }}</h2>
                    <span>{{ section.items.length }} 部</span>
                </div>
                <div class="shelf">
                    <DramaCard v-for="item in section.items" :key="item.dramaId" :drama="item"/>
                </div>
            </section>

            <EmptyState
                v-if="activeCategory !== null && !visibleSections.length"
                title="当前推荐中没有该分类"
                description="首页只展示热播和最新两批内容，换个分类试试。"
            />

            <p class="home-foot">码上短剧 · 本地演示环境</p>
        </template>
    </div>
</template>

<style scoped>
.home-main {
    padding-top: calc(env(safe-area-inset-top) + 16px);
}

.home-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
}

.home-greeting {
    display: grid;
    gap: 5px;
    min-width: 0;
}

.home-greeting h1 {
    overflow: hidden;
    font-size: 20px;
    font-weight: 800;
    letter-spacing: -0.01em;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.home-avatar {
    flex: none;
    border-radius: 50%;
    box-shadow: 0 0 0 2px rgba(255, 107, 74, 0.28);
}

.search-entry {
    display: flex;
    align-items: center;
    gap: 9px;
    height: 42px;
    padding: 0 14px;
    border: 1px solid var(--line);
    border-radius: 13px;
    background: var(--surface);
    box-shadow: var(--shadow-soft);
    color: var(--muted);
    font-size: 12px;
    transition: border-color 150ms ease, background 150ms ease;
}

.search-entry:hover {
    border-color: rgba(184, 58, 38, 0.3);
}

.search-entry:active {
    background: var(--surface-muted);
}

.search-entry svg {
    flex: none;
    width: 16px;
    height: 16px;
    color: var(--brand-ink);
}

.continue-card {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    padding: 10px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background:
        radial-gradient(220px 100px at 0% 0%, rgba(47, 174, 122, 0.14), transparent 72%),
        linear-gradient(135deg, var(--surface), #fff6f1);
    box-shadow: var(--shadow-soft);
    text-align: left;
}

.continue-cover {
    flex: none;
    width: 58px;
}

.continue-copy {
    display: grid;
    min-width: 0;
    flex: 1;
    gap: 3px;
}

.continue-copy strong {
    overflow: hidden;
    color: var(--text);
    font-size: 14px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.continue-episode {
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.continue-progress {
    color: var(--brand-2);
    font-size: 10px;
}

.continue-arrow {
    flex: none;
    color: var(--muted);
    font-size: 22px;
}

.category-strip {
    display: flex;
    gap: 8px;
    margin: 0 -16px;
    padding: 0 16px 2px;
    overflow-x: auto;
    scrollbar-width: none;
}

.category-strip::-webkit-scrollbar {
    display: none;
}

.home-section {
    display: grid;
    gap: 10px;
}

.personalized-note {
    margin: -2px 0 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.personalized-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

.shelf {
    display: flex;
    gap: 12px;
    margin: 0 -16px;
    padding: 0 16px 4px;
    overflow-x: auto;
    scroll-snap-type: x proximity;
    scrollbar-width: none;
}

.shelf::-webkit-scrollbar {
    display: none;
}

.shelf :deep(.drama-card) {
    scroll-snap-align: start;
}

.recommend-card-wrap {
    position: relative;
    flex: none;
    width: 136px;
}

.dislike-btn {
    position: absolute;
    top: 6px;
    right: 6px;
    z-index: 2;
    padding: 3px 6px;
    border: none;
    border-radius: 6px;
    background: rgba(0, 0, 0, 0.55);
    color: rgba(255, 255, 255, 0.72);
    font-size: 9px;
    line-height: 1.3;
    opacity: 0;
    backdrop-filter: blur(6px);
    transition: opacity 140ms ease;
}

.recommend-card-wrap:hover .dislike-btn,
.dislike-btn:focus-visible {
    opacity: 1;
}

.dislike-btn:disabled {
    opacity: 0.5;
}

@media (hover: none) {
    .dislike-btn {
        opacity: 0.82;
    }
}

.skeleton-chips {
    display: flex;
    gap: 8px;
}

.skeleton-shelf {
    display: flex;
    gap: 12px;
    overflow: hidden;
}

.home-foot {
    padding: 14px 0 8px;
    color: var(--muted-soft);
    font-size: 11px;
    letter-spacing: 0.08em;
    text-align: center;
}

/* ---------- 桌面端（≥1024） ---------- */

@media (min-width: 1024px) {
    .home-main {
        padding-top: 24px;
    }

    /* 搜索和头像已在顶部栏，首页不再重复 */
    .search-entry,
    .home-avatar {
        display: none;
    }

    .home-hero {
        display: grid;
        gap: 16px;
    }

    .home-hero.has-continue {
        grid-template-columns: minmax(0, 1.9fr) minmax(300px, 0.9fr);
    }

    /* 有继续观看时两列等高，Banner 拉伸填满行高 */
    .home-hero.has-continue :deep(.banner),
    .home-hero.has-continue .continue-card {
        height: 100%;
    }

    .home-hero.has-continue :deep(.banner-slide),
    .home-hero.has-continue :deep(.banner-slide .cover) {
        height: 100%;
        aspect-ratio: auto;
    }

    .home-hero.has-continue .continue-card {
        flex-direction: column;
        align-items: stretch;
        justify-content: center;
        gap: 14px;
        padding: 22px;
    }

    .home-hero.has-continue .continue-card .continue-cover {
        width: 100%;
        aspect-ratio: 16 / 9;
    }

    .home-hero.has-continue .continue-card :deep(.cover-image) {
        object-position: center 24%;
    }

    .home-hero.has-continue .continue-copy strong {
        font-size: 17px;
    }

    .home-hero.has-continue .continue-arrow {
        display: none;
    }

    .category-strip {
        flex-wrap: wrap;
        margin: 0;
        padding: 0;
        overflow: visible;
    }

    .home-section {
        gap: 14px;
    }

    .shelf {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
        gap: 18px 16px;
        margin: 0;
        padding: 0;
        overflow: visible;
        scroll-snap-type: none;
    }

    .shelf :deep(.drama-card) {
        width: 100%;
    }

    .skeleton-shelf {
        overflow: visible;
    }
}
</style>
