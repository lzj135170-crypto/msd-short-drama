<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { chatWithAssistant } from '../../api/agent'
import { useAuthStore } from '../../stores/auth'
import { currentUserScope } from '../../utils/userScope'
import AppModal from '../AppModal.vue'
import GradientCover from '../GradientCover.vue'

const MAX_MESSAGES = 30
const SUGGESTIONS = [
    '有没有先婚后爱的甜宠剧',
    '推荐一部女主验尸破案的悬疑短剧',
    '类似《隐婚总裁的甜妻》的剧',
    '最近完结的短剧'
]

const props = defineProps({
    modelValue: {
        type: Boolean,
        default: false
    }
})

const emit = defineEmits(['update:modelValue'])

const auth = useAuthStore()
const sessionId = ref('')
const messages = ref([])
const input = ref('')
const sending = ref(false)
const error = ref('')
const listEl = ref(null)
let sequence = 0

const canSend = computed(() => Boolean(input.value.trim()) && !sending.value)

/**
 * 当前用户维度的存储作用域；切换账号后不再复读上一个人的会话。
 */
function storageScope() {
    return currentUserScope(auth)
}

function sessionStorageKey() {
    return `msd.assistant.${storageScope()}.session`
}

function messagesStorageKey() {
    return `msd.assistant.${storageScope()}.messages`
}

/**
 * 生成消息本地 key。
 */
function nextId() {
    sequence += 1
    return `${Date.now()}-${sequence}`
}

/**
 * 恢复本地会话和消息，只保留最近 30 条。
 */
function restore() {
    try {
        sessionId.value = sessionStorage.getItem(sessionStorageKey()) || ''
        const raw = sessionStorage.getItem(messagesStorageKey())
        const parsed = raw ? JSON.parse(raw) : []
        messages.value = Array.isArray(parsed)
            ? parsed.filter((item) => item && (item.role === 'user' || item.role === 'assistant'))
                .slice(-MAX_MESSAGES)
                .map((item) => ({
                    id: item.id || nextId(),
                    role: item.role,
                    text: typeof item.text === 'string' ? item.text : '',
                    cards: Array.isArray(item.cards) ? item.cards : []
                }))
            : []
    } catch {
        sessionId.value = ''
        messages.value = []
    }
}

/**
 * 持久化会话和消息，刷新页面后仍可继续。
 */
function persist() {
    try {
        sessionStorage.setItem(sessionStorageKey(), sessionId.value)
        sessionStorage.setItem(messagesStorageKey(), JSON.stringify(messages.value.slice(-MAX_MESSAGES)))
    } catch {
        // 存储不可用时只保留内存会话
    }
}

/**
 * 滚动到底部。
 */
async function scrollToBottom() {
    await nextTick()
    if (listEl.value) {
        listEl.value.scrollTop = listEl.value.scrollHeight
    }
}

/**
 * 发送一条消息。
 */
async function send(text) {
    const content = String(text ?? input.value).trim()
    if (!content || sending.value) {
        return
    }
    error.value = ''
    input.value = ''
    messages.value.push({ id: nextId(), role: 'user', text: content, cards: [] })
    sending.value = true
    persist()
    scrollToBottom()
    try {
        const data = await chatWithAssistant({
            message: content,
            sessionId: sessionId.value
        })
        if (data?.sessionId) {
            sessionId.value = data.sessionId
        }
        messages.value.push({
            id: nextId(),
            role: 'assistant',
            text: data?.reply || '暂时没有回复，请稍后再试。',
            cards: Array.isArray(data?.cards) ? data.cards : []
        })
    } catch (cause) {
        error.value = cause?.message || '助手暂时不可用，请稍后再试'
    } finally {
        sending.value = false
        persist()
        scrollToBottom()
    }
}

/**
 * 清空会话，重新开始。
 */
function reset() {
    sessionId.value = ''
    messages.value = []
    error.value = ''
    persist()
}

watch(() => props.modelValue, (open) => {
    if (open) {
        restore()
        scrollToBottom()
    }
}, { immediate: true })

// 2. 账号切换时立即换用新用户的会话，避免旧消息残留在弹层里
watch(() => auth.user?.userId, () => {
    restore()
})
</script>

