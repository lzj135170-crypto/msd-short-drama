<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { deleteComment, fetchMyComments } from '../api/interaction'
import { useToastStore } from '../stores/toast'
import { usePagedList } from '../composables/usePagedList'
import { commentStatusLabel, formatRelative } from '../utils/format'
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
    loadMore,
    remove
} = usePagedList(
    ({ pageNo, pageSize }) => fetchMyComments({ pageNo, pageSize }),
    { keyOf: (item) => item.commentId }
)

const deleteTarget = ref(null)
const deleting = ref(false)

const confirmVisible = computed({
    get: () => Boolean(deleteTarget.value),
    set: (value) => {
        if (!value) {
            deleteTarget.value = null
        }
    }
})

function open(item) {
    if (!item.dramaTitle) {
        return
    }
    if (item.episodeId) {
        router.push({
            path: `/drama/${item.dramaId}`,
            query: { episode: item.episodeId }
        })
        return
    }
    router.push(`/drama/${item.dramaId}`)
}

async function confirmDelete() {
    if (!deleteTarget.value) {
        return
    }
    deleting.value = true
    try {
        await deleteComment(deleteTarget.value.commentId)
        remove(deleteTarget.value.commentId)
        toast.success('评论已删除')
        deleteTarget.value = null
    } catch (cause) {
        toast.push(cause?.message || '删除失败')
    } finally {
        deleting.value = false
    }
}
</script>

<template>
    <div class="sub-page">
        <PageHeader title="我的评论" back-to="/me"/>

        <main class="app-main">
            <div v-if="loading" class="list-skeleton">
                <SkeletonBlock v-for="index in 4" :key="index" height="112px" radius="14px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <template v-else>
                <EmptyState
                    v-if="!items.length"
                    title="还没有评论"
                    description="看剧时留下你的想法，评论会集中在这里。"
                    action-text="去首页"
                    @action="router.push('/')"
                />

                <div v-else class="comment-list">
                    <article v-for="item in items" :key="item.commentId" class="my-comment">
                        <button
                            class="comment-main"
                            type="button"
                            :disabled="!item.dramaTitle"
                            @click="open(item)"
                        >
                            <span class="comment-head">
                                <span class="target">
                                    {{ item.dramaTitle || '内容暂不可见' }}
                                    <template v-if="item.episodeTitle"> · {{ item.episodeTitle }}</template>
                                </span>
                                <span class="status" :class="`status-${item.status}`">
                                    {{ commentStatusLabel(item.status) }}
                                </span>
                            </span>

                            <span class="comment-content">{{ item.content }}</span>

                            <span class="comment-meta">
                                <span>{{ formatRelative(item.createTime) }}</span>
                                <span>赞 {{ item.likeCount || 0 }}</span>
                                <span>回复 {{ item.replyCount || 0 }}</span>
                            </span>
                        </button>

                        <button
                            class="delete-btn"
                            type="button"
                            @click="deleteTarget = item"
                        >
                            删除
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
            title="删除评论"
            message="删除后无法恢复，确定删除这条评论？"
            confirm-text="删除"
            danger
            :loading="deleting"
            @confirm="confirmDelete"
        />
    </div>
</template>

<style scoped>
.list-skeleton,
.comment-list {
    display: grid;
    gap: 10px;
}

.my-comment {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--surface);
}

.comment-main {
    display: grid;
    min-width: 0;
    flex: 1;
    gap: 6px;
    padding: 0;
    border: none;
    background: none;
    color: inherit;
    text-align: left;
}

.comment-main:disabled {
    opacity: 0.7;
}

.comment-head {
    display: flex;
    align-items: center;
    gap: 8px;
}

.target {
    overflow: hidden;
    color: var(--brand-2);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.status {
    flex: none;
    padding: 2px 6px;
    border-radius: 999px;
    font-size: 9px;
}

.status-0 {
    background: var(--warning-soft);
    color: var(--warning-ink);
}

.status-1 {
    background: var(--ok-soft);
    color: var(--ok);
}

.status-2 {
    background: var(--surface-muted);
    color: var(--muted);
}

.comment-content {
    display: -webkit-box;
    overflow: hidden;
    color: var(--text-soft);
    font-size: 13px;
    line-height: 1.65;
    white-space: pre-wrap;
    word-break: break-word;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
}

.comment-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    color: var(--muted-soft);
    font-size: 10px;
}

.delete-btn {
    flex: none;
    padding: 4px 0;
    border: none;
    background: none;
    color: var(--danger);
    font-size: 11px;
}

/* ---------- 桌面端（≥1024） ---------- */

@media (min-width: 1024px) {
    .comment-list {
        max-width: 900px;
        gap: 12px;
    }

    .my-comment {
        padding: 16px 18px;
    }

    .comment-content {
        font-size: 14px;
    }

    .delete-btn {
        padding: 6px 10px;
        border: 1px solid #f0c6c6;
        border-radius: 9px;
        font-size: 12px;
    }
}

@media (min-width: 1280px) {
    .comment-list {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        max-width: none;
    }
}
</style>
