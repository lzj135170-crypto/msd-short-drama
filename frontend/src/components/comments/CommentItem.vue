<script setup>
import { computed, ref } from 'vue'
import { likeTarget, unlikeTarget } from '../../api/interaction'
import { useInteractionAck } from '../../composables/useInteractionAck'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toast'
import { formatRelative } from '../../utils/format'
import UserAvatar from '../UserAvatar.vue'

const props = defineProps({
    comment: {
        type: Object,
        required: true
    },
    isReply: {
        type: Boolean,
        default: false
    }
})

defineEmits(['reply', 'delete'])

const auth = useAuthStore()
const toast = useToastStore()
const pendingLike = ref(false)
const ackId = ref('')
const ack = useInteractionAck()

const displayName = computed(() => props.comment.nickname || '匿名剧迷')
const canDelete = computed(() => (
    Boolean(auth.user?.id) && Number(props.comment.userId) === Number(auth.user.id)
))

/**
 * 评论点赞：本地先变，失败回滚。
 */
async function toggleLike() {
    if (pendingLike.value) {
        return
    }
    const previous = Boolean(props.comment.liked)
    pendingLike.value = true
    props.comment.liked = !previous
    props.comment.likeCount = Math.max(0, Number(props.comment.likeCount || 0) + (previous ? -1 : 1))

    try {
        const result = previous
            ? await unlikeTarget(3, props.comment.commentId)
            : await likeTarget(3, props.comment.commentId)
        props.comment.liked = result.active
        if (result.eventId) {
            ackId.value = result.eventId
            ack.track(result.eventId, {
                onDead: () => {
                    if (ackId.value !== result.eventId) {
                        return
                    }
                    ackId.value = ''
                    props.comment.liked = previous
                    props.comment.likeCount = Math.max(
                        0,
                        Number(props.comment.likeCount || 0) + (previous ? 1 : -1)
                    )
                    toast.push('点赞同步失败，已恢复状态')
                }
            })
        }
    } catch (cause) {
        props.comment.liked = previous
        props.comment.likeCount = Math.max(
            0,
            Number(props.comment.likeCount || 0) + (previous ? 1 : -1)
        )
        toast.push(cause?.message || '点赞失败')
    } finally {
        pendingLike.value = false
    }
}
</script>

<template>
    <article class="comment-item" :class="{ 'is-reply': isReply }">
        <UserAvatar :name="displayName" :src="comment.avatarUrl" :size="isReply ? 28 : 34"/>

        <div class="comment-body">
            <header class="comment-head">
                <span class="nickname">{{ displayName }}</span>
                <span v-if="comment.replyToNickname" class="reply-to">
                    回复 @{{ comment.replyToNickname }}
                </span>
                <span class="time">{{ formatRelative(comment.createTime) }}</span>
            </header>

            <p class="comment-content">{{ comment.content }}</p>

            <footer class="comment-actions">
                <button
                    class="link-btn"
                    :class="{ 'is-active': comment.liked }"
                    type="button"
                    :disabled="pendingLike"
                    @click="toggleLike"
                >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <path d="M12 20s-7.5-4.6-7.5-10A4.5 4.5 0 0 1 12 6.8 4.5 4.5 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z"/>
                    </svg>
                    {{ comment.likeCount || 0 }}
                </button>
                <button class="link-btn" type="button" @click="$emit('reply', comment)">
                    回复
                </button>
                <button
                    v-if="canDelete"
                    class="link-btn is-danger"
                    type="button"
                    @click="$emit('delete', comment)"
                >
                    删除
                </button>
            </footer>
        </div>
    </article>
</template>

<style scoped>
.comment-item {
    display: flex;
    gap: 10px;
    padding: 12px 0;
    border-bottom: 1px solid var(--line);
}

.comment-item.is-reply {
    padding: 9px 0;
    border-bottom: none;
}

.comment-body {
    display: grid;
    min-width: 0;
    flex: 1;
    gap: 5px;
}

.comment-head {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 6px;
}

.nickname {
    color: var(--text);
    font-size: 12px;
    font-weight: 700;
}

.reply-to {
    color: var(--brand-2);
    font-size: 10px;
}

.time {
    margin-left: auto;
    color: var(--muted-soft);
    font-size: 10px;
}

.comment-content {
    color: var(--text-soft);
    font-size: 13px;
    line-height: 1.75;
    white-space: pre-wrap;
    word-break: break-word;
}

.comment-actions {
    display: flex;
    align-items: center;
    gap: 14px;
}

.link-btn {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    padding: 2px 0;
    border: none;
    background: none;
    color: var(--muted);
    font-size: 11px;
}

.link-btn svg {
    width: 13px;
    height: 13px;
}

.link-btn.is-active {
    color: var(--brand-2);
}

.link-btn.is-danger {
    color: var(--danger);
}

.link-btn:disabled {
    opacity: 0.5;
}
</style>
