<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchCategories, searchDramas } from '../api/drama'
import { recommendDramas, streamRecommendDramas } from '../api/content'
import { usePagedList } from '../composables/usePagedList'
import { aiErrorText, aiFlagLabels } from '../utils/aiFlags'
import AiFeedback from '../components/AiFeedback.vue'
import AiSourceCard from '../components/AiSourceCard.vue'
import DramaCard from '../components/DramaCard.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const route = useRoute()
const router = useRouter()

// 1. 顶部栏搜索会带上 keyword 查询参数，首次进入直接回填
const initialMode = route.query.mode === 'ai' ? 'ai' : 'keyword'
const initialKeyword = typeof route.query.keyword === 'string'
    ? route.query.keyword.trim().slice(0, initialMode === 'ai' ? 200 : 50)
    : ''

const mode = ref(initialMode)
const categories = ref([])
const keywordInput = ref(initialKeyword)
const keyword = ref(initialKeyword)
const activeCategory = ref(null)
const sort = ref('latest')
const aiLoading = ref(false)
const aiResult = ref(null)
const aiError = ref('')
const aiSearched = ref(false)
let aiRequestId = 0
let aiAbortController = null

const aiSources = computed(() => aiResult.value?.sources || [])
const aiFlags = computed(() => aiFlagLabels(aiResult.value?.degradedFlags))
const searchPlaceholder = computed(() => (
    mode.value === 'ai'
        ? '描述你想看的剧情，例如：先婚后爱、女主逆袭'
        : '搜索短剧、演员、剧情'
))
const inputMaxLength = computed(() => (mode.value === 'ai' ? 200 : 50))
const activeCategoryName = computed(() => (
    categories.value.find((item) => item.id === activeCategory.value)?.name || ''
))
const hasActiveFilters = computed(() => activeCategory.value !== null || sort.value !== 'latest')
const aiExamples = ['先婚后爱，甜宠', '女主逆袭的爽剧', '最近很火的悬疑短剧', '古装权谋，节奏快']

const {
    items,
    total,
    loading,
    loadingMore,
    error,
    loadMoreError,
    hasMore,
    refresh,
    loadMore
} = usePagedList(
    ({ pageNo, pageSize }) => searchDramas({
        keyword: keyword.value,
        categoryId: activeCategory.value,
        sort: sort.value,
        pageNo,
        pageSize
    }),
    { pageSize: 12, immediate: false }
)

/**
 * 加载分类；失败时降级为不显示筛选栏，不阻断搜索。
 */
async function loadCategories() {
    try {
        categories.value = await fetchCategories()
    } catch {
        categories.value = []
    }
}

/**
 * 提交搜索：关键词模式走原分页搜索，AI 模式走自然语言找剧。
 */
function submitSearch() {
    const value = keywordInput.value.trim()
    keyword.value = value

    if (mode.value === 'ai') {
        const query = {
            ...(value ? { keyword: value } : {}),
            mode: 'ai'
        }
        const target = router.resolve({ name: 'search', query })
        if (target.fullPath === route.fullPath) {
            runAiSearch()
            return
        }
        router.replace({ name: 'search', query })
        return
    }

    const query = value ? { keyword: value } : {}
    const target = router.resolve({ name: 'search', query })

    // 2. RouterView 以 fullPath 为 key，查询变化会重挂载并自动请求；
    //    查询没变（例如重复搜索同一关键词）时手动刷新即可
    if (target.fullPath === route.fullPath) {
        refresh()
        return
    }
    router.replace({ name: 'search', query })
}

/**
 * 切换关键词/AI 模式，模式写入地址栏，刷新后仍能恢复。
 */
function switchMode(next) {
    if (mode.value === next) {
        return
    }
    if (next !== 'ai') {
        aiAbortController?.abort()
        aiAbortController = null
        aiLoading.value = false
    }
    const value = keywordInput.value.trim()
    keyword.value = value
    mode.value = next
    const query = {
        ...(value ? { keyword: value } : {}),
        ...(next === 'ai' ? { mode: 'ai' } : {})
    }
    router.replace({ name: 'search', query })
}

/**
 * 执行一次 AI 找剧；优先 SSE，首帧前失败时回退普通 JSON。
 */
