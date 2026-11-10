<script setup>
import { computed, ref, watch } from 'vue'
import {
    createComment,
    deleteComment,
    fetchCommentSummary,
    fetchComments
} from '../../api/interaction'
import { useInteractionAck } from '../../composables/useInteractionAck'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toast'
import { usePagedList } from '../../composables/usePagedList'
import CommentComposer from './CommentComposer.vue'
import CommentThread from './CommentThread.vue'
import ConfirmDialog from '../ConfirmDialog.vue'
import EmptyState from '../EmptyState.vue'
import ErrorState from '../ErrorState.vue'
import LoadMore from '../LoadMore.vue'
import SkeletonBlock from '../SkeletonBlock.vue'

const props = defineProps({
    dramaId: {
        type: [String, Number],
        required: true
    },
    episodeId: {
        type: [String, Number],
        default: null
    },
    title: {
        type: String,
        default: '评论'
    },
    showTitle: {
        type: Boolean,
        default: true
    }
})

const auth = useAuthStore()
const toast = useToastStore()

const {
    items,
    total,
    loading,
    loadingMore,
    error,
    loadMoreError,
    hasMore,
    initialized,
    refresh,
    loadMore,
    prepend,
    remove
} = usePagedList(
    ({ pageNo, pageSize }) => fetchComments({
        dramaId: Number(props.dramaId),
        episodeId: props.episodeId === null ? undefined : Number(props.episodeId),
        pageNo,
        pageSize
    }),
    {
        pageSize: 10,
        keyOf: (item) => item.commentId
    }
)

const composerRef = ref(null)
const summary = ref(null)
const summaryLoading = ref(false)
const submitting = ref(false)
const pendingRequestId = ref('')
const deleteTarget = ref(null)
const deleting = ref(false)
const ack = useInteractionAck()

const confirmVisible = computed({
    get: () => Boolean(deleteTarget.value),
    set: (value) => {
        if (!value) {
            deleteTarget.value = null
        }
    }
})

const deleteMessage = computed(() => (
    Number(deleteTarget.value?.replyCount) > 0
        ? '这条评论下的回复也会一起删除，且无法恢复。'
        : '删除后无法恢复，确定删除这条评论？'
))

const summaryAvailable = computed(() => Boolean(
    summary.value?.summary || summary.value?.representatives?.length
))

/**
 * 加载 AI 课代表；失败静默，不阻断评论列表。
 */
async function loadSummary() {
    summaryLoading.value = true
    try {
        const data = await fetchCommentSummary({
            dramaId: Number(props.dramaId),
            episodeId: props.episodeId === null ? undefined : Number(props.episodeId)
        })
        summary.value = data?.degraded ? null : data
    } catch {
        summary.value = null
    } finally {
        summaryLoading.value = false
    }
}

watch(() => [props.dramaId, props.episodeId], loadSummary, { immediate: true })

/**
 * 生成评论请求幂等键；重试时复用，避免重复评论。
 */