<template>
    <AppModal
        :model-value="modelValue"
        title="AI 找剧助手"
        @update:model-value="emit('update:modelValue', $event)"
    >
        <div class="assistant">
            <div ref="listEl" class="assistant-list">
                <div v-if="!messages.length" class="assistant-empty">
                    <strong>想看点什么？</strong>
                    <p>描述剧情、题材、演员或“类似某部剧”，我会从已上架短剧里找。</p>
                    <div class="assistant-chips">
                        <button
                            v-for="item in SUGGESTIONS"
                            :key="item"
                            class="assistant-chip"
                            type="button"
                            @click="send(item)"
                        >
                            {{ item }}
                        </button>
                    </div>
                </div>

                <div
                    v-for="message in messages"
                    :key="message.id"
                    class="assistant-row"
                    :class="`is-${message.role}`"
                >
                    <div class="assistant-bubble">{{ message.text }}</div>
                    <div v-if="message.cards?.length" class="assistant-cards">
                        <RouterLink
                            v-for="card in message.cards"
                            :key="card.dramaId"
                            class="assistant-card"
                            :to="`/drama/${card.dramaId}`"
                            @click="emit('update:modelValue', false)"
                        >
                            <GradientCover
                                :seed="card.dramaId"
                                :title="card.title"
                                ratio="poster"
                                class="assistant-cover"
                            />
                            <span class="assistant-card-copy">
                                <strong>{{ card.title }}</strong>
                                <small>{{ card.subtitle || card.snippet }}</small>
                            </span>
                            <span class="assistant-card-go">去看</span>
                        </RouterLink>
                    </div>
                </div>

                <p v-if="sending" class="assistant-waiting">正在找剧、整理推荐…</p>
                <p v-if="error" class="assistant-error">{{ error }}</p>
            </div>

            <form class="assistant-composer" @submit.prevent="send()">
                <input
                    v-model="input"
                    maxlength="2000"
                    placeholder="描述你想看的短剧…"
                    :disabled="sending"
                >
                <button class="btn btn-primary btn-small" type="submit" :disabled="!canSend">
                    发送
                </button>
            </form>
            <button
                v-if="messages.length"
                class="assistant-reset"
                type="button"
                @click="reset"
            >
                新会话
            </button>
        </div>
    </AppModal>
</template>

<style scoped>
.assistant {
    display: grid;
    gap: 10px;
}

.assistant-list {
    display: grid;
    align-content: start;
    gap: 10px;
    max-height: min(56dvh, 520px);
    overflow-y: auto;
    padding-right: 2px;
    scrollbar-width: thin;
}

.assistant-empty {
    display: grid;
    gap: 8px;
    padding: 16px;
    border: 1px dashed var(--line);
    border-radius: 14px;
    color: var(--muted);
    font-size: 12px;
    line-height: 1.65;
}

.assistant-empty strong {
    color: var(--text);
    font-size: 14px;
}

.assistant-empty p {
    margin: 0;
}

.assistant-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    padding-top: 4px;
}

.assistant-chip {
    padding: 6px 10px;
    border: 1px solid rgba(184, 58, 38, 0.3);
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-size: 11px;
    text-align: left;
}

.assistant-row {
    display: grid;
    gap: 6px;
}

.assistant-row.is-user {
    justify-items: end;
}

.assistant-bubble {
    max-width: 88%;
    padding: 9px 12px;
    border-radius: 12px;
    background: var(--surface-soft);
    color: var(--text);
    font-size: 12px;
    line-height: 1.7;
    white-space: pre-wrap;
}

.assistant-row.is-user .assistant-bubble {
    background: var(--brand-soft);
    color: var(--text);
}

.assistant-cards {
    display: grid;
    gap: 8px;
    width: 100%;
}

.assistant-card {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface);
    color: var(--text);
}

.assistant-card:hover {
    border-color: rgba(184, 58, 38, 0.32);
}

.assistant-cover {
    flex: none;
    width: 46px;
}

.assistant-card-copy {
    display: grid;
    min-width: 0;
    gap: 2px;
}

.assistant-card-copy strong {
    overflow: hidden;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.assistant-card-copy small {
    display: -webkit-box;
    overflow: hidden;
    color: var(--muted);
    font-size: 10.5px;
    line-height: 1.5;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}

.assistant-card-go {
    flex: none;
    margin-left: auto;
    color: var(--brand-2);
    font-size: 11px;
}

.assistant-waiting,
.assistant-error {
    margin: 0;
    color: var(--muted);
    font-size: 11px;
}

.assistant-error {
    color: var(--danger);
}

.assistant-composer {
    display: flex;
    gap: 8px;
}

.assistant-composer input {
    flex: 1;
    height: 40px;
    padding: 0 12px;
    border: 1px solid var(--line-strong);
    border-radius: 10px;
    background: var(--surface);
    color: var(--text);
    font-size: 12px;
}

.assistant-reset {
    justify-self: end;
    padding: 0;
    border: none;
    background: none;
    color: var(--muted);
    font-size: 10.5px;
}
</style>