async function runAiSearch() {
    const query = keyword.value.trim()
    aiAbortController?.abort()
    aiAbortController = null
    if (!query) {
        aiRequestId += 1
        aiLoading.value = false
        aiResult.value = null
        aiError.value = ''
        aiSearched.value = false
        return
    }
    const requestId = ++aiRequestId
    const controller = new AbortController()
    aiAbortController = controller
    aiLoading.value = true
    aiError.value = ''
    aiSearched.value = true

    const draft = reactive({
        requestId: '',
        answer: '',
        rewrittenQuery: '',
        sources: [],
        degraded: false,
        degradedFlags: [],
        elapsedMs: 0
    })
    let streamed = false
    let streamError = null

    /**
     * 增量更新草稿；模板会在首帧后直接展示。
     */
    function onStreamEvent(event, data) {
        if (requestId !== aiRequestId) {
            return
        }
        if (event === 'meta') {
            if (typeof data?.requestId === 'string') {
                draft.requestId = data.requestId
            }
            if (typeof data?.rewrittenQuery === 'string') {
                draft.rewrittenQuery = data.rewrittenQuery
            }
            return
        }
        if (event === 'delta') {
            const chunk = typeof data?.text === 'string' ? data.text : ''
            if (chunk) {
                if (!streamed) {
                    streamed = true
                    aiResult.value = draft
                }
                draft.answer += chunk
            }
            return
        }
        if (event === 'sources') {
            draft.sources = Array.isArray(data?.items) ? data.items : []
            return
        }
        if (event === 'done') {
            draft.degraded = Boolean(data?.degraded)
            draft.degradedFlags = Array.isArray(data?.degradedFlags)
                ? data.degradedFlags
                : []
            draft.elapsedMs = Number(data?.elapsedMs) || 0
            return
        }
        if (event === 'error') {
            streamError = data
        }
    }

    try {
        try {
            await streamRecommendDramas({ query, topK: 3 }, {
                signal: controller.signal,
                onEvent: onStreamEvent
            })
        } catch (cause) {
            // 1. 主动取消（切换模式/离开页面/发起新请求）直接忽略；超时不能被当成取消
            if (requestId !== aiRequestId || cause?.name === 'AbortError'
                || (cause?.code === -2 && !cause?.timedOut)) {
                return
            }
            if (cause?.timedOut) {
                streamError = { code: 'timeout', message: '' }
            } else if (!streamed) {
                streamError = { code: 'unavailable', message: '' }
            } else {
                throw cause
            }
        }
        if (requestId !== aiRequestId) {
            return
        }
        // 1. 首帧前失败或没有内容，回退普通 JSON 接口
        if (!streamed || !draft.answer.trim()) {
            const result = await recommendDramas({ query, topK: 3 })
            if (requestId !== aiRequestId) {
                return
            }
            aiResult.value = result
            return
        }
        // 2. 已有部分回答时保留内容，把超时标记为降级
        if (streamError) {
            draft.degraded = true
            draft.degradedFlags = [...new Set([...draft.degradedFlags, 'timeout'])]
        }
    } catch (cause) {
        if (requestId !== aiRequestId || cause?.name === 'AbortError'
            || (cause?.code === -2 && !cause?.timedOut)) {
            return
        }
        aiResult.value = null
        aiError.value = aiErrorText(cause)
    } finally {
        if (requestId === aiRequestId) {
            aiLoading.value = false
            aiAbortController = null
        }
    }
}

/**
 * 切换分类；再次点击已选分类时取消筛选。
 */
function selectCategory(categoryId) {
    activeCategory.value = activeCategory.value === categoryId ? null : categoryId
    refresh()
}

/**
 * 切换最新/最热排序。
 */
function changeSort(value) {
    if (sort.value === value) {
        return
    }
    sort.value = value
    refresh()
}

/**
 * 清空分类和排序筛选，回到默认结果。
 */
function resetFilters() {
    activeCategory.value = null
    sort.value = 'latest'
    refresh()
}

/**
 * 点击 AI 示例描述：回填输入框并把查询写入地址栏，由路由重挂载发起找剧。
 */
function useAiExample(text) {
    keywordInput.value = text
    keyword.value = text
    const query = { keyword: text, mode: 'ai' }
    const target = router.resolve({ name: 'search', query })
    if (target.fullPath === route.fullPath) {
        runAiSearch()
        return
    }
    router.replace({ name: 'search', query })
}

onMounted(() => {
    loadCategories()
    if (mode.value === 'keyword') {
        refresh()
    } else if (keyword.value) {
        runAiSearch()
    }
})

