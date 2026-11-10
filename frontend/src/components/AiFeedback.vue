<script setup>
import { computed, ref, watch } from 'vue'
import { sendAiFeedback } from '../api/ai'
import { useAuthStore } from '../stores/auth'
import { aiErrorText } from '../utils/aiFlags'

const props = defineProps({
    scene: {
        type: String,
        required: true
    },
    requestId: {
        type: String,
        default: ''
    },
    targetId: {
        type: [String, Number],
        default: null
    },
    sessionId: {
        type: String,
        default: ''
    }
})

const auth = useAuthStore()
const loading = ref(false)
const submitted = ref(0)
const error = ref('')

const loggedIn = computed(() => auth.hasValidSession())
const submittedText = computed(() => (
    submitted.value === 1 ? '已标记有用' : submitted.value === -1 ? '已记录，我们会继续改进' : ''
))

watch(() => props.requestId, () => {
    submitted.value = 0
    error.value = ''
})

/**
 * 提交赞/踩；同一 requestId 只提交一次。
 */
async function rate(value) {
    if (!loggedIn.value || !props.requestId || loading.value || submitted.value) {
        return
    }
    loading.value = true
    error.value = ''
    try {
        await sendAiFeedback({
            scene: props.scene,
            requestId: props.requestId,
            rating: value,
            targetId: props.targetId || undefined,
            sessionId: props.sessionId || undefined
        })
        submitted.value = value
    } catch (cause) {
        error.value = aiErrorText(cause)
    } finally {
        loading.value = false
    }
}
</script>

<template>
    <div v-if="loggedIn && requestId" class="ai-feedback" aria-label="AI 回答反馈">
        <span class="feedback-label">这个回答</span>
        <button
            class="feedback-btn"
            :class="{ 'is-active': submitted === 1 }"
            type="button"
            :disabled="loading || Boolean(submitted)"
            :aria-pressed="submitted === 1"
            @click="rate(1)"
        >
            有用
        </button>
        <button
            class="feedback-btn"
            :class="{ 'is-active': submitted === -1 }"
            type="button"
            :disabled="loading || Boolean(submitted)"
            :aria-pressed="submitted === -1"
            @click="rate(-1)"
        >
            没用
        </button>
        <span v-if="loading" class="feedback-note">提交中…</span>
        <span v-else-if="submittedText" class="feedback-note is-done">{{ submittedText }}</span>
        <span v-else-if="error" class="feedback-note is-error">{{ error }}</span>
    </div>
</template>

<style scoped>
.ai-feedback {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    color: var(--muted);
    font-size: 10px;
}

.feedback-label {
    color: var(--muted-soft);
}

.feedback-btn {
    padding: 2px 8px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: transparent;
    color: var(--muted);
    font-size: 10px;
    line-height: 1.6;
}

.feedback-btn.is-active {
    border-color: rgba(184, 58, 38, 0.35);
    background: var(--brand-soft);
    color: var(--brand-ink);
}

.feedback-btn:disabled {
    opacity: 0.55;
}

.feedback-note {
    color: var(--muted-soft);
}

.feedback-note.is-done {
    color: var(--ok);
}

.feedback-note.is-error {
    color: var(--danger);
}
</style>
