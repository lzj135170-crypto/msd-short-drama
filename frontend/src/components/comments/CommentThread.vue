<script setup>
import { computed, nextTick, ref } from 'vue'
import { createComment, deleteComment, fetchReplies } from '../../api/interaction'
import { useInteractionAck } from '../../composables/useInteractionAck'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toast'
import { usePagedList } from '../../composables/usePagedList'
import CommentComposer from './CommentComposer.vue'
import CommentItem from './CommentItem.vue'
import ConfirmDialog from '../ConfirmDialog.vue'
import LoadMore from '../LoadMore.vue'

const props = defineProps({
    root: {
        type: Object,
        required: true
    },
    dramaId: {
        type: [String, Number],
        required: true
    },
    episodeId: {
        type: [String, Number],
        default: null
    }
})

const emit = defineEmits(['delete'])

const auth = useAuthStore()
const toast = useToastStore()

const {
    items: replyItems,
    total: replyTotal,
    loading: repliesLoading,
    loadingMore: repliesLoadingMore,
    error: repliesError,
    loadMoreError: repliesMoreError,
    hasMore: repliesHasMore,
    initialized: repliesInitialized,
    refresh: loadReplies,
    loadMore: loadMoreReplies,
    append: appendReply,
    remove: removeReplyLocal
} = usePagedList(
    ({ pageNo, pageSize }) => fetchReplies(props.root.commentId, { pageNo, pageSize }),
    {
        pageSize: 10,
        keyOf: (item) => item.commentId,
        immediate: false
    }
)

const expanded = ref(false)
const replyOpen = ref(false)
const replyTarget = ref(null)
const submitting = ref(false)
const pendingRequestId = ref('')
const composerRef = ref(null)
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

/**
 * 展开时首次加载回复，收起只隐藏。
 */
async function toggleReplies() {
    if (expanded.value) {
        expanded.value = false
        return
    }
    expanded.value = true
    if (!repliesInitialized.value) {
        await loadReplies()
    }
}

function startReply(target) {
    replyTarget.value = target || props.root
    replyOpen.value = true
    nextTick(() => composerRef.value?.focus())
}

function cancelReply() {
    replyOpen.value = false
    replyTarget.value = null
}

/**
 * 生成评论请求幂等键；重试时复用，避免重复回复。
 */
function newRequestId() {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID()
    }
    return `req-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * 回复成功后在当前线程内追加，并同步根评论回复数。
 */
async function submitReply(content) {
    if (submitting.value) {
        return
    }
    submitting.value = true
    if (!pendingRequestId.value) {
        pendingRequestId.value = newRequestId()
    }
    const target = replyTarget.value || props.root
    const payload = {
        dramaId: Number(props.dramaId),
        episodeId: props.episodeId === null ? undefined : Number(props.episodeId),
        rootId: props.root.commentId,
        parentId: target.commentId,
        content,
        clientRequestId: pendingRequestId.value
    }

    try {
        const result = await createComment(payload)
        pendingRequestId.value = ''
        const view = {
            commentId: result.commentId,
            dramaId: payload.dramaId,
            episodeId: payload.episodeId ?? null,
            userId: auth.user?.id ?? 0,
            nickname: auth.displayName,
            avatarUrl: auth.user?.avatarUrl || '',
            rootId: props.root.commentId,
            parentId: payload.parentId,
            replyToUserId: target.userId,
            replyToNickname: target.nickname || '匿名剧迷',
            content,
            likeCount: 0,
            replyCount: 0,
            liked: false,
            createTime: new Date().toISOString()
        }

        props.root.replyCount = Number(props.root.replyCount || 0) + 1
        expanded.value = true
        if (!repliesInitialized.value) {
            await loadReplies()
        }
        appendReply(view)
        replyTotal.value = Number(props.root.replyCount || 0)
        if (result.eventId) {
            ack.track(result.eventId, {
                onDead: () => {
                    removeReplyLocal(result.commentId)
                    props.root.replyCount = Math.max(0, Number(props.root.replyCount || 0) - 1)
                    replyTotal.value = Number(props.root.replyCount || 0)
                    toast.push('回复同步失败，已移除本地回复')
                }
            })
        }
        composerRef.value?.clear()
        cancelReply()
        toast.success('回复成功')
    } catch (cause) {
        toast.push(cause?.message || '回复失败')
    } finally {
        submitting.value = false
    }
}

function askDelete(comment) {
    deleteTarget.value = comment
}

async function confirmDelete() {
    if (!deleteTarget.value) {
        return
    }
    deleting.value = true
    try {
        await deleteComment(deleteTarget.value.commentId)
        removeReplyLocal(deleteTarget.value.commentId)
        props.root.replyCount = Math.max(0, Number(props.root.replyCount || 0) - 1)
        replyTotal.value = Number(props.root.replyCount || 0)
        toast.success('回复已删除')
        deleteTarget.value = null
    } catch (cause) {
        toast.push(cause?.message || '删除失败')
    } finally {
        deleting.value = false
    }
}
</script>

<template>
    <div class="comment-thread">
        <CommentItem
            :comment="root"
            @reply="startReply"
            @delete="emit('delete', root)"
        />

        <button
            v-if="Number(root.replyCount) > 0 || expanded"
            class="replies-toggle"
            type="button"
            @click="toggleReplies"
        >
            {{ expanded ? '收起回复' : `查看 ${root.replyCount} 条回复` }}
        </button>

        <div v-if="expanded" class="reply-list">
            <p v-if="repliesLoading" class="reply-state">回复加载中…</p>
            <div v-else-if="repliesError" class="reply-state is-error">
                {{ repliesError }}
                <button type="button" @click="loadReplies">重试</button>
            </div>

            <CommentItem
                v-for="reply in replyItems"
                :key="reply.commentId"
                :comment="reply"
                is-reply
                @reply="startReply"
                @delete="askDelete"
            />

            <LoadMore
                v-if="replyItems.length"
                :loading="repliesLoadingMore"
                :has-more="repliesHasMore"
                :error="repliesMoreError"
                :total="replyTotal"
                :show-end="false"
                @load="loadMoreReplies"
            />
        </div>

        <CommentComposer
            v-if="replyOpen"
            ref="composerRef"
            :reply-to="replyTarget?.nickname || root.nickname || '匿名剧迷'"
            :submitting="submitting"
            placeholder="回复这条评论…"
            @submit="submitReply"
            @cancel="cancelReply"
        />

        <ConfirmDialog
            v-model="confirmVisible"
            title="删除回复"
            message="删除后无法恢复，确定删除这条回复？"
            confirm-text="删除"
            danger
            :loading="deleting"
            @confirm="confirmDelete"
        />
    </div>
</template>

<style scoped>
.comment-thread {
    padding: 2px 0;
    border-bottom: 1px solid var(--line);
}

.comment-thread :deep(.comment-item) {
    border-bottom: none;
}

.replies-toggle {
    margin-left: 44px;
    padding: 2px 0 8px;
    border: none;
    background: none;
    color: var(--brand-2);
    font-size: 11px;
}

.reply-list {
    margin-left: 20px;
    padding-left: 12px;
    border-left: 1px solid var(--line);
}

.reply-state {
    padding: 6px 0;
    color: var(--muted);
    font-size: 11px;
}

.reply-state.is-error {
    color: var(--danger);
}

.reply-state button {
    padding: 0;
    border: none;
    background: none;
    color: var(--brand-2);
    font-size: 11px;
    text-decoration: underline;
}

.comment-thread :deep(.composer) {
    margin: 4px 0 12px 44px;
}
</style>