function newRequestId() {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID()
    }
    return `req-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * 发布根评论后本地插入，避免整页刷新。
 */
async function submitRoot(content) {
    if (submitting.value) {
        return
    }
    submitting.value = true
    if (!pendingRequestId.value) {
        pendingRequestId.value = newRequestId()
    }
    try {
        const result = await createComment({
            dramaId: Number(props.dramaId),
            episodeId: props.episodeId === null ? undefined : Number(props.episodeId),
            content,
            clientRequestId: pendingRequestId.value
        })
        pendingRequestId.value = ''
        prepend({
            commentId: result.commentId,
            dramaId: Number(props.dramaId),
            episodeId: props.episodeId === null ? null : Number(props.episodeId),
            userId: auth.user?.id ?? 0,
            nickname: auth.displayName,
            avatarUrl: auth.user?.avatarUrl || '',
            rootId: 0,
            parentId: 0,
            replyToUserId: null,
            replyToNickname: null,
            content,
            likeCount: 0,
            replyCount: 0,
            liked: false,
            createTime: new Date().toISOString()
        })
        if (result.eventId) {
            ack.track(result.eventId, {
                onDead: () => {
                    remove(result.commentId)
                    toast.push('评论同步失败，已移除本地评论')
                }
            })
        }
        composerRef.value?.clear()
        toast.success('评论已发布')
    } catch (cause) {
        toast.push(cause?.message || '评论失败')
    } finally {
        submitting.value = false
    }
}

function askDelete(comment) {
    deleteTarget.value = comment
}

/**
 * 删除根评论，后端会级联删除回复。
 */
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
    <section class="comment-section">
        <div v-if="showTitle" class="section-title">
            <h2>{{ title }}</h2>
            <span>{{ initialized ? `${total} 条` : '' }}</span>
        </div>

        <section v-if="summaryLoading" class="comment-summary is-loading">
            <span class="summary-badge">AI 课代表</span>
            <span>正在整理评论观点…</span>
        </section>

        <section v-else-if="summaryAvailable" class="comment-summary">
            <div class="summary-head">
                <span class="summary-badge">AI 课代表</span>
                <span class="summary-disclaimer">仅供参考</span>
            </div>
            <p v-if="summary.summary" class="summary-text">{{ summary.summary }}</p>
            <div v-if="summary.representatives?.length" class="summary-quotes">
                <blockquote v-for="item in summary.representatives" :key="item.commentId">
                    <p>{{ item.content }}</p>
                    <span>{{ item.likeCount || 0 }} 赞</span>
                </blockquote>
            </div>
        </section>

        <CommentComposer
            ref="composerRef"
            placeholder="说点什么…"
            :submitting="submitting"
            @submit="submitRoot"
        />

        <div v-if="loading" class="comment-loading">
            <SkeletonBlock v-for="index in 3" :key="index" height="58px" radius="12px"/>
        </div>

        <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

        <template v-else>
            <EmptyState
                v-if="!items.length"
                title="还没有评论"
                description="来发第一条评论吧。"
            />
            <div v-else class="comment-list">
                <CommentThread
                    v-for="comment in items"
                    :key="comment.commentId"
                    :root="comment"
                    :drama-id="dramaId"
                    :episode-id="episodeId"
                    @delete="askDelete"
                />
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

        <ConfirmDialog
            v-model="confirmVisible"
            title="删除评论"
            :message="deleteMessage"
            confirm-text="删除"
            danger
            :loading="deleting"
            @confirm="confirmDelete"
        />
    </section>
</template>

<style scoped>
.comment-section {
    display: grid;
    gap: 12px;
    padding-top: 6px;
}

.comment-loading {
    display: grid;
    gap: 10px;
}

.comment-list {
    display: grid;
}

.comment-summary {
    display: grid;
    gap: 8px;
    padding: 12px;
    border: 1px solid #f0ddb4;
    border-radius: 12px;
    background: linear-gradient(145deg, var(--warning-soft), var(--surface-soft));
}

.comment-summary.is-loading {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
    font-size: 11px;
}

.summary-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
}

.summary-badge {
    color: var(--brand-2);
    font-size: 10.5px;
    font-weight: 800;
    letter-spacing: 0.04em;
}

.summary-disclaimer {
    color: var(--muted);
    font-size: 9.5px;
}

.summary-text {
    color: var(--text);
    font-size: 12px;
    line-height: 1.7;
}

.summary-quotes {
    display: grid;
    gap: 6px;
}

.summary-quotes blockquote {
    display: grid;
    gap: 3px;
    margin: 0;
    padding: 7px 9px;
    border-left: 2px solid rgba(184, 58, 38, 0.35);
    border-radius: 0 7px 7px 0;
    background: var(--surface-muted);
}

.summary-quotes p {
    color: var(--text-soft);
    font-size: 11px;
    line-height: 1.6;
}

.summary-quotes span {
    color: var(--muted);
    font-size: 9.5px;
}
</style>