onBeforeUnmount(() => {
    aiAbortController?.abort()
    aiAbortController = null
})
</script>

<template>
    <div class="sub-page">
        <PageHeader title="发现" back-to="/"/>

        <main class="app-main search-page">
            <section class="discover-hero">
                <div class="hero-copy">
                    <span class="eyebrow">DISCOVER</span>
                    <h1>发现下一部上头短剧</h1>
                    <p>搜剧名、演员，或用一句话描述你想看的剧情。AI 会结合简介和看点给出推荐。</p>
                    <div class="hero-points">
                        <span><strong>关键词</strong> 精准搜索</span>
                        <span><strong>AI</strong> 自然语言找剧</span>
                    </div>
                </div>

                <div class="hero-controls">
                    <div class="search-mode" role="tablist" aria-label="搜索方式">
                        <button
                            class="search-mode-option"
                            :class="{ 'is-active': mode === 'keyword' }"
                            type="button"
                            role="tab"
                            :aria-selected="mode === 'keyword'"
                            @click="switchMode('keyword')"
                        >
                            关键词
                        </button>
                        <button
                            class="search-mode-option"
                            :class="{ 'is-active': mode === 'ai' }"
                            type="button"
                            role="tab"
                            :aria-selected="mode === 'ai'"
                            @click="switchMode('ai')"
                        >
                            AI 找剧
                        </button>
                    </div>

                    <form class="search-bar" role="search" @submit.prevent="submitSearch">
                        <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                             stroke-width="2" stroke-linecap="round" aria-hidden="true">
                            <circle cx="11" cy="11" r="7"/>
                            <path d="M16.5 16.5L21 21"/>
                        </svg>
                        <input
                            v-model="keywordInput"
                            type="search"
                            :maxlength="inputMaxLength"
                            :placeholder="searchPlaceholder"
                            :aria-label="mode === 'ai' ? '描述想看的剧情' : '搜索关键词'"
                        />
                        <button class="btn btn-primary btn-small" type="submit">
                            {{ mode === 'ai' ? '找剧' : '搜索' }}
                        </button>
                    </form>
                </div>
            </section>

            <section v-if="mode === 'ai'" class="search-results ai-results">
                <div v-if="aiLoading && !aiResult" class="ai-loading panel">
                    <div class="ai-loading-head">
                        <span class="ai-badge">AI 找剧</span>
                        <span>正在理解你的描述…</span>
                    </div>
                    <div class="ai-progress">
                        <span>理解描述</span>
                        <span>检索短剧</span>
                        <span>整理推荐</span>
                    </div>
                    <SkeletonBlock height="92px" radius="12px"/>
                </div>

                <ErrorState v-else-if="aiError" :message="aiError" @retry="runAiSearch"/>

                <div v-else-if="!aiSearched" class="ai-empty">
                    <span class="ai-empty-icon" aria-hidden="true">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
                             stroke-linecap="round" stroke-linejoin="round">
                            <path d="M12 3.5l1.7 4.3 4.3 1.7-4.3 1.7-1.7 4.3-1.7-4.3L6 9.5l4.3-1.7z"/>
                            <path d="M18.5 15l.9 2.1 2.1.9-2.1.9-.9 2.1-.9-2.1-2.1-.9 2.1-.9z"/>
                        </svg>
                    </span>
                    <h2>描述你想看的剧情</h2>
                    <p>AI 会先从关键词和剧情简介里找候选，再整理成推荐理由。试试这些说法：</p>
                    <div class="ai-examples">
                        <button
                            v-for="example in aiExamples"
                            :key="example"
                            class="chip"
                            type="button"
                            @click="useAiExample(example)"
                        >
                            {{ example }}
                        </button>
                    </div>
                </div>

                <template v-else-if="aiResult">
                    <article class="ai-answer-card">
                        <div class="ai-answer-head">
                            <span class="ai-badge">AI 找剧</span>
                            <span v-if="aiLoading" class="ai-streaming">生成中…</span>
                            <span v-if="aiResult.rewrittenQuery" class="ai-rewrite">
                                已理解：{{ aiResult.rewrittenQuery }}
                            </span>
                        </div>
                        <p class="ai-answer">
                            {{ aiResult.answer || '正在组织回答…' }}<span
                                v-if="aiLoading && aiResult.answer"
                                class="ai-caret"
                                aria-hidden="true"
                            ></span>
                        </p>
                        <p v-if="aiResult.degraded" class="ai-degraded">
                            推荐依据不完整：{{ aiFlags.join('、') || '部分资料不可用' }}
                        </p>
                        <div class="ai-actions">
                            <button class="btn btn-ghost btn-small" type="button" @click="runAiSearch">
                                重新找一次
                            </button>
                            <button class="btn btn-ghost btn-small" type="button" @click="switchMode('keyword')">
                                改用关键词搜索
                            </button>
                            <AiFeedback
                                scene="RAG_SEARCH"
                                :request-id="aiResult.requestId"
                            />
                        </div>
                    </article>

                    <div v-if="aiSources.length" class="ai-source-grid">
                        <AiSourceCard
                            v-for="source in aiSources"
                            :key="source.dramaId"
                            :source="source"
                        />
                    </div>
                    <EmptyState
                        v-else
                        title="AI 没找到匹配的短剧"
                        description="换个说法试试，或者用关键词搜索。"
                        action-text="改用关键词搜索"
                        @action="switchMode('keyword')"
                    />
                </template>
            </section>

            <div v-if="mode === 'keyword'" class="search-body">
                <aside v-if="categories.length" class="search-filters" aria-label="分类筛选">
                    <span class="filter-label">分类</span>

                    <div class="chip-row">
                        <button
                            class="chip"
                            :class="{ 'is-active': activeCategory === null }"
                            type="button"
                            @click="selectCategory(null)"
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

                    <div class="filter-side">
                        <button
                            v-if="hasActiveFilters"
                            class="filter-reset"
                            type="button"
                            @click="resetFilters"
                        >
                            重置
                        </button>
                        <div class="sort-switch" role="group" aria-label="排序方式">
                            <button
                                class="sort-option"
                                :class="{ 'is-active': sort === 'latest' }"
                                type="button"
                                @click="changeSort('latest')"
                            >
                                最新
                            </button>
                            <button
                                class="sort-option"
                                :class="{ 'is-active': sort === 'hot' }"
                                type="button"
                                @click="changeSort('hot')"
                            >
                                最热
                            </button>
                        </div>
                    </div>
                </aside>

                <section class="search-results">
                    <div class="results-head">
                        <div class="results-copy">
                            <h2>{{ keyword ? `“${keyword}”` : '全部短剧' }}</h2>
                            <p>
                                {{ loading ? '搜索中…' : `共 ${total} 部` }}
                                <template v-if="activeCategoryName"> · {{ activeCategoryName }}</template>
                            </p>
                        </div>
                        <div class="sort-switch toolbar-sort" role="group" aria-label="排序方式">
                            <button
                                class="sort-option"
                                :class="{ 'is-active': sort === 'latest' }"
                                type="button"
                                @click="changeSort('latest')"
                            >
                                最新
                            </button>
                            <button
                                class="sort-option"
                                :class="{ 'is-active': sort === 'hot' }"
                                type="button"
                                @click="changeSort('hot')"
                            >
                                最热
                            </button>
                        </div>
                    </div>

                    <div v-if="loading" class="result-grid">
                        <SkeletonBlock v-for="index in 6" :key="index" height="216px" radius="14px"/>
                    </div>

                    <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

                    <EmptyState
                        v-else-if="!items.length"
                        title="没有找到相关短剧"
                        description="换个关键词或分类试试，也可以回到首页看看热播。"
                        action-text="回首页"
                        @action="router.push('/')"
                    />

                    <template v-else>
                        <div class="result-grid">
                            <DramaCard v-for="item in items" :key="item.dramaId" :drama="item"/>
                        </div>

                        <LoadMore
                            :loading="loadingMore"
                            :has-more="hasMore"
                            :error="loadMoreError"
                            :total="total"
                            @load="loadMore"
                        />
                    </template>
                </section>
            </div>
        </main>
    </div>
