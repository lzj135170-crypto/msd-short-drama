<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { chatWithCompanion, streamCompanionChat } from '../../api/companion'
import { aiErrorText, aiFlagLabels } from '../../utils/aiFlags'
import { useAuthStore } from '../../stores/auth'
import { currentUserScope } from '../../utils/userScope'
import { useToastStore } from '../../stores/toast'
import AiFeedback from '../AiFeedback.vue'
import CommentComposer from '../comments/CommentComposer.vue'
import ConfirmDialog from '../ConfirmDialog.vue'

const SESSION_PATTERN = /^[A-Za-z0-9_-]{1,64}$/
const MAX_MESSAGES = 50
const MAX_SESSIONS = 10
const SESSION_TITLE_CHARS = 18
const REQUEST_TIMEOUT_MS = 35000
const SUGGESTIONS = ['这一集讲了什么', '帮我理一下人物关系', '前情回顾', '这集结尾什么意思']
const FOLLOW_UPS = ['再讲讲人物关系', '这一集有什么伏笔', '帮我回顾前情']

const props = defineProps({
    dramaId: {
        type: [String, Number],
        required: true
    },
    dramaTitle: {
        type: String,
        default: ''
    },
    episode: {
        type: Object,
        default: null
    },
    disabled: {
        type: Boolean,
        default: false
    },
    disabledText: {
        type: String,
        default: '解锁本集后可使用伴看'
    },
    positionMs: {
        type: [String, Number],
        default: 0
    }
})

const emit = defineEmits(['seek'])

const auth = useAuthStore()
const toast = useToastStore()

const messages = ref([])
const sessionId = ref('')
const sessions = ref([])
const sessionsOpen = ref(false)
const sending = ref(false)
const streamingDelta = ref(false)
const error = ref('')
const stopped = ref(false)
const locked = ref(false)
const waitingSeconds = ref(0)
const confirmResetOpen = ref(false)
const listEl = ref(null)
const composerRef = ref(null)

let sequence = 0
let requestSequence = 0
let abortController = null
let waitingTimer = null
let typeTimer = null
let stopRequested = false
let lastFailedText = ''

const episodeLabel = computed(() => {
    if (!props.episode) {
        return '当前集'
    }
    const title = String(props.episode.title || '').trim()
    if (title.startsWith('第')) {
        return title
    }
    return title
        ? `第${props.episode.episodeNo}集 · ${title}`
        : `第${props.episode.episodeNo}集`
})

// 1. 本地会话按用户隔离，同一浏览器切换账号不会读到上一个人的伴看记录
const storageScope = computed(() => currentUserScope(auth))
const dramaScope = computed(() => props.dramaId || 'default')
const storageKey = computed(() => `msd.companion.${storageScope.value}.${dramaScope.value}`)
const sessionsKey = computed(() => (
    `msd.companion.sessions.${storageScope.value}.${dramaScope.value}`
))

const waitingText = computed(() => {
    if (waitingSeconds.value < 3) {
        return '正在回忆这一集…'
    }
    if (waitingSeconds.value < 8) {
        return '正在整理人物和前情…'
    }
    return '还在组织回答，马上好…'
})

const lastAssistant = computed(() => {
    for (let index = messages.value.length - 1; index >= 0; index -= 1) {
        if (messages.value[index].role === 'assistant') {
            return messages.value[index]
        }
    }
    return null
})

const showFollowUps = computed(() => (
    !sending.value
    && !error.value
    && !stopped.value
    && Boolean(lastAssistant.value)
    && !lastAssistant.value?.typing
))

/**
 * 生成符合后端 sessionId 正则的会话 ID。
 */
