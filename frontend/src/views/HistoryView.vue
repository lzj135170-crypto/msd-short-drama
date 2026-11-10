<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
    clearHistory,
    deleteHistoryEntry,
    fetchHistory
} from '../api/interaction'
import { useToastStore } from '../stores/toast'
import { usePagedList } from '../composables/usePagedList'
import { formatMs, formatRelative, progressPercent } from '../utils/format'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import GradientCover from '../components/GradientCover.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const router = useRouter()
const toast = useToastStore()

const {
    items,
    total,
    loading,
    loadingMore,
    error,
    loadMoreError,
    hasMore,
    refresh,
    loadMore,
    remove
} = usePagedList(
    ({ pageNo, pageSize }) => fetchHistory({ pageNo, pageSize }),
    { keyOf: (item) => item.episodeId }
)

const confirmMode = ref('')
const confirmTarget = ref(null)
const confirming = ref(false)

const confirmVisible = computed({
    get: () => Boolean(confirmMode.value),
    set: (value) => {
        if (!value) {
            confirmMode.value = ''
            confirmTarget.value = null
        }
    }
})

const confirmTitle = computed(() => (
    confirmMode.value === 'clear' ? '清空观看历史' : '删除观看记录'
))

const confirmMessage = computed(() => (
    confirmMode.value === 'clear'
        ? `将删除全部 ${total.value} 条观看记录，且无法恢复。`
        : '删除后无法恢复，确定删除这条观看记录？'
))

/**
 * 未看完从上次位置继续；已看完从头播放。
 */
function resume(item) {
    if (!item.dramaTitle || !item.episodeTitle) {
        toast.push('内容暂不可见')
        return
    }
    const query = { episode: item.episodeId }
    if (!item.finished && Number(item.positionMs) > 0) {
        query.start = item.positionMs
    }
    router.push({
        path: `/drama/${item.dramaId}`,
        query
    })
}

function askDelete(item) {
    confirmTarget.value = item
    confirmMode.value = 'single'
}

function askClear() {
    if (!items.value.length) {
        return
    }
    confirmTarget.value = null
    confirmMode.value = 'clear'
}

async function confirmAction() {
    if (confirming.value) {
        return
    }
    confirming.value = true
    try {
        if (confirmMode.value === 'clear') {
            const removed = await clearHistory()
            await refresh()
            toast.success(`已清空 ${removed} 条观看记录`)
        } else if (confirmTarget.value) {
            await deleteHistoryEntry(confirmTarget.value.episodeId)
            remove(confirmTarget.value.episodeId)
            toast.success('已删除观看记录')
        }
        confirmVisible.value = false
    } catch (cause) {
        toast.push(cause?.message || '操作失败')
    } finally {
        confirming.value = false
    }
}
</script>

<template>
    <div class="tab-page">
        <PageHeader title="追剧" :show-back="false">
            <template #right>
                <button
                    class="header-action"
                    type="button"
                    :disabled="!items.length"
                    @click="askClear"
                >
                    清空
                </button>
            </template>
        </PageHeader>

        <main class="app-main">
            <div v-if="loading" class="list-skeleton">
                <SkeletonBlock v-for="index in 4" :key="index" height="104px" radius="14px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <template v-else>
                <EmptyState
                    v-if="!items.length"
                    title="还没有观看记录"
                    description="播放任意一集后，进度会自动记录在这里。"
                    action-text="去首页选剧"
                    @action="router.push('/')"
                />

                <div v-else class="history-list">
                    <article v-for="item in items" :key="item.episodeId" class="history-item">
                        <button
                            class="history-main"
                            type="button"
                            :disabled="!item.dramaTitle"
                            @click="resume(item)"
                        >
                            <span class="history-cover">
                                <GradientCover :seed="item.dramaId" :title="item.dramaTitle"
                                               :src="item.dramaCoverUrl" ratio="thumb"/>
                                <span class="play-mark" aria-hidden="true">
                                    <svg viewBox="0 0 24 24" fill="currentColor">
                                        <path d="M8 5.5v13l11-6.5z"/>
                                    </svg>
                                </span>
                            </span>

                            <span class="history-copy">
                                <span class="history-head">
                                    <strong>{{ item.dramaTitle || '内容暂不可见' }}</strong>
                                    <span v-if="item.finished" class="finished">已看完</span>
                                    <span v-else class="percent">
                                        {{ progressPercent(item.positionMs, item.durationMs) }}%
                                    </span>
                                </span>
                                <span class="episode">
                                    {{ item.episodeTitle || `第 ${item.episodeNo} 集` }}
                                </span>
                                <span class="progress">
                                    <i :style="{ width: `${progressPercent(item.positionMs, item.durationMs)}%` }"></i>
                                </span>
                                <span class="meta">
                                    {{ formatRelative(item.lastWatchTime) }}
                                    · {{ formatMs(item.positionMs) }} / {{ formatMs(item.durationMs) }}
                                </span>
                            </span>
                        </button>

                        <button
                            class="remove-btn"
                            type="button"
                            aria-label="删除这条观看记录"
                            @click="askDelete(item)"
                        >
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
                                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                <path d="M4 7h16M9 7V5h6v2M7 7l1 13h8l1-13M10 11v5M14 11v5"/>
                            </svg>
                        </button>
                    </article>
                </div>

                <LoadMore
                    v-if="items.length"
                    :loading="loadingMore"
                    :has-more="hasMore"
                    :error="loadMoreError"
                    :total="total"
                    @load="loadMore"
                />
            </template>
        </main>

        <ConfirmDialog
            v-model="confirmVisible"
            :title="confirmTitle"
            :message="confirmMessage"
            :confirm-text="confirmMode === 'clear' ? '清空' : '删除'"
            danger
            :loading="confirming"
            @confirm="confirmAction"
        />
    </div>