</template>

<style scoped>
.search-page {
    gap: 18px;
}

/* ---------- 发现页头部 ---------- */

.discover-hero {
    display: grid;
    min-width: 0;
    gap: 16px;
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: 20px;
    background:
        radial-gradient(420px 240px at 0% 0%, rgba(47, 174, 122, 0.16), transparent 70%),
        radial-gradient(360px 220px at 100% 0%, rgba(255, 107, 74, 0.12), transparent 72%),
        linear-gradient(135deg, var(--surface), #fbfdfb 60%, #fff8f4);
    box-shadow: var(--shadow-soft);
}

.hero-copy {
    display: none;
}

.hero-copy h1 {
    font-size: 27px;
    font-weight: 900;
    letter-spacing: -0.02em;
    line-height: 1.2;
}

.hero-copy p {
    max-width: 44ch;
    color: var(--muted);
    font-size: 13px;
    line-height: 1.7;
}

.hero-points {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 18px;
    color: var(--muted);
    font-size: 12px;
}

.hero-points strong {
    color: var(--text);
    font-weight: 800;
}

.hero-controls {
    display: grid;
    min-width: 0;
    gap: 10px;
}

.search-bar {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 10px;
    padding: 7px 7px 7px 14px;
    border: 1px solid var(--line-strong);
    border-radius: 14px;
    background: var(--surface);
    transition: border-color 150ms ease, box-shadow 150ms ease;
}

.search-bar:focus-within {
    border-color: rgba(184, 58, 38, 0.45);
    box-shadow: 0 0 0 3px rgba(184, 58, 38, 0.1);
}

.search-icon {
    flex: none;
    width: 18px;
    height: 18px;
    color: var(--brand-ink);
}

.search-bar input {
    min-width: 0;
    flex: 1;
    height: 38px;
    padding: 0;
    border: none;
    background: transparent;
    color: var(--text);
    font-size: 15px;
}

.search-bar input::placeholder {
    color: var(--muted-soft);
}

.search-bar input:focus {
    outline: none;
}

.search-bar .btn {
    flex: none;
    height: 38px;
}

.search-body {
    display: grid;
    min-width: 0;
    gap: 16px;
}

.search-filters {
    min-width: 0;
}

.search-results {
    display: grid;
    min-width: 0;
    gap: 16px;
}

/* 移动端：分类横向滚动，排序放在结果头部 */
.filter-label,
.filter-side {
    display: none;
}

.results-head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 12px;
}

