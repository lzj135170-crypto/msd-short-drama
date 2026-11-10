<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
    placeholder: {
        type: String,
        default: '说点什么…'
    },
    submitting: {
        type: Boolean,
        default: false
    },
    replyTo: {
        type: String,
        default: ''
    },
    maxLength: {
        type: Number,
        default: 1000
    },
    submitOnEnter: {
        type: Boolean,
        default: false
    }
})

const emit = defineEmits(['submit', 'cancel'])

const content = ref('')
const textareaRef = ref(null)

const canSubmit = computed(() => content.value.trim().length > 0 && !props.submitting)

function submit() {
    if (!canSubmit.value) {
        return
    }
    emit('submit', content.value.trim())
}

/**
 * 可选 Enter 发送；Shift+Enter 换行，输入法组合期间不触发。
 */
function onKeydown(event) {
    if (!props.submitOnEnter || event.key !== 'Enter' || event.shiftKey || event.isComposing) {
        return
    }
    event.preventDefault()
    submit()
}

function clear() {
    content.value = ''
}

function focus() {
    textareaRef.value?.focus()
}

function set(value) {
    content.value = value == null ? '' : String(value)
}

defineExpose({ clear, focus, set })
</script>

<template>
    <form class="composer" @submit.prevent="submit">
        <div v-if="replyTo" class="reply-chip">
            <span>回复 @{{ replyTo }}</span>
            <button type="button" aria-label="取消回复" @click="emit('cancel')">取消</button>
        </div>

        <textarea
            ref="textareaRef"
            v-model="content"
            class="composer-input"
            :maxlength="maxLength"
            :placeholder="placeholder"
            rows="3"
            @keydown="onKeydown"
        ></textarea>

        <div class="composer-foot">
            <span class="counter">{{ content.length }}/{{ maxLength }}</span>
            <button class="btn btn-primary btn-small" type="submit" :disabled="!canSubmit">
                {{ submitting ? '发送中…' : '发送' }}
            </button>
        </div>
    </form>
</template>

<style scoped>
.composer {
    display: grid;
    gap: 8px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--surface-soft);
}

.reply-chip {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    color: var(--brand-2);
    font-size: 11px;
}

.reply-chip button {
    padding: 0;
    border: none;
    background: none;
    color: var(--muted);
    font-size: 11px;
}

.composer-input {
    width: 100%;
    min-height: 66px;
    padding: 10px 11px;
    border: 1px solid var(--line-strong);
    border-radius: 10px;
    background: var(--surface);
    color: var(--text);
    font-size: 16px;
    line-height: 1.6;
    resize: vertical;
}

.composer-input::placeholder {
    color: var(--muted-soft);
}

.composer-input:focus {
    outline: none;
    border-color: rgba(184, 58, 38, 0.5);
    box-shadow: 0 0 0 3px rgba(184, 58, 38, 0.1);
}

.composer-foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
}

.counter {
    color: var(--muted-soft);
    font-family: "Cascadia Code", Consolas, monospace;
    font-size: 10px;
}
</style>
