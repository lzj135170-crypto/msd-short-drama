<script setup>
import { ref, watch } from 'vue'
import {
    deleteAdminComment,
    fetchAdminComments,
    hideAdminComment,
    retryAdminCommentAiAudit
} from '../api/admin'
import { usePagedList } from '../composables/usePagedList'
import { useToastStore } from '../stores/toast'
import { parseAiReviewResult } from '../utils/aiReview'
import { formatDateTime } from '../utils/format'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const toast = useToastStore()

const statusFilter = ref(1)
const aiReviewFilter = ref(null)
const pendingId = ref(0)
const deleteTarget = ref(null)
const deleteOpen = ref(false)
const deleteLoading = ref(false)

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
    ({ pageNo, pageSize }) => fetchAdminComments({
        status: statusFilter.value,
        aiReviewStatus: aiReviewFilter.value,
        pageNo,
        pageSize
    }),
    { keyOf: (item) => item.commentId }
)

const filters = [
    { label: '可见', value: 1 },
    { label: '待审', value: 0 },
    { label: '已隐藏', value: 2 },
    { label: '已删除', value: 3 },
    { label: '全部', value: null }
]

const aiFilters = [
    { label: 'AI 全部', value: null },
    { label: 'AI 风险', value: 3 },
    { label: 'AI 失败', value: 4 },
    { label: 'AI 通过', value: 2 },
    { label: 'AI 未审', value: 0 }
]

const STATUS_LABELS = {
    0: '待审',
    1: '可见',
    2: '已隐藏',
    3: '已删除'
}

const AI_STATUS_LABELS = {
    0: 'AI 未审',
    1: 'AI 审核中',
    2: 'AI 通过',
    3: 'AI 风险',
    4: 'AI 失败'
}

watch(statusFilter, () => refresh())
watch(aiReviewFilter, () => refresh())

function statusLabel(status) {
    return STATUS_LABELS[Number(status)] || '未知'
}

function aiStatusLabel(status) {
    return AI_STATUS_LABELS[Number(status)] || 'AI 未审'
}

/**
 * 解析 AI 审核 JSON，损坏时返回 null。
 */
function aiVerdict(item) {
    const payload = parseAiReviewResult(item.aiReviewResult)
    return payload?.verdict || null
}

function aiTags(item) {
    const tags = aiVerdict(item)?.tags
    return Array.isArray(tags) ? tags.filter((tag) => typeof tag === 'string') : []
}

/**
 * 重跑评论 AI 预审，异步等待后刷新列表。
 */
async function retryAi(item) {
    if (pendingId.value) {
        return
    }
    pendingId.value = item.commentId
    try {
        await retryAdminCommentAiAudit(item.commentId)
        toast.success('已提交 AI 预审')
        window.setTimeout(() => refresh(), 1500)
    } catch (cause) {
        toast.push(cause?.message || 'AI 预审失败')
    } finally {
        pendingId.value = 0
    }
}

async function hide(item) {
    if (pendingId.value) {
        return
    }
    pendingId.value = item.commentId
    try {
        await hideAdminComment(item.commentId)
        toast.success('已隐藏')
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '隐藏失败')
    } finally {
        pendingId.value = 0
    }
}

function askDelete(item) {
    deleteTarget.value = item
    deleteOpen.value = true
}

async function confirmDelete() {
    if (!deleteTarget.value || deleteLoading.value) {
        return
    }
    deleteLoading.value = true
    try {
        await deleteAdminComment(deleteTarget.value.commentId)
        toast.success('已删除')
        deleteOpen.value = false
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '删除失败')
    } finally {
        deleteLoading.value = false
    }
}
</script>