.results-copy h2 {
    overflow: hidden;
    font-size: 18px;
    font-weight: 800;
    letter-spacing: -0.01em;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.results-copy p {
    margin-top: 3px;
    color: var(--muted);
    font-size: 11px;
}

.chip-row {
    display: flex;
    gap: 8px;
    margin: 0 -16px;
    padding: 0 16px 2px;
    overflow-x: auto;
    scrollbar-width: none;
}

.chip-row::-webkit-scrollbar {
    display: none;
}

.sort-switch {
    display: inline-flex;
    flex: none;
    gap: 2px;
    padding: 3px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: var(--surface-muted);
}

.sort-option {
    height: 28px;
    padding: 0 12px;
    border: none;
    border-radius: 999px;
    background: transparent;
    color: var(--muted);
    font-size: 12px;
    transition: background 150ms ease, color 150ms ease;
}

.sort-option.is-active {
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-weight: 700;
}

.result-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px 12px;
}

.result-grid :deep(.drama-card) {
    width: 100%;
}

/* ---------- 桌面端（≥1024）：左侧筛选栏 + 结果网格 ---------- */

@media (min-width: 1024px) {
    .discover-hero {
        grid-template-columns: minmax(0, 1fr) minmax(420px, 560px);
        align-items: center;
        gap: 32px;
        padding: 26px 28px;
        border-radius: 22px;
    }

    .hero-copy {
        display: grid;
        gap: 8px;
    }

    .hero-points {
        margin-top: 4px;
    }

    .hero-controls {
        justify-self: end;
        width: 100%;
    }

    .search-mode {
        justify-self: end;
    }

    .search-body {
        gap: 16px;
    }

    /* 筛选改为横向工具条，吸顶在顶部栏下方 */
    .search-filters {
        position: sticky;
        top: calc(var(--topbar-height) + 12px);
        z-index: 20;
        display: flex;
        align-items: center;
        gap: 14px;
        padding: 10px 14px;
        border: 1px solid var(--line);
        border-radius: 16px;
        background: rgba(255, 255, 255, 0.86);
        box-shadow: var(--shadow-soft);
        backdrop-filter: blur(18px);
    }

    .filter-label {
        display: inline;
        flex: none;
        color: var(--muted-soft);
        font-size: 10px;
        font-weight: 700;
        letter-spacing: 0.14em;
        text-transform: uppercase;
    }

    .filter-side {
        display: flex;
        flex: none;
        align-items: center;
        gap: 10px;
        margin-left: auto;
    }

    .filter-reset {
        padding: 0 2px;
        border: none;
        background: none;
        color: var(--brand-ink);
        font-size: 11px;
        text-decoration: underline;
        text-underline-offset: 2px;
    }

    .chip-row {
        flex: 1;
        min-width: 0;
        flex-wrap: nowrap;
        margin: 0;
        padding: 2px 2px;
        overflow-x: auto;
    }

    .chip {
        height: 30px;
    }

    .toolbar-sort {
        display: none;
    }

    .results-head {
        align-items: center;
    }

    .results-copy h2 {
        font-size: 20px;
    }

    .result-grid {
        grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
        gap: 20px 16px;
    }
}