function newSessionId() {
    if (globalThis.crypto?.randomUUID) {
        return globalThis.crypto.randomUUID()
    }
    return `d${props.dramaId}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

/**
 * 生成消息 ID，仅用于本地列表 key。
 */
function nextId() {
    sequence += 1
    return `${Date.now()}-${sequence}`
}

/**
 * 校验并归一本地消息，避免旧数据破坏渲染。
 */
function normalizeMessages(value) {
    if (!Array.isArray(value)) {
        return []
    }
    return value
        .filter((item) => item
            && (item.role === 'user' || item.role === 'assistant')
            && typeof item.text === 'string')
        .slice(-MAX_MESSAGES)
        .map((item) => ({
            id: item.id || nextId(),
            role: item.role,
            text: item.text,
            degraded: Boolean(item.degraded),
            degradedFlags: Array.isArray(item.degradedFlags)
                ? item.degradedFlags.filter((flag) => typeof flag === 'string')
                : [],
            sources: normalizeSources(item.sources),
            requestId: typeof item.requestId === 'string' ? item.requestId : '',
            typing: false,
            displayText: ''
        }))
}

/**
 * 校验引用数据，避免本地旧缓存破坏渲染。
 */
function normalizeSources(value) {
    if (!Array.isArray(value)) {
        return []
    }
    return value
        .filter((item) => item && typeof item === 'object')
        .slice(0, 8)
        .map((item) => ({
            type: item.type === 'previous'
                ? 'previous'
                : (item.type === 'transcript' ? 'transcript' : 'current'),
            episodeId: Number(item.episodeId) || 0,
            episodeNo: Number(item.episodeNo) || 0,
            episodeTitle: typeof item.episodeTitle === 'string' ? item.episodeTitle : '',
            summary: typeof item.summary === 'string' ? item.summary : '',
            startMs: Number(item.startMs) || 0,
            endMs: Number(item.endMs) || 0
        }))
}

/**
 * 毫秒转 mm:ss。
 */
function formatTime(value) {
    const total = Math.max(0, Math.round(Number(value) || 0))
    const minutes = Math.floor(total / 60000)
    const seconds = Math.floor((total % 60000) / 1000)
    return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

/**
 * 点击字幕引用，请求播放器跳到对应时间。
 */
function seekSource(source) {
    if (!source || source.type !== 'transcript') {
        return
    }
    emit('seek', {
        episodeId: Number(source.episodeId) || 0,
        positionMs: Number(source.startMs) || 0
    })
}

/**
 * 消息转可持久化结构，去掉打字机临时字段。
 */
function serializeMessages(list) {
    return list.slice(-MAX_MESSAGES).map((message) => ({
        id: message.id,
        role: message.role,
        text: message.text,
        degraded: message.degraded,
        degradedFlags: message.degradedFlags,
        sources: message.sources || [],
        requestId: message.requestId
    }))
}

/**
 * 取第一条用户消息作为会话标题。
 */
function firstUserTitle(list) {
    const first = (Array.isArray(list) ? list : []).find(
        (item) => item?.role === 'user' && typeof item.text === 'string' && item.text.trim()
    )
    if (!first) {
        return ''
    }
    const text = first.text.trim()
    return text.length > SESSION_TITLE_CHARS
        ? `${text.slice(0, SESSION_TITLE_CHARS)}…`
        : text
}

/**
 * 校验并归一本地会话列表。
 */
function normalizeSessions(value) {
    if (!Array.isArray(value)) {
        return []
    }
    const seen = new Set()
    const result = []
    for (const item of value) {
        if (!item || typeof item.sessionId !== 'string'
            || !SESSION_PATTERN.test(item.sessionId) || seen.has(item.sessionId)) {
            continue
        }
        seen.add(item.sessionId)
        const list = Array.isArray(item.messages) ? item.messages : []
        result.push({
            sessionId: item.sessionId,
            title: typeof item.title === 'string' && item.title
                ? item.title
                : (firstUserTitle(list) || '新会话'),
            updatedAt: Number(item.updatedAt) || 0,
            messages: list
        })
        if (result.length >= MAX_SESSIONS) {
            break
        }
    }
    return result
}

/**
 * 切换到指定会话的内存状态。
 */
function loadSession(session) {
    sessionId.value = session.sessionId
    messages.value = normalizeMessages(session.messages)
    error.value = ''
    stopped.value = false
    locked.value = false
    streamingDelta.value = false
    lastFailedText = ''
}

/**
 * 从 sessionStorage 恢复多会话；旧单会话结构自动迁移。
 */
function restore() {
    requestSequence += 1
    abortController?.abort()
    abortController = null
    stopTyping()
    stopWaiting()
    messages.value = []
    sessions.value = []
    sessionsOpen.value = false
    sessionId.value = newSessionId()
    sending.value = false
    streamingDelta.value = false
    error.value = ''
    stopped.value = false
    locked.value = false
    confirmResetOpen.value = false
    lastFailedText = ''
    try {
        const raw = sessionStorage.getItem(sessionsKey.value)
        if (raw) {
            const stored = JSON.parse(raw)
            sessions.value = normalizeSessions(stored?.sessions)
            if (sessions.value.length) {
                const active = sessions.value.find(
                    (item) => item.sessionId === stored?.activeSessionId
                ) || sessions.value[0]
                loadSession(active)
                return
            }
        }
        // 兼容 P0 的单会话存储
        const legacy = sessionStorage.getItem(storageKey.value)
        if (legacy) {
            const stored = JSON.parse(legacy)
            if (stored && typeof stored.sessionId === 'string'
                && SESSION_PATTERN.test(stored.sessionId)) {
                const session = {
                    sessionId: stored.sessionId,
                    title: firstUserTitle(stored.messages) || '上次会话',
                    updatedAt: Date.now(),
                    messages: Array.isArray(stored.messages) ? stored.messages : []
                }
                sessions.value = [session]
                loadSession(session)
                persist()
            }
        }
    } catch {
        // 本地存储损坏时直接使用新会话
    }
}

/**
 * 保存当前会话和会话列表，存储失败不影响聊天。
 */
function persist() {
    if (!SESSION_PATTERN.test(sessionId.value)) {
        return
    }
    const entry = {
        sessionId: sessionId.value,
        title: firstUserTitle(messages.value) || '新会话',
        updatedAt: Date.now(),
        messages: serializeMessages(messages.value)
    }
    const index = sessions.value.findIndex((item) => item.sessionId === entry.sessionId)
    if (index >= 0) {
        sessions.value[index] = entry
    } else {
        sessions.value.push(entry)
    }
    sessions.value = [...sessions.value]
        .sort((left, right) => right.updatedAt - left.updatedAt)
        .slice(0, MAX_SESSIONS)
    try {
        sessionStorage.setItem(sessionsKey.value, JSON.stringify({
            activeSessionId: sessionId.value,
            sessions: sessions.value
        }))
    } catch {
        // 忽略存储配额或隐私模式异常
    }
}

/**
 * 切换到另一个本地会话。
 */
function switchSession(targetSessionId) {
    if (targetSessionId === sessionId.value) {
        sessionsOpen.value = false
        return
    }
    persist()
    const target = sessions.value.find((item) => item.sessionId === targetSessionId)
    if (!target) {
        return
    }
    requestSequence += 1
    abortController?.abort()
    abortController = null
    stopTyping()
    stopWaiting()
    sending.value = false
    loadSession(target)
    sessionsOpen.value = false
    scrollToBottom()
}

/**
 * 滚动到消息列表底部。
 */
async function scrollToBottom() {
    await nextTick()
    if (listEl.value) {
        listEl.value.scrollTop = listEl.value.scrollHeight
    }
}

/**
 * 启动等待计时，给用户可感知的阶段反馈。
 */
function startWaiting() {
    stopWaiting()
    waitingSeconds.value = 0
    waitingTimer = setInterval(() => {
        waitingSeconds.value += 1
    }, 1000)
}

/**
 * 停止等待计时。
 */
function stopWaiting() {
    if (waitingTimer) {
        clearInterval(waitingTimer)
        waitingTimer = null
    }
}

/**
 * 批量降级标记转中文。
 */
function flagLabels(flags) {
    return aiFlagLabels(flags)
}

/**
 * 停止当前的打字机效果。
 */
function stopTyping() {
    if (typeTimer) {
        clearInterval(typeTimer)
        typeTimer = null
    }
}

/**
 * 对完整的助手回复做轻量打字机展示；减少动态偏好时直接显示全文。
 */
function startTypewriter(message) {
    stopTyping()
    const reduceMotion = globalThis.matchMedia
        && globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (reduceMotion || message.text.length > 800) {
        message.typing = false
        message.displayText = ''
        return
    }
    message.typing = true
    message.displayText = ''
    let index = 0
    typeTimer = setInterval(() => {
        index = Math.min(index + 2, message.text.length)
        message.displayText = message.text.slice(0, index)
        if (index >= message.text.length) {
            stopTyping()
            message.typing = false
            message.displayText = ''
            scrollToBottom()
        }
    }, 18)
}

/**
 * 取消当前生成。
 */
function stop() {
    if (!sending.value) {
        return
    }
    stopRequested = true
    const lastUser = [...messages.value].reverse().find((item) => item.role === 'user')
    lastFailedText = lastUser?.text || ''
    abortController?.abort()
    abortController = null
    stopTyping()
    stopWaiting()
    sending.value = false
    stopped.value = true
    toast.push('已停止生成')
}

/**
 * 创建一条助手消息。
 */
function createAssistantMessage(fields = {}) {
    return {
        id: nextId(),
        role: 'assistant',
        text: '',
        degraded: false,
        degradedFlags: [],
        sources: [],
        requestId: '',
        typing: false,
        displayText: '',
        ...fields
    }
}

/**
 * 应用普通 JSON 回复，作为流式不可用时的兜底。
 */
function applyJsonResponse(data) {
    if (data?.sessionId && SESSION_PATTERN.test(data.sessionId)) {
        sessionId.value = data.sessionId
    }
    locked.value = Boolean(data?.locked)
    const message = createAssistantMessage({
        text: data?.reply || '暂时没有回复，请稍后再试。',
        degraded: Boolean(data?.degraded),
        degradedFlags: Array.isArray(data?.degradedFlags) ? data.degradedFlags : [],
        sources: normalizeSources(data?.sources),
        requestId: typeof data?.requestId === 'string' ? data.requestId : ''
    })
    messages.value.push(message)
    startTypewriter(message)
}

/**
 * 发送一条消息；优先 SSE，流式不可用时自动回退普通 JSON。
 * 重试时复用上一条用户消息，不重复追加。
 */
async function sendText(text, options = {}) {
    const retry = Boolean(options.retry)
    const content = String(text || '').trim()
    if (!content || sending.value || props.disabled || locked.value || !props.episode) {
        return
    }
    const episodeId = Number(props.episode.episodeId)
    if (!Number.isFinite(episodeId) || episodeId <= 0) {
        return
    }

    composerRef.value?.clear()
    stopTyping()
    error.value = ''
    stopped.value = false
    streamingDelta.value = false
    lastFailedText = ''
    if (!retry) {
        messages.value.push({
            id: nextId(),
            role: 'user',
            text: content,
            degraded: false,
            degradedFlags: [],
            sources: [],
            requestId: ''
        })
    }
    persist()
    scrollToBottom()
    sending.value = true
    stopRequested = false
    startWaiting()

    const requestToken = ++requestSequence
    const controller = new AbortController()
    abortController = controller
    const payload = {
        message: content,
        sessionId: sessionId.value,
        episodeId,
        positionMs: Math.max(0, Math.round(Number(props.positionMs) || 0))
    }

    let assistant = null
    let doneData = null
    let streamError = null
    let streamFailed = false
    let metaRequestId = ''
    let pendingSources = []

    /**
     * 首个 delta 才创建气泡，避免空消息占位。
     */
    function ensureAssistant() {
        if (!assistant) {
            assistant = createAssistantMessage({
                requestId: metaRequestId,
                sources: pendingSources
            })
            messages.value.push(assistant)
        }
        return assistant
    }

    /**
     * 处理 SSE 事件；只更新当前请求的消息。
     */
    function onStreamEvent(event, data) {
        if (requestToken !== requestSequence || stopRequested) {
            return
        }
        if (event === 'meta') {
            if (typeof data?.sessionId === 'string' && SESSION_PATTERN.test(data.sessionId)) {
                sessionId.value = data.sessionId
            }
            if (typeof data?.requestId === 'string') {
                metaRequestId = data.requestId
            }
            if (assistant) {
                assistant.requestId = metaRequestId
            }
            return
        }
        if (event === 'delta') {
            const chunk = typeof data?.text === 'string' ? data.text : ''
            if (!chunk) {
                return
            }
            streamingDelta.value = true
            const message = ensureAssistant()
            message.text += chunk
            scrollToBottom()
            return
        }
        if (event === 'sources') {
            pendingSources = normalizeSources(data?.items)
            if (assistant) {
                assistant.sources = pendingSources
            }
            return
        }
        if (event === 'done') {
            doneData = data
            if (data?.locked) {
                locked.value = true
            }
            if (assistant) {
                assistant.degraded = Boolean(data?.degraded)
                assistant.degradedFlags = Array.isArray(data?.degradedFlags)
                    ? data.degradedFlags
                    : []
            }
            return
        }
        if (event === 'error') {
            streamError = data
        }
    }

    try {
        // 1. 先尝试流式；没有收到任何 delta 时允许回退 JSON
        try {
            await streamCompanionChat(payload, {
                signal: controller.signal,
                timeoutMs: REQUEST_TIMEOUT_MS,
                onEvent: onStreamEvent
            })
        } catch (cause) {
            // 1. 主动取消（停止、切集、切换会话）直接忽略；超时保留部分回答并提示重试
            if (requestToken !== requestSequence || stopRequested
                || cause?.name === 'AbortError'
                || (cause?.code === -2 && !cause?.timedOut)) {
                return
            }
            if (cause?.timedOut) {
                streamError = { code: 'timeout', message: '' }
            }
            if (!assistant && !streamingDelta.value) {
                streamFailed = true
            } else if (!cause?.timedOut) {
                throw cause
            }
        }
        if (requestToken !== requestSequence || stopRequested) {
            return
        }

        // 2. 输入被 Guard 拒绝时不再走 JSON，避免同一请求重复失败
        if (streamError?.code === 'invalid_input') {
            lastFailedText = content
            error.value = streamError.message || '这条消息包含不支持的指令'
            toast.push(error.value)
            return
        }
        // 3. 流式在首帧前超时或没有内容，回退 JSON
        if (streamError && !assistant) {
            streamFailed = true
        }
        if (streamError && assistant) {
            lastFailedText = content
            error.value = streamError.code === 'timeout'
                ? '这次等待太久了，可以重试一次'
                : '回答生成中断，可以重试一次'
            return
        }
        if (streamFailed || !assistant) {
            if (assistant && !assistant.text.trim()) {
                messages.value = messages.value.filter((item) => item.id !== assistant.id)
                assistant = null
            }
            const data = await chatWithCompanion(payload, {
                signal: controller.signal,
                timeoutMs: REQUEST_TIMEOUT_MS
            })
            if (requestToken !== requestSequence || stopRequested) {
                return
            }
            applyJsonResponse(data)
            return
        }

        // 4. 流式完成，补齐兜底文本和请求标识
        if (!assistant.text.trim()) {
            assistant.text = '暂时没有回复，请稍后再试。'
        }
        if (!assistant.requestId) {
            assistant.requestId = doneData?.requestId || metaRequestId
        }
        locked.value = Boolean(doneData?.locked)
    } catch (cause) {
        if (requestToken !== requestSequence
            || stopRequested
            || cause?.name === 'AbortError'
            || (cause?.code === -2 && !cause?.timedOut)) {
            return
        }
        lastFailedText = content
        error.value = aiErrorText(cause)
        toast.push(error.value)
    } finally {
        if (requestToken === requestSequence) {
            stopWaiting()
            sending.value = false
            streamingDelta.value = false
            abortController = null
            stopRequested = false
            persist()
            scrollToBottom()
        }
    }
}

/**
 * 重试最后一条失败消息。
 */
function retryLast() {
    if (lastFailedText) {
        sendText(lastFailedText, { retry: true })
    }
}

/**
 * 保留当前会话，另开一个新会话。
 */
function performReset() {
    persist()
    stopTyping()
    messages.value = []
    sessionId.value = newSessionId()
    streamingDelta.value = false
    error.value = ''
    stopped.value = false
    locked.value = false
    lastFailedText = ''
    confirmResetOpen.value = false
    sessionsOpen.value = false
    persist()
    composerRef.value?.clear()
    toast.success('已开始新会话')
}

/**
 * 复制回答内容。
 */
async function copyMessage(message) {
    try {
        await navigator.clipboard.writeText(message.text)
        toast.success('已复制')
    } catch {
        toast.push('复制失败，请手动选择文本')
    }
}

watch(() => props.dramaId, restore, { immediate: true })

watch(() => props.episode?.episodeId, () => {
    requestSequence += 1
    abortController?.abort()
    abortController = null
    stopTyping()
    stopWaiting()
    sending.value = false
    locked.value = false
    error.value = ''
    stopped.value = false
    lastFailedText = ''
})

watch(() => messages.value.length, () => {
    scrollToBottom()
})

onBeforeUnmount(() => {
    abortController?.abort()
    stopTyping()
    stopWaiting()
})
</script>

<template>
    <div class="companion-chat">
        <div class="companion-meta">
            <span class="episode-chip">{{ episodeLabel }}</span>
            <div class="meta-actions">
                <button v-if="sending" class="reset-btn stop-btn" type="button" @click="stop">
                    停止
                </button>
                <template v-else-if="!disabled && !locked">
                    <button
                        v-if="sessions.length > 1 || messages.length"
                        class="reset-btn"
                        type="button"
                        @click="sessionsOpen = !sessionsOpen"
                    >
                        会话
                    </button>
                    <button
                        v-if="messages.length"
                        class="reset-btn"
                        type="button"
                        @click="confirmResetOpen = true"
                    >
                        新会话
                    </button>
                </template>
            </div>
        </div>

        <div v-if="sessionsOpen && !sending" class="session-menu">
            <button
                v-for="item in sessions"
                :key="item.sessionId"
                class="session-item"
                :class="{ 'is-active': item.sessionId === sessionId }"
                type="button"
                @click="switchSession(item.sessionId)"
            >
                <span>{{ item.title || '新会话' }}</span>
                <small>{{ item.sessionId === sessionId ? '当前' : '切换' }}</small>
            </button>
        </div>
        <p class="companion-hint">
            {{ dramaTitle || '当前短剧' }} · 只讨论当前集及之前 · AI 可能出错，请以正片为准
        </p>

        <div v-if="disabled || locked" class="companion-locked">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M7.5 11V8.5a4.5 4.5 0 0 1 8.6-1.8"/>
                <rect x="4.5" y="11" width="15" height="9" rx="2"/>
            </svg>
            <p>{{ disabled ? disabledText : '本集解锁后可使用伴看' }}</p>
        </div>

        <template v-else>
            <div ref="listEl" class="message-list" aria-live="polite">
                <div v-if="!messages.length" class="empty">
                    <p>这一集有什么想聊的？</p>
                    <div class="suggestions">
                        <button
                            v-for="item in SUGGESTIONS"
                            :key="item"
                            class="suggestion"
                            type="button"
                            @click="sendText(item)"
                        >
                            {{ item }}
                        </button>
                    </div>
                </div>

                <div
                    v-for="message in messages"
                    :key="message.id"
                    class="msg"
                    :class="message.role"
                >
                    <div class="bubble">
                        {{ message.typing ? message.displayText : message.text }}
                    </div>
                    <div v-if="message.degraded" class="degraded-banner">
                        <strong>本回答资料不完整</strong>
                        <span>
                            {{ message.degradedFlags.length
                                ? flagLabels(message.degradedFlags).join('、')
                                : '建议结合正片判断' }}
                        </span>
                    </div>
                    <details
                        v-if="message.role === 'assistant' && message.sources?.length"
                        class="source-box"
                    >
                        <summary>参考 {{ message.sources.length }} 条资料</summary>
                        <ul>
                            <li v-for="(source, index) in message.sources" :key="index">
                                <strong>
                                    {{ source.type === 'current'
                                        ? '本集摘要'
                                        : (source.type === 'transcript'
                                            ? `第${source.episodeNo}集字幕 · ${formatTime(source.startMs)}`
                                            : `第${source.episodeNo}集前情`) }}
                                </strong>
                                <span>{{ source.summary }}</span>
                                <button
                                    v-if="source.type === 'transcript'"
                                    class="source-seek"
                                    type="button"
                                    @click="seekSource(source)"
                                >
                                    跳到 {{ formatTime(source.startMs) }}
                                </button>
                            </li>
                        </ul>
                    </details>
                    <div v-if="message.role === 'assistant'" class="msg-actions">
                        <button class="copy-btn" type="button" @click="copyMessage(message)">
                            复制
                        </button>
                        <AiFeedback
                            v-if="message.requestId && !locked"
                            scene="COMPANION"
                            :request-id="message.requestId"
                            :target-id="episode?.episodeId"
                            :session-id="sessionId"
                        />
                    </div>
                </div>

                <div v-if="sending && !streamingDelta" class="msg assistant">
                    <div class="bubble typing">
                        {{ waitingText }}
                        <span v-if="waitingSeconds" class="waiting-count">（{{ waitingSeconds }}s）</span>
                    </div>
                </div>

                <div v-if="error" class="error-card">
                    <p>{{ error }}</p>
                    <button class="btn btn-ghost btn-small" type="button" @click="retryLast">
                        重试
                    </button>
                </div>

                <div v-if="stopped && !sending" class="stopped-card">
                    <span>已停止生成</span>
                    <button class="copy-btn" type="button" @click="retryLast">重试</button>
                </div>
            </div>

            <div v-if="showFollowUps" class="follow-up-row">
                <button
                    v-for="item in FOLLOW_UPS"
                    :key="item"
                    class="suggestion"
                    type="button"
                    @click="sendText(item)"
                >
                    {{ item }}
                </button>
            </div>

            <CommentComposer
                ref="composerRef"
                class="companion-composer"
                placeholder="问问这一集的剧情…"
                :submitting="sending"
                submit-on-enter
                @submit="sendText"
            />
        </template>

        <ConfirmDialog
            v-model="confirmResetOpen"
            title="开始新会话"
            message="当前聊天记录会被清空，确定开始新会话吗？"
            confirm-text="开始新会话"
            @confirm="performReset"
        />
    </div>
</template>

<style scoped>
.companion-chat {
    display: flex;
    min-height: 0;
    flex-direction: column;
    gap: 8px;
}

.companion-meta {
    display: flex;
    flex: none;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
}

.episode-chip {
    overflow: hidden;
    padding: 3px 9px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--brand-2);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.reset-btn {
    flex: none;
    padding: 0;
    border: none;
    background: none;
    color: var(--muted);
    font-size: 11px;
}

.reset-btn:hover {
    color: var(--text);
}

.companion-hint {
    flex: none;
    margin: 0;
    color: var(--muted-soft);
    font-size: 10.5px;
    line-height: 1.5;
}

.message-list {
    display: grid;
    min-height: 0;
    flex: 1;
    gap: 10px;
    max-height: min(48dvh, 420px);
    overflow-y: auto;
    padding: 4px 3px 4px 0;
    overscroll-behavior: contain;
    scrollbar-width: thin;
}

.companion-locked {
    display: grid;
    flex: 1;
    align-content: center;
    justify-items: center;
    gap: 10px;
    padding: 28px 16px;
    color: var(--muted);
    font-size: 12px;
    text-align: center;
}

.companion-locked svg {
    width: 30px;
    height: 30px;
    color: var(--brand-2);
}

.companion-locked p {
    margin: 0;
}

.empty {
    display: grid;
    align-content: start;
    gap: 10px;
    padding: 18px 4px;
    color: var(--muted);
    font-size: 12px;
}

.empty p {
    margin: 0;
}

.suggestions {
    display: flex;
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 8px;
}

.suggestion {
    padding: 6px 11px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: var(--surface);
    color: var(--text);
    font-size: 11px;
}

.suggestion:hover {
    border-color: rgba(184, 58, 38, 0.4);
    color: var(--brand-ink);
}

.msg {
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: flex-start;
}

.msg.user {
    align-items: flex-end;
}

.bubble {
    max-width: 88%;
    padding: 9px 12px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--surface-soft);
    color: var(--text);
    font-size: 13px;
    line-height: 1.65;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
}

.msg.user .bubble {
    border: none;
    border-radius: 14px 14px 4px 14px;
    background: linear-gradient(135deg, var(--brand), var(--brand-2-bright));
    color: #241009;
}

.msg.assistant .bubble {
    border-bottom-left-radius: 4px;
}

.typing {
    color: var(--muted);
    font-style: italic;
}

.flag-row {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
}

.flag {
    padding: 2px 7px;
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-size: 10px;
}

.error {
    margin: 0;
    color: var(--danger);
    font-size: 11px;
}

.meta-actions {
    display: flex;
    flex: none;
    align-items: center;
    gap: 10px;
}

.stop-btn {
    color: var(--danger);
}

.degraded-banner {
    display: grid;
    gap: 2px;
    max-width: 88%;
    padding: 7px 10px;
    border-radius: 9px;
    background: var(--warning-soft);
    color: var(--warning-ink);
    font-size: 10.5px;
    line-height: 1.5;
}

.degraded-banner strong {
    font-size: 10.5px;
}

.msg-actions {
    display: flex;
    gap: 8px;
    padding-left: 2px;
}

.copy-btn {
    padding: 0;
    border: none;
    background: none;
    color: var(--muted);
    font-size: 10.5px;
}

.copy-btn:hover {
    color: var(--brand-2);
}

.waiting-count {
    color: var(--muted);
    font-style: normal;
}

.error-card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 8px 10px;
    border: 1px solid #f0c6c6;
    border-radius: 10px;
    background: var(--danger-soft);
}

.error-card p {
    margin: 0;
    color: var(--danger);
    font-size: 11px;
    line-height: 1.5;
}

.stopped-card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 7px 10px;
    border: 1px solid var(--line);
    border-radius: 10px;
    color: var(--muted);
    font-size: 11px;
}

.follow-up-row {
    display: flex;
    flex: none;
    flex-wrap: wrap;
    gap: 6px;
}

.follow-up-row .suggestion {
    font-size: 10.5px;
}

.companion-composer {
    flex: none;
}

.session-menu {
    display: grid;
    flex: none;
    gap: 4px;
    max-height: 156px;
    overflow-y: auto;
    padding: 6px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: var(--surface-soft);
}

.session-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 7px 8px;
    border: none;
    border-radius: 7px;
    background: transparent;
    color: var(--text);
    font-size: 11px;
    text-align: left;
}

.session-item:hover,
.session-item.is-active {
    background: var(--brand-soft);
}

.session-item.is-active {
    color: var(--brand-ink);
}

.session-item span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.session-item small {
    flex: none;
    color: var(--muted);
    font-size: 9px;
}

.source-box {
    max-width: 88%;
    padding: 7px 10px;
    border: 1px solid var(--line);
    border-radius: 9px;
    color: var(--muted);
    font-size: 10.5px;
}

.source-box summary {
    cursor: pointer;
    color: var(--brand-2);
}

.source-box ul {
    display: grid;
    gap: 6px;
    margin: 7px 0 0;
    padding: 0;
    list-style: none;
}

.source-box li {
    display: grid;
    gap: 2px;
    line-height: 1.55;
}

.source-box strong {
    color: var(--text);
    font-size: 10px;
}

.source-box span {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
}

.source-seek {
    justify-self: start;
    padding: 2px 8px;
    border: 1px solid rgba(184, 58, 38, 0.3);
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-size: 10px;
    line-height: 1.5;
}

@media (max-width: 420px) {
    .message-list {
        max-height: 42dvh;
    }
}
</style>