<template>
    <div class="sub-page">
        <PageHeader title="评论治理" back-to="/me"/>

        <main class="app-main comment-admin-page">
            <div class="filter-strip" aria-label="评论状态筛选">
                <button
                    v-for="item in filters"
                    :key="String(item.value)"
                    class="chip"
                    :class="{ 'is-active': statusFilter === item.value }"
                    type="button"
                    @click="statusFilter = item.value"
                >
                    {{ item.label }}
                </button>
            </div>

            <div class="filter-strip" aria-label="评论 AI 状态筛选">
                <button
                    v-for="item in aiFilters"
                    :key="`ai-${String(item.value)}`"
                    class="chip"
                    :class="{ 'is-active': aiReviewFilter === item.value }"
                    type="button"
                    @click="aiReviewFilter = item.value"
                >
                    {{ item.label }}
                </button>
            </div>

            <div v-if="loading" class="comment-list">
                <SkeletonBlock v-for="index in 4" :key="index" height="104px" radius="14px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <EmptyState
                v-else-if="!items.length"
                title="当前没有评论"
                description="切换状态筛选查看其他评论。"
            />

            <template v-else>
                <div class="comment-list">
                    <article v-for="item in items" :key="item.commentId" class="comment-card">
                        <div class="comment-head">
                            <span class="comment-user">用户 {{ item.userId }}</span>
                            <span class="status-chip" :class="`status-${item.status}`">
                                {{ statusLabel(item.status) }}
                            </span>
                        </div>
                        <p class="comment-content">{{ item.content }}</p>
                        <div class="comment-meta">
                            <span>短剧 {{ item.dramaId }}</span>
                            <span v-if="item.episodeId">剧集 {{ item.episodeId }}</span>
                            <span>{{ formatDateTime(item.createTime) }}</span>
                        </div>
                        <div class="ai-review-row">
                            <span class="ai-chip" :class="`ai-${item.aiReviewStatus || 0}`">
                                {{ aiStatusLabel(item.aiReviewStatus) }}
                            </span>
                            <span
                                v-if="aiVerdict(item)?.is_violation"
                                class="ai-reason"
                            >
                                {{ aiVerdict(item).violation_reason || '内容存在风险' }}
                            </span>
                            <span v-if="aiTags(item).length" class="ai-tags">
                                {{ aiTags(item).join('、') }}
                            </span>
                        </div>
                        <div class="comment-actions">
                            <button
                                v-if="Number(item.status) === 0 || Number(item.status) === 1"
                                class="btn btn-ghost btn-small"
                                type="button"
                                :disabled="pendingId === item.commentId"
                                @click="hide(item)"
                            >
                                {{ pendingId === item.commentId ? '处理中…' : '隐藏' }}
                            </button>
                            <button
                                v-if="Number(item.aiReviewStatus) === 4
                                    || Number(item.aiReviewStatus) === 0"
                                class="btn btn-ghost btn-small"
                                type="button"
                                :disabled="pendingId === item.commentId"
                                @click="retryAi(item)"
                            >
                                {{ pendingId === item.commentId ? '审核中…' : 'AI 重审' }}
                            </button>
                            <button
                                v-if="Number(item.status) !== 3"
                                class="btn btn-danger btn-small"
                                type="button"
                                @click="askDelete(item)"
                            >
                                删除
                            </button>
                        </div>
                    </article>
                </div>

                <LoadMore
                    :loading="loadingMore"
                    :has-more="hasMore"
                    :error="loadMoreError"
                    :total="total"
                    @load="loadMore"
                />
            </template>
        </main>

        <ConfirmDialog
            v-model="deleteOpen"
            title="删除评论"
            :message="deleteTarget ? `确认删除这条评论？${deleteTarget.content.slice(0, 40)}` : ''"
            confirm-text="删除"
            danger
            :loading="deleteLoading"
            @confirm="confirmDelete"
        />
    </div>
</template>

<style scoped>
.comment-admin-page {
    display: grid;
    gap: 12px;
}

.filter-strip {
    display: flex;
    gap: 8px;
    overflow-x: auto;
    padding-bottom: 2px;
    scrollbar-width: none;
}

.filter-strip::-webkit-scrollbar {
    display: none;
}

.filter-strip .chip {
    flex: none;
}

.comment-list {
    display: grid;
    gap: 10px;
}

.comment-card {
    display: grid;
    gap: 8px;
    padding: 14px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
}

.comment-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
}

.comment-user {
    color: var(--muted);
    font-size: 11px;
}

.status-chip {
    padding: 3px 9px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--muted);
    font-size: 10px;
}

.status-0 {
    color: var(--brand-2);
}

.status-1 {
    border-color: #b9e6d0;
    color: var(--ok);
}

.status-2 {
    border-color: #f0ddb4;
    color: var(--warning-ink);
}

.status-3 {
    border-color: #f0c6c6;
    color: var(--danger);
}

.comment-content {
    margin: 0;
    color: var(--text);
    font-size: 13px;
    line-height: 1.65;
    overflow-wrap: anywhere;
}

.comment-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    color: var(--muted-soft);
    font-size: 10px;
}

.comment-actions {
    display: flex;
    gap: 8px;
}

.ai-review-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
}

.ai-chip {
    padding: 2px 8px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--muted);
    font-size: 9.5px;
}

.ai-2 {
    border-color: #b9e6d0;
    color: var(--ok);
}

.ai-3 {
    border-color: #f0c6c6;
    color: var(--danger);
}

.ai-4 {
    border-color: #f0ddb4;
    color: var(--warning-ink);
}

.ai-reason {
    color: var(--danger);
    font-size: 10px;
    line-height: 1.5;
}

.ai-tags {
    color: var(--brand-2);
    font-size: 9.5px;
}

/* ---------- 桌面端（≥1024）：一行一条评论 ---------- */

@media (min-width: 1024px) {
    .comment-admin-page {
        gap: 14px;
    }

    .filter-strip {
        flex-wrap: wrap;
        overflow: visible;
    }

    .comment-list {
        gap: 10px;
    }

    .comment-card {
        grid-template-columns: minmax(240px, 280px) minmax(0, 1fr) auto;
        align-items: center;
        column-gap: 24px;
        row-gap: 6px;
        padding: 14px 20px;
    }

    .comment-head {
        grid-column: 1;
        grid-row: 1;
    }

    .comment-content {
        display: -webkit-box;
        grid-column: 2;
        grid-row: 1 / span 2;
        overflow: hidden;
        -webkit-box-orient: vertical;
        -webkit-line-clamp: 2;
    }

    .comment-meta {
        grid-column: 1;
        grid-row: 2;
        flex-wrap: nowrap;
        white-space: nowrap;
    }

    .comment-actions {
        grid-column: 3;
        grid-row: 1 / span 2;
        justify-content: flex-end;
    }
}
</style>