/* ---------- AI 找剧 ---------- */

.search-mode {
    display: inline-flex;
    justify-self: start;
    gap: 3px;
    padding: 3px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: var(--surface-muted);
}

.search-mode-option {
    height: 32px;
    padding: 0 15px;
    border: none;
    border-radius: 999px;
    background: transparent;
    color: var(--muted);
    font-size: 12px;
    font-weight: 600;
}

.search-mode-option.is-active {
    background: linear-gradient(135deg, var(--brand), var(--brand-2-bright));
    color: #241009;
    font-weight: 800;
    box-shadow: 0 6px 16px rgba(255, 107, 74, 0.22);
}

.ai-results {
    display: grid;
    gap: 16px;
    justify-self: center;
    width: min(100%, 960px);
}

.ai-empty {
    display: grid;
    justify-items: start;
    gap: 8px;
    padding: 26px 22px;
    border: 1px dashed var(--line-strong);
    border-radius: 18px;
    background: linear-gradient(145deg, var(--warning-soft), var(--surface-soft));
}

.ai-empty-icon {
    display: grid;
    place-items: center;
    width: 42px;
    height: 42px;
    border-radius: 13px;
    background: var(--surface);
    color: var(--brand-ink);
    box-shadow: var(--shadow-soft);
}

.ai-empty-icon svg {
    width: 22px;
    height: 22px;
}

.ai-empty h2 {
    font-size: 18px;
    font-weight: 800;
}

.ai-empty p {
    max-width: 52ch;
    color: var(--muted);
    font-size: 12px;
    line-height: 1.7;
}

.ai-examples {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 4px;
}

.ai-examples .chip:hover {
    border-color: rgba(184, 58, 38, 0.35);
    color: var(--brand-ink);
}

.ai-answer-card {
    padding: 20px;
    border: 1px solid #f0ddb4;
    border-radius: 18px;
    background: linear-gradient(145deg, var(--warning-soft), var(--surface-soft));
    box-shadow: var(--shadow-soft);
}

.ai-answer-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
}

.ai-badge {
    padding: 3px 8px;
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.04em;
}

.ai-rewrite {
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.ai-streaming {
    color: var(--brand-2);
    font-size: 10.5px;
}

.ai-caret {
    display: inline-block;
    width: 2px;
    height: 1em;
    margin-left: 2px;
    background: var(--brand-2);
    vertical-align: -0.12em;
    animation: ai-caret-blink 900ms steps(2, start) infinite;
}

@keyframes ai-caret-blink {
    to {
        visibility: hidden;
    }
}

@media (prefers-reduced-motion: reduce) {
    .ai-caret {
        animation: none;
    }
}

.ai-answer {
    margin: 12px 0 0;
    color: var(--text);
    font-size: 14px;
    line-height: 1.75;
    white-space: pre-wrap;
}

.ai-degraded {
    margin: 10px 0 0;
    padding: 8px 10px;
    border-radius: 8px;
    background: var(--warning-soft);
    color: var(--warning-ink);
    font-size: 11px;
    line-height: 1.55;
}

.ai-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
}

.ai-source-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, 200px);
    gap: 16px 14px;
}

.ai-loading {
    display: grid;
    gap: 12px;
    padding: 20px;
    border: 1px solid var(--line);
    border-radius: 18px;
    background: var(--surface);
    box-shadow: var(--shadow-soft);
}

.ai-loading-head {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
    font-size: 12px;
}

.ai-progress {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}

.ai-progress span {
    padding: 4px 8px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--muted);
    font-size: 10px;
}

@media (max-width: 640px) {
    .ai-source-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
    }
}
</style>