</template>

<style scoped>
.header-action {
    padding: 5px 9px;
    border: 1px solid var(--line);
    border-radius: 9px;
    background: transparent;
    color: var(--brand-2);
    font-size: 11px;
}

.header-action:disabled {
    opacity: 0.4;
}

.list-skeleton,
.history-list {
    display: grid;
    gap: 10px;
}

.history-item {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--surface);
}

.history-main {
    display: flex;
    min-width: 0;
    flex: 1;
    align-items: center;
    gap: 11px;
    padding: 0;
    border: none;
    background: none;
    color: inherit;
    text-align: left;
}

.history-main:disabled {
    opacity: 0.7;
}

.history-cover {
    position: relative;
    flex: none;
    width: 78px;
    overflow: hidden;
    border-radius: 9px;
}

.play-mark {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    background: rgba(0, 0, 0, 0.24);
    color: #fff;
}

.play-mark svg {
    width: 22px;
    height: 22px;
    filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.5));
}

.history-copy {
    display: grid;
    min-width: 0;
    flex: 1;
    gap: 4px;
}

.history-head {
    display: flex;
    align-items: center;
    gap: 7px;
}

.history-head strong {
    overflow: hidden;
    font-size: 13px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.finished,
.percent {
    flex: none;
    padding: 2px 6px;
    border-radius: 999px;
    font-size: 9px;
}

.finished {
    background: var(--ok-soft);
    color: var(--ok);
}

.percent {
    background: var(--brand-soft);
    color: var(--brand-2);
}

.episode {
    overflow: hidden;
    color: var(--text-soft);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.progress {
    display: block;
    overflow: hidden;
    height: 3px;
    border-radius: 999px;
    background: var(--surface-strong);
}

.progress i {
    display: block;
    height: 100%;
    border-radius: inherit;
    background: linear-gradient(90deg, var(--brand), var(--brand-2-bright));
}

.meta {
    color: var(--muted-soft);
    font-size: 10px;
}

.remove-btn {
    display: grid;
    flex: none;
    place-items: center;
    width: 30px;
    height: 30px;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: 9px;
    background: transparent;
    color: var(--muted);
}

.remove-btn svg {
    width: 14px;
    height: 14px;
}

/* ---------- 桌面端（≥1024） ---------- */

@media (min-width: 1024px) {
    .history-list {
        max-width: 900px;
        gap: 12px;
    }

    .history-item {
        padding: 14px 16px;
    }

    .history-main {
        gap: 16px;
    }

    .history-cover {
        width: 96px;
    }

    .history-head strong {
        font-size: 15px;
    }

    .episode {
        font-size: 12px;
    }

    .meta {
        font-size: 11px;
    }

    .remove-btn {
        width: 36px;
        height: 36px;
    }

    .remove-btn svg {
        width: 16px;
        height: 16px;
    }
}

/* 宽屏改为双列，减少长列表纵向滚动 */
@media (min-width: 1280px) {
    .history-list {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        max-width: none;
    }
}
</style>
