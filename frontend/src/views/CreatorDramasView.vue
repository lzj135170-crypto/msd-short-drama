<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { deleteDrama, fetchMyDramas, submitDrama } from '../api/creator'
import { usePagedList } from '../composables/usePagedList'
import { useToastStore } from '../stores/toast'
import { formatDateTime } from '../utils/format'
import AiReviewNotice from '../components/AiReviewNotice.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
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
    loadMore
} = usePagedList(
    ({ pageNo, pageSize }) => fetchMyDramas({ pageNo, pageSize }),
    { keyOf: (item) => item.dramaId }
)

const deletingId = ref(0)
const submitPendingId = ref(0)
const deleteTarget = ref(null)
const deleteOpen = ref(false)
const deleteLoading = ref(false)

const STATUS_LABELS = {
    0: '草稿',
    1: '审核中',
    2: '已上架',
    3: '已驳回',
    4: '已下架'
}

const AI_LABELS = {
    0: 'AI 未提交',
    1: 'AI 待审',
    2: 'AI 通过',
    3: 'AI 驳回',
    4: 'AI 失败'
}

function statusLabel(status) {
    return STATUS_LABELS[Number(status)] || '未知'
}

function aiLabel(status) {
    return AI_LABELS[Number(status)] || 'AI 未知'
}

function canEdit(item) {
    return Number(item.reviewStatus) === 0 || Number(item.reviewStatus) === 3
}

function openCreate() {
    router.push('/creator/dramas/new')
}

function edit(item) {
    router.push(`/creator/dramas/${item.dramaId}/edit`)
}

async function submit(item) {
    if (submitPendingId.value) {
        return
    }
    submitPendingId.value = item.dramaId
    try {
        await submitDrama(item.dramaId)
        toast.success('已提交审核')
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '提交审核失败')
    } finally {
        submitPendingId.value = 0
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
    deletingId.value = deleteTarget.value.dramaId
    try {
        await deleteDrama(deleteTarget.value.dramaId)
        toast.success('已删除')
        deleteOpen.value = false
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '删除失败')
    } finally {
        deleteLoading.value = false
        deletingId.value = 0
    }
}
</script>

<template>
    <div class="sub-page">
        <PageHeader title="创作者中心" back-to="/me">
            <template #right>
                <button class="btn btn-primary btn-small" type="button" @click="openCreate">
                    新建短剧
                </button>
            </template>
        </PageHeader>

        <main class="app-main creator-page">
            <div v-if="loading" class="card-list">
                <SkeletonBlock v-for="index in 4" :key="index" height="118px" radius="16px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <EmptyState
                v-else-if="!items.length"
                title="还没有作品"
                description="创建第一部短剧，添加剧集后提交审核。"
                action-text="新建短剧"
                @action="openCreate"
            />

            <template v-else>
                <div class="card-list">
                    <article v-for="item in items" :key="item.dramaId" class="drama-card">
                        <div class="card-head">
                            <div class="card-copy">
                                <h2>{{ item.title }}</h2>
                                <p>{{ item.subtitle || item.description || '暂无简介' }}</p>
                            </div>
                            <span class="status-chip" :class="`status-${item.reviewStatus}`">
                                {{ statusLabel(item.reviewStatus) }}
                            </span>
                        </div>

                        <div class="card-meta">
                            <span>共 {{ item.episodeCount || 0 }} 集</span>
                            <span>{{ aiLabel(item.aiReviewStatus) }}</span>
                            <span>提交 {{ item.submitTime ? formatDateTime(item.submitTime) : '—' }}</span>
                        </div>

                        <AiReviewNotice
                            :review-status="item.reviewStatus"
                            :ai-review-status="item.aiReviewStatus"
                            :ai-review-result="item.aiReviewResult"
                        />

                        <div class="card-actions">
                            <button
                                v-if="canEdit(item)"
                                class="btn btn-ghost btn-small"
                                type="button"
                                @click="edit(item)"
                            >
                                编辑
                            </button>
                            <button
                                v-if="canEdit(item)"
                                class="btn btn-ghost btn-small"
                                type="button"
                                :disabled="submitPendingId === item.dramaId"
                                @click="submit(item)"
                            >
                                {{ submitPendingId === item.dramaId ? '提交中…' : '提交审核' }}
                            </button>
                            <button
                                v-if="canEdit(item)"
                                class="btn btn-danger btn-small"
                                type="button"
                                :disabled="deletingId === item.dramaId"
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
            title="删除短剧"
            :message="deleteTarget ? `确认删除《${deleteTarget.title}》？删除后不可恢复。` : ''"
            confirm-text="删除"
            danger
            :loading="deleteLoading"
            @confirm="confirmDelete"
        />
    </div>
</template>

<style scoped>
.creator-page {
    display: grid;
    gap: 12px;
}

.card-list {
    display: grid;
    gap: 12px;
}

.drama-card {
    display: grid;
    gap: 10px;
    padding: 15px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--panel);
    box-shadow: var(--shadow-soft);
}

.card-head {
    display: flex;
    align-items: flex-start;
    gap: 10px;
}

.card-copy {
    min-width: 0;
    flex: 1;
}

.card-copy h2 {
    overflow: hidden;
    margin: 0 0 4px;
    font-size: 16px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.card-copy p {
    display: -webkit-box;
    overflow: hidden;
    margin: 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}

.status-chip {
    flex: none;
    padding: 3px 9px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--muted);
    font-size: 10px;
}

.status-0 {
    color: var(--muted);
}

.status-1 {
    border-color: #f0ddb4;
    color: var(--warning-ink);
}

.status-2 {
    border-color: #b9e6d0;
    color: var(--ok);
}

.status-3 {
    border-color: #f0c6c6;
    color: var(--danger);
}

.card-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    color: var(--muted-soft);
    font-size: 10px;
}

.card-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

/* ---------- 桌面端（≥1024）：一行一条，标题 / 元信息 / 操作 ---------- */

@media (min-width: 1024px) {
    .creator-page {
        gap: 14px;
    }

    .card-list {
        gap: 10px;
    }

    .drama-card {
        grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr) auto;
        grid-template-areas:
            "head meta actions"
            "notice notice actions";
        align-items: center;
        column-gap: 24px;
        row-gap: 6px;
        padding: 14px 20px;
    }

    .card-head {
        grid-area: head;
        align-items: center;
    }

    .card-meta {
        grid-area: meta;
        align-self: center;
    }

    .drama-card :deep(.ai-review-notice) {
        grid-area: notice;
    }

    .card-actions {
        grid-area: actions;
        align-self: center;
        justify-content: flex-end;
    }

    .card-copy h2 {
        margin-bottom: 0;
        font-size: 15px;
    }

    /* 简介让位给单行布局，详情进编辑页查看 */
    .card-copy p {
        display: none;
    }

    .card-meta {
        flex-wrap: nowrap;
        white-space: nowrap;
    }

    .card-actions {
        flex-wrap: nowrap;
    }
}
</style>
