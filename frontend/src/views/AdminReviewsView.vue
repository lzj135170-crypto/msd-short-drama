<script setup>
import { computed, ref, watch } from 'vue'
import {
    auditDrama,
    auditPendingDramas,
    decideReview,
    fetchReviewLogs,
    fetchReviewQueue,
    offlineDrama,
    reindexRag
} from '../api/admin'
import { usePagedList } from '../composables/usePagedList'
import { useToastStore } from '../stores/toast'
import { aiFlagLabels } from '../utils/aiFlags'
import { formatDateTime } from '../utils/format'
import AiFeedback from '../components/AiFeedback.vue'
import AppModal from '../components/AppModal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const toast = useToastStore()

const reviewFilter = ref(1)
const aiFilter = ref(null)
const auditingId = ref(0)
const batchOpen = ref(false)
const batchLoading = ref(false)
const batchResult = ref(null)
const batchResultOpen = ref(false)
const detailOpen = ref(false)
const detailTarget = ref(null)
const reindexLoading = ref(false)
const reindexOpen = ref(false)
const reindexResult = ref(null)
const reindexResultOpen = ref(false)
const decisionOpen = ref(false)
const decisionTarget = ref(null)
const decision = ref('APPROVE')
const remark = ref('')
const decisionLoading = ref(false)
const offlineTarget = ref(null)
const offlineOpen = ref(false)
const offlineLoading = ref(false)
const logsOpen = ref(false)
const logsTarget = ref(null)
const logs = ref([])
const logsLoading = ref(false)

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
    ({ pageNo, pageSize }) => fetchReviewQueue({
        reviewStatus: reviewFilter.value,
        aiReviewStatus: aiFilter.value,
        pageNo,
        pageSize
    }),
    { keyOf: (item) => item.dramaId }
)

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

const filters = [
    { label: '待审', value: 1 },
    { label: '全部', value: null },
    { label: '草稿', value: 0 },
    { label: '已上架', value: 2 },
    { label: '已驳回', value: 3 },
    { label: '已下架', value: 4 }
]

const aiFilters = [
    { label: 'AI 全部', value: null },
    { label: '未提交', value: 0 },
    { label: '待审', value: 1 },
    { label: '通过', value: 2 },
    { label: '驳回', value: 3 },
    { label: '失败', value: 4 }
]

const canSubmitDecision = computed(() => (
    !decisionLoading.value
    && (decision.value === 'APPROVE' || remark.value.trim().length > 0)
))

const detailData = computed(() => (
    detailTarget.value ? parseAiResult(detailTarget.value) : null
))

const detailMalformed = computed(() => (
    Boolean(detailTarget.value?.aiReviewResult) && !detailData.value
))

const detailAdvisory = computed(() => {
    const advisory = detailData.value?.advisory
    return advisory && typeof advisory === 'object' ? advisory : null
})

const detailFlags = computed(() => aiFlagLabels(detailData.value?.degradedFlags))

watch([reviewFilter, aiFilter], () => refresh())

function statusLabel(status) {
    return STATUS_LABELS[Number(status)] || '未知'
}

function aiLabel(status) {
    return AI_LABELS[Number(status)] || 'AI 未知'
}

const SENTIMENT_LABELS = {
    positive: '正向',
    negative: '负向',
    mixed: '混合',
    unknown: '未知'
}

/**
 * 解析审核结果 JSON；旧数据或损坏数据返回 null。
 */
function parseAiResult(item) {
    if (!item.aiReviewResult) {
        return null
    }
    try {
        const parsed = JSON.parse(item.aiReviewResult)
        return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
    } catch {
        return null
    }
}

/**
 * 把审核结果 JSON 转成简短可读文本。
 */
function aiSummary(item) {
    const payload = parseAiResult(item)
    if (!payload) {
        return item.aiReviewResult
            ? 'AI 结果损坏，请重新审核'
            : '尚未执行 AI 审核'
    }
    if (payload.degraded) {
        const flags = aiFlagLabels(payload.degradedFlags)
        return `AI 降级：${flags.join('、') || '未知原因'}`
    }
    const verdict = payload.verdict
    if (!verdict) {
        return 'AI 未返回有效结论'
    }
    if (verdict.is_violation) {
        const tags = Array.isArray(verdict.tags) ? verdict.tags.join('、') : ''
        return `违规：${verdict.violation_reason || '未说明'}${tags ? `（${tags}）` : ''}`
    }
    return '未发现违规'
}

/**
 * 读取 Rex 情感/标签建议；旧审核记录没有该字段。
 */
function aiAdvisory(item) {
    const payload = parseAiResult(item)
    const advisory = payload?.advisory
    return advisory && typeof advisory === 'object' ? advisory : null
}

/**
 * 情感展示文案。
 */
function sentimentText(advisory) {
    return SENTIMENT_LABELS[advisory?.sentiment?.label] || '未知'
}

/**
 * 情感 chip 样式。
 */
function sentimentClass(advisory) {
    const label = advisory?.sentiment?.label
    return label && SENTIMENT_LABELS[label] ? `is-${label}` : ''
}

/**
 * 建议标签名称列表。
 */
function tagNames(advisory) {
    const tags = Array.isArray(advisory?.tags) ? advisory.tags : []
    return tags.map((tag) => tag?.name).filter(Boolean)
}

/**
 * 建议标签及命中片数，详情中展示 support。
 */
function tagLabels(advisory) {
    const tags = Array.isArray(advisory?.tags) ? advisory.tags : []
    return tags
        .filter((tag) => tag?.name)
        .map((tag) => (Number(tag.support) > 0 ? `${tag.name}(${tag.support})` : tag.name))
}

/**
 * 部分片段失败时提示，不把建议当作完整结论。
 */
function isPartialAdvisory(advisory) {
    return !advisory?.degraded && Number(advisory?.failedChunks) > 0
}

/**
 * AI 审核按钮文案：失败项显示为重试。
 */
function auditActionText(item) {
    return Number(item.aiReviewStatus) === 4 ? '重新 AI 审核' : 'AI 审核'
}

/**
 * 打开 AI 审核详情抽屉。
 */
function openDetail(item) {
    detailTarget.value = item
    detailOpen.value = true
}

/**
 * 汇总情感和标签证据，供详情抽屉展开查看。
 */
function advisoryEvidence(advisory) {
    if (!advisory) {
        return []
    }
    const evidence = []
    const sentimentEvidence = Array.isArray(advisory.sentiment?.evidence)
        ? advisory.sentiment.evidence : []
    sentimentEvidence.forEach((text) => {
        if (text) {
            evidence.push(`情感：${text}`)
        }
    })
    const tags = Array.isArray(advisory.tags) ? advisory.tags : []
    tags.forEach((tag) => {
        const tagEvidence = Array.isArray(tag?.evidence) ? tag.evidence : []
        if (tag?.name) {
            evidence.push(tagEvidence.length
                ? `${tag.name}：${tagEvidence.join('；')}`
                : `${tag.name}：无原文证据`)
        }
    })
    return evidence
}

/**
 * 对单部待审短剧执行 AI 审核。
 */
async function runAudit(item) {
    if (auditingId.value) {
        return
    }
    auditingId.value = item.dramaId
    try {
        const result = await auditDrama(item.dramaId)
        if (!result) {
            toast.push('短剧不在待审状态')
        } else if (result.updated) {
            toast.success(`AI 审核完成：${aiLabel(result.aiReviewStatus)}`)
        } else {
            toast.push('AI 结果未写回，状态可能已变化')
        }
        if (detailTarget.value?.dramaId === item.dramaId) {
            detailOpen.value = false
        }
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || 'AI 审核失败')
    } finally {
        auditingId.value = 0
    }
}

/**
 * 打开批量 AI 审核确认。
 */
function openBatch() {
    if (!batchLoading.value && !auditingId.value) {
        batchOpen.value = true
    }
}

/**
 * 批量审核当前筛选下最多 5 条；查看 AI 失败时同时重试失败项。
 */
async function confirmBatch() {
    if (batchLoading.value) {
        return
    }
    batchLoading.value = true
    try {
        const result = await auditPendingDramas({
            limit: 5,
            includeFailed: aiFilter.value === 4
        })
        batchResult.value = result || null
        batchOpen.value = false
        batchResultOpen.value = true
        if (result?.degradedFlags?.includes('drama')) {
            toast.push('短剧服务暂不可用，请稍后重试')
        } else {
            toast.success(`批量审核完成：${result?.audited || 0} 条`)
        }
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '批量审核失败')
    } finally {
        batchLoading.value = false
    }
}

function openDecision(item, value) {
    decisionTarget.value = item
    decision.value = value
    remark.value = ''
    decisionOpen.value = true
}

async function confirmDecision() {
    if (!decisionTarget.value || !canSubmitDecision.value) {
        return
    }
    decisionLoading.value = true
    try {
        await decideReview(decisionTarget.value.dramaId, {
            decision: decision.value,
            remark: remark.value.trim()
        })
        toast.success(decision.value === 'APPROVE' ? '已通过并上架' : '已驳回')
        decisionOpen.value = false
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '审核失败')
    } finally {
        decisionLoading.value = false
    }
}

/**
 * 查看某部短剧的审核日志。
 */
async function openLogs(item) {
    logsTarget.value = item
    logs.value = []
    logsOpen.value = true
    logsLoading.value = true
    try {
        logs.value = await fetchReviewLogs(item.dramaId)
    } catch (cause) {
        toast.push(cause?.message || '日志加载失败')
    } finally {
        logsLoading.value = false
    }
}

function openOffline(item) {
    offlineTarget.value = item
    offlineOpen.value = true
}

async function confirmOffline() {
    if (!offlineTarget.value || offlineLoading.value) {
        return
    }
    offlineLoading.value = true
    try {
        await offlineDrama(offlineTarget.value.dramaId, '审核台下架')
        toast.success('已下架')
        offlineOpen.value = false
        await refresh()
    } catch (cause) {
        toast.push(cause?.message || '下架失败')
    } finally {
        offlineLoading.value = false
    }
}

/**
 * 重建索引涉及全量拉取和写入，先二次确认。
 */
function openReindex() {
    if (!reindexLoading.value) {
        reindexOpen.value = true
    }
}

/**
 * 新剧上架后重建推荐索引，并展示完整结果。
 */
async function rebuildIndex() {
    if (reindexLoading.value) {
        return
    }
    reindexLoading.value = true
    try {
        const result = await reindexRag()
        reindexResult.value = result || null
        reindexOpen.value = false
        reindexResultOpen.value = true
        if (result?.partial) {
            toast.push('索引只完成了一部分，请查看详情')
        } else {
            toast.success(`索引已重建：${result?.dramaCount || 0} 部短剧 / ${result?.episodeCount || 0} 集`)
        }
    } catch (cause) {
        toast.push(cause?.message || '索引重建失败')
    } finally {
        reindexLoading.value = false
    }
}
</script>

<template>
    <div class="sub-page">
        <PageHeader title="审核台" back-to="/me">
            <template #right>
                <button
                    class="btn btn-ghost btn-small"
                    type="button"
                    :disabled="reindexLoading"
                    @click="openReindex"
                >
                    {{ reindexLoading ? '重建中…' : '重建索引' }}
                </button>
            </template>
        </PageHeader>

        <main class="app-main review-page">
            <div class="filter-strip" aria-label="审核状态筛选">
                <button
                    v-for="item in filters"
                    :key="String(item.value)"
                    class="chip"
                    :class="{ 'is-active': reviewFilter === item.value }"
                    type="button"
                    @click="reviewFilter = item.value"
                >
                    {{ item.label }}
                </button>
            </div>

            <div class="filter-strip ai-filter-strip" aria-label="AI 审核状态筛选">
                <button
                    v-for="item in aiFilters"
                    :key="`ai-${String(item.value)}`"
                    class="chip"
                    :class="{ 'is-active': aiFilter === item.value }"
                    type="button"
                    @click="aiFilter = item.value"
                >
                    {{ item.label }}
                </button>
            </div>

            <div class="review-toolbar">
                <button
                    class="btn btn-primary btn-small"
                    type="button"
                    :disabled="batchLoading || Boolean(auditingId)"
                    @click="openBatch"
                >
                    {{ batchLoading ? '批量审核中…' : '批量 AI 审核（5 条）' }}
                </button>
                <span>AI 只写建议，人工决定最终状态</span>
            </div>

            <div v-if="loading" class="card-list">
                <SkeletonBlock v-for="index in 4" :key="index" height="150px" radius="16px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <EmptyState
                v-else-if="!items.length"
                title="当前没有待处理内容"
                description="切换状态筛选查看其他短剧。"
            />

            <template v-else>
                <div class="card-list">
                    <article v-for="item in items" :key="item.dramaId" class="review-card">
                        <div class="review-head">
                            <div class="review-copy">
                                <h2>{{ item.title }}</h2>
                                <p>{{ item.subtitle || item.description || '暂无简介' }}</p>
                            </div>
                            <span class="status-chip" :class="`status-${item.reviewStatus}`">
                                {{ statusLabel(item.reviewStatus) }}
                            </span>
                        </div>

                        <div class="review-meta">
                            <span>作者 {{ item.authorId }}</span>
                            <span>{{ aiLabel(item.aiReviewStatus) }}</span>
                            <span>{{ item.submitTime ? formatDateTime(item.submitTime) : '未提交' }}</span>
                        </div>

                        <div class="ai-review">
                            <p class="ai-summary" :class="{ degraded: item.aiReviewStatus === 4 }">
                                {{ aiSummary(item) }}
                            </p>
                            <div v-if="aiAdvisory(item)" class="ai-advisory">
                                <span
                                    class="adv-chip"
                                    :class="sentimentClass(aiAdvisory(item))"
                                >
                                    情感：{{ sentimentText(aiAdvisory(item)) }}
                                </span>
                                <span
                                    v-if="aiAdvisory(item).sentiment?.highNegative"
                                    class="adv-chip is-danger"
                                >
                                    建议人工复核
                                </span>
                                <span
                                    v-if="tagNames(aiAdvisory(item)).length"
                                    class="adv-tags"
                                >
                                    建议标签：{{ tagNames(aiAdvisory(item)).join('、') }}（仅供参考）
                                </span>
                                <button
                                    class="adv-link"
                                    type="button"
                                    @click="openDetail(item)"
                                >
                                    查看证据
                                </button>
                                <span
                                    v-if="isPartialAdvisory(aiAdvisory(item))"
                                    class="adv-note"
                                >
                                    部分片段未分析
                                </span>
                                <span
                                    v-if="aiAdvisory(item).truncated"
                                    class="adv-note"
                                >
                                    文本较长，仅分析前部
                                </span>
                                <span
                                    v-if="aiAdvisory(item).degraded"
                                    class="adv-note is-warning"
                                >
                                    情感分析暂不可用
                                </span>
                            </div>
                        </div>

                        <div class="review-actions">
                            <button
                                v-if="Number(item.reviewStatus) === 1"
                                class="btn btn-ghost btn-small"
                                type="button"
                                :disabled="auditingId === item.dramaId"
                                @click="runAudit(item)"
                            >
                                {{ auditingId === item.dramaId ? '审核中…' : auditActionText(item) }}
                            </button>
                            <button
                                v-if="Number(item.reviewStatus) === 1"
                                class="btn btn-primary btn-small"
                                type="button"
                                @click="openDecision(item, 'APPROVE')"
                            >
                                通过
                            </button>
                            <button
                                v-if="Number(item.reviewStatus) === 1"
                                class="btn btn-danger btn-small"
                                type="button"
                                @click="openDecision(item, 'REJECT')"
                            >
                                驳回
                            </button>
                            <button
                                v-if="Number(item.reviewStatus) === 2"
                                class="btn btn-ghost btn-small"
                                type="button"
                                @click="openOffline(item)"
                            >
                                下架
                            </button>
                            <button
                                class="btn btn-ghost btn-small"
                                type="button"
                                @click="openDetail(item)"
                            >
                                AI 详情
                            </button>
                            <button
                                class="btn btn-ghost btn-small"
                                type="button"
                                @click="openLogs(item)"
                            >
                                日志
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

        <AppModal v-model="decisionOpen" size="lg" :title="decision === 'APPROVE' ? '通过审核' : '驳回短剧'">
            <form class="decision-form" @submit.prevent="confirmDecision">
                <p class="decision-title">
                    {{ decisionTarget?.title }}
                </p>
                <label class="field">
                    <span>审核意见{{ decision === 'REJECT' ? '（必填）' : '（可选）' }}</span>
                    <textarea
                        v-model="remark"
                        maxlength="500"
                        rows="4"
                        :placeholder="decision === 'APPROVE' ? '可补充人工复核说明' : '说明驳回原因'"
                    ></textarea>
                </label>
                <button class="btn btn-primary btn-block" type="submit" :disabled="!canSubmitDecision">
                    {{ decisionLoading ? '提交中…' : (decision === 'APPROVE' ? '确认通过并上架' : '确认驳回') }}
                </button>
            </form>
        </AppModal>

        <AppModal v-model="logsOpen" size="lg" title="审核日志">
            <div v-if="logsLoading" class="log-list">
                <SkeletonBlock v-for="index in 3" :key="index" height="54px" radius="10px"/>
            </div>
            <p v-else-if="!logs.length" class="log-empty">暂无审核日志</p>
            <div v-else class="log-list">
                <div v-for="log in logs" :key="log.id" class="log-row">
                    <div class="log-head">
                        <strong>{{ log.action }}</strong>
                        <span>{{ log.source === 'AI' ? 'AI' : '人工' }}</span>
                    </div>
                    <p>
                        状态 {{ log.fromStatus ?? '—' }} → {{ log.toStatus ?? '—' }}
                        <template v-if="log.operatorId">· 操作人 {{ log.operatorId }}</template>
                    </p>
                    <p v-if="log.remark">{{ log.remark }}</p>
                    <time>{{ formatDateTime(log.createTime) }}</time>
                </div>
            </div>
        </AppModal>

        <AppModal v-model="detailOpen" size="lg" title="AI 审核详情">
            <div v-if="detailTarget" class="ai-detail">
                <div class="detail-head">
                    <strong>{{ detailTarget.title }}</strong>
                    <span class="status-chip" :class="`status-${detailTarget.reviewStatus}`">
                        {{ aiLabel(detailTarget.aiReviewStatus) }}
                    </span>
                </div>
                <p class="detail-meta">
                    提交 {{ detailTarget.submitTime ? formatDateTime(detailTarget.submitTime) : '未提交' }}
                    · AI 审核
                    {{ detailTarget.aiReviewTime ? formatDateTime(detailTarget.aiReviewTime) : '未执行' }}
                </p>

                <p v-if="detailMalformed" class="detail-error">
                    AI 结果损坏，请重新审核。
                </p>

                <template v-else-if="detailData">
                    <section class="detail-section">
                        <h3>合规结论</h3>
                        <p v-if="detailData.verdict">
                            {{ detailData.verdict.is_violation ? '发现风险' : '未发现违规' }}
                            <template v-if="detailData.verdict.violation_reason">
                                ：{{ detailData.verdict.violation_reason }}
                            </template>
                        </p>
                        <p v-else>AI 未返回有效结论。</p>
                        <div v-if="detailData.verdict?.tags?.length" class="detail-tags">
                            <span
                                v-for="tag in detailData.verdict.tags"
                                :key="tag"
                                class="detail-tag"
                            >
                                {{ tag }}
                            </span>
                        </div>
                    </section>

                    <section v-if="detailAdvisory" class="detail-section">
                        <h3>情感与标签建议</h3>
                        <p>
                            情感：{{ sentimentText(detailAdvisory) }}
                            <span class="detail-support">
                                支持片段 {{ detailAdvisory.sentiment?.support ?? 0 }}
                            </span>
                            <span
                                v-if="detailAdvisory.sentiment?.highNegative"
                                class="detail-warn"
                            >
                                建议人工复核
                            </span>
                        </p>
                        <p v-if="tagLabels(detailAdvisory).length">
                            建议标签：{{ tagLabels(detailAdvisory).join('、') }}（括号内为命中片数，仅供参考）
                        </p>
                        <p>
                            分析片段 {{ detailAdvisory.chunks ?? '—' }}
                            · 失败 {{ detailAdvisory.failedChunks ?? 0 }}
                            <template v-if="detailAdvisory.truncated"> · 文本较长，仅分析前部</template>
                        </p>
                        <p v-if="detailAdvisory.degraded" class="detail-warn">
                            情感/标签建议降级：{{ aiFlagLabels(detailAdvisory.degradedFlags).join('、') || '未知原因' }}
                        </p>
                        <div v-if="advisoryEvidence(detailAdvisory).length" class="detail-evidence">
                            <h4>证据</h4>
                            <ul>
                                <li
                                    v-for="item in advisoryEvidence(detailAdvisory)"
                                    :key="item"
                                >
                                    {{ item }}
                                </li>
                            </ul>
                        </div>
                    </section>

                    <section class="detail-section">
                        <h3>运行信息</h3>
                        <dl class="detail-grid">
                            <div>
                                <dt>耗时</dt>
                                <dd>{{ detailData.elapsedMs ?? '—' }} ms</dd>
                            </div>
                            <div>
                                <dt>审核时间</dt>
                                <dd>{{ detailData.auditedAt ? formatDateTime(detailData.auditedAt) : '—' }}</dd>
                            </div>
                            <div>
                                <dt>降级标记</dt>
                                <dd>{{ detailFlags.join('、') || '无' }}</dd>
                            </div>
                            <div>
                                <dt>AI 状态</dt>
                                <dd>{{ aiLabel(detailTarget.aiReviewStatus) }}</dd>
                            </div>
                        </dl>
                    </section>

                    <p class="detail-hint">AI 仅供参考，人工审核决定最终状态。</p>

                    <AiFeedback
                        v-if="detailData.requestId"
                        scene="AUDIT"
                        :request-id="detailData.requestId"
                        :target-id="detailTarget.dramaId"
                    />

                    <details class="raw-json">
                        <summary>原始 JSON</summary>
                        <pre>{{ detailTarget.aiReviewResult }}</pre>
                    </details>
                </template>

                <p v-else class="detail-hint">尚未执行 AI 审核。</p>

                <div
                    v-if="Number(detailTarget.aiReviewStatus) === 4
                        && Number(detailTarget.reviewStatus) === 1"
                    class="detail-actions"
                >
                    <button
                        class="btn btn-primary btn-small"
                        type="button"
                        :disabled="auditingId === detailTarget.dramaId"
                        @click="runAudit(detailTarget)"
                    >
                        {{ auditingId === detailTarget.dramaId ? '审核中…' : '重新 AI 审核' }}
                    </button>
                </div>
            </div>
        </AppModal>

        <ConfirmDialog
            v-model="batchOpen"
            title="批量 AI 审核"
            message="将审核最多 5 条短剧。AI 只写 ai_review_* 建议，不会改变人工审核状态。"
            confirm-text="开始审核"
            :loading="batchLoading"
            @confirm="confirmBatch"
        />

        <AppModal v-model="batchResultOpen" size="lg" title="批量审核结果">
            <div v-if="batchResult" class="batch-result">
                <div class="result-grid">
                    <div><span>扫描</span><strong>{{ batchResult.scanned ?? 0 }}</strong></div>
                    <div><span>已审核</span><strong>{{ batchResult.audited ?? 0 }}</strong></div>
                    <div><span>通过</span><strong>{{ batchResult.passed ?? 0 }}</strong></div>
                    <div><span>驳回</span><strong>{{ batchResult.rejected ?? 0 }}</strong></div>
                    <div><span>失败</span><strong>{{ batchResult.failed ?? 0 }}</strong></div>
                    <div><span>跳过</span><strong>{{ batchResult.skipped ?? 0 }}</strong></div>
                </div>
                <p v-if="batchResult.degradedFlags?.length" class="result-warn">
                    降级标记：{{ aiFlagLabels(batchResult.degradedFlags).join('、') }}
                </p>
                <p class="detail-hint">AI 结果仅供参考，人工审核决定最终状态。</p>
            </div>
        </AppModal>

        <ConfirmDialog
            v-model="reindexOpen"
            title="重建 RAG 索引"
            message="将全量拉取已上架短剧并重写 Milvus 与 Elasticsearch 索引。重建期间检索可能只返回部分结果。"
            confirm-text="开始重建"
            :loading="reindexLoading"
            @confirm="rebuildIndex"
        />

        <AppModal v-model="reindexResultOpen" size="lg" title="索引重建结果">
            <div v-if="reindexResult" class="batch-result">
                <p
                    class="result-banner"
                    :class="reindexResult.partial ? 'is-warning' : 'is-ok'"
                >
                    {{ reindexResult.partial ? '索引只完成了一部分' : '索引重建完成' }}
                </p>
                <div class="result-grid">
                    <div><span>短剧</span><strong>{{ reindexResult.dramaCount ?? 0 }}</strong></div>
                    <div><span>分集</span><strong>{{ reindexResult.episodeCount ?? 0 }}</strong></div>
                    <div><span>Milvus</span><strong>{{ reindexResult.vectorUpserted ?? 0 }}</strong></div>
                    <div><span>ES/BM25</span><strong>{{ reindexResult.esIndexed ?? 0 }}</strong></div>
                    <div><span>清理旧向量</span><strong>{{ reindexResult.staleDeleted ?? 0 }}</strong></div>
                    <div><span>耗时</span><strong>{{ reindexResult.elapsedMs ?? 0 }} ms</strong></div>
                </div>
                <p class="detail-meta">
                    Milvus {{ reindexResult.vectorAvailable ? '可用' : '不可用' }}
                    · Elasticsearch {{ reindexResult.bm25Available ? '可用' : '不可用' }}
                </p>
                <div v-if="reindexResult.errors?.length" class="result-errors">
                    <h3>错误信息</h3>
                    <ul>
                        <li v-for="item in reindexResult.errors" :key="item">{{ item }}</li>
                    </ul>
                </div>
            </div>
        </AppModal>

        <ConfirmDialog
            v-model="offlineOpen"
            title="下架短剧"
            :message="offlineTarget ? `确认下架《${offlineTarget.title}》？下架后首页和详情不再可见。` : ''"
            confirm-text="下架"
            danger
            :loading="offlineLoading"
            @confirm="confirmOffline"
        />
    </div>
</template>

<style scoped>
.review-page {
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

.card-list {
    display: grid;
    gap: 12px;
}

.review-card {
    display: grid;
    gap: 9px;
    padding: 15px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--panel);
}

.review-head {
    display: flex;
    align-items: flex-start;
    gap: 10px;
}

.review-copy {
    min-width: 0;
    flex: 1;
}

.review-copy h2 {
    overflow: hidden;
    margin: 0 0 4px;
    font-size: 16px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.review-copy p {
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

.review-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    color: var(--muted-soft);
    font-size: 10px;
}

.ai-review {
    display: grid;
    min-width: 0;
    gap: 6px;
}

.ai-summary {
    margin: 0;
    padding: 8px 10px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: var(--surface-soft);
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.ai-summary.degraded {
    border-color: #f0ddb4;
    background: var(--warning-soft);
    color: var(--warning-ink);
}

.ai-advisory {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
}

.adv-chip {
    flex: none;
    padding: 2px 8px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--muted);
    font-size: 10px;
    line-height: 1.6;
}

.adv-chip.is-positive {
    border-color: #b9e6d0;
    color: var(--ok);
}

.adv-chip.is-negative {
    border-color: #f0c6c6;
    color: var(--danger);
}

.adv-chip.is-mixed {
    border-color: #f0ddb4;
    color: var(--warning-ink);
}

.adv-chip.is-danger {
    border-color: #f0c6c6;
    background: var(--danger-soft);
    color: var(--danger);
}

.adv-tags {
    min-width: 0;
    color: var(--muted);
    font-size: 10px;
    line-height: 1.6;
}

.adv-note {
    color: var(--muted-soft);
    font-size: 10px;
    line-height: 1.6;
}

.adv-note.is-warning {
    color: var(--warning-ink);
}

.review-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

.ai-filter-strip {
    padding-top: 2px;
}

.review-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    color: var(--muted);
    font-size: 11px;
}

.adv-link {
    padding: 2px 0;
    border: none;
    background: transparent;
    color: var(--brand-2);
    font-size: 10px;
    text-decoration: underline;
    text-underline-offset: 2px;
}

.ai-detail {
    display: grid;
    gap: 14px;
}

.detail-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
}

.detail-head strong {
    min-width: 0;
    overflow: hidden;
    font-size: 15px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.detail-meta {
    margin: -6px 0 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.detail-error {
    margin: 0;
    padding: 10px 12px;
    border: 1px solid #f0c6c6;
    border-radius: 10px;
    background: var(--danger-soft);
    color: var(--danger);
    font-size: 12px;
}

.detail-section {
    display: grid;
    gap: 6px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface-soft);
}

.detail-section h3 {
    margin: 0;
    color: var(--text);
    font-size: 12px;
}

.detail-section p {
    margin: 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.65;
}

.detail-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}

.detail-tag {
    padding: 2px 8px;
    border: 1px solid rgba(184, 58, 38, 0.3);
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-size: 10px;
}

.detail-support {
    margin-left: 6px;
    color: var(--muted-soft);
    font-size: 10px;
}

.detail-warn {
    margin-left: 6px;
    color: var(--danger);
    font-size: 10px;
}

.detail-evidence h4 {
    margin: 4px 0 2px;
    color: var(--text);
    font-size: 11px;
}

.detail-evidence ul,
.result-errors ul {
    display: grid;
    gap: 4px;
    margin: 0;
    padding-left: 18px;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.detail-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
    margin: 0;
}

.detail-grid div {
    display: grid;
    gap: 2px;
    padding: 8px 10px;
    border-radius: 9px;
    background: var(--surface-muted);
}

.detail-grid dt {
    color: var(--muted-soft);
    font-size: 10px;
}

.detail-grid dd {
    margin: 0;
    color: var(--text);
    font-size: 11px;
}

.detail-hint {
    margin: 0;
    color: var(--muted-soft);
    font-size: 10px;
    line-height: 1.6;
}

.raw-json summary {
    color: var(--muted);
    font-size: 11px;
    cursor: pointer;
}

.raw-json pre {
    overflow: auto;
    max-height: 220px;
    margin: 8px 0 0;
    padding: 10px;
    border-radius: 9px;
    background: rgba(0, 0, 0, 0.28);
    color: rgba(255, 255, 255, 0.62);
    font-size: 10px;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-all;
}

.detail-actions {
    display: flex;
    justify-content: flex-end;
}

.batch-result {
    display: grid;
    gap: 12px;
}

.result-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
}

.result-grid > div {
    display: grid;
    gap: 3px;
    padding: 10px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: var(--surface-soft);
}

.result-grid span {
    color: var(--muted);
    font-size: 10px;
}

.result-grid strong {
    color: var(--text);
    font-size: 16px;
}

.result-warn {
    margin: 0;
    color: var(--brand-2);
    font-size: 11px;
    line-height: 1.6;
}

.result-banner {
    margin: 0;
    padding: 9px 11px;
    border-radius: 9px;
    font-size: 12px;
    font-weight: 700;
}

.result-banner.is-ok {
    background: var(--ok-soft);
    color: var(--ok);
}

.result-banner.is-warning {
    background: var(--warning-soft);
    color: var(--warning-ink);
}

.result-errors h3 {
    margin: 0 0 4px;
    color: var(--danger);
    font-size: 11px;
}

.decision-form {
    display: grid;
    gap: 12px;
}

.decision-title {
    margin: 0;
    color: var(--text);
    font-size: 15px;
    font-weight: 700;
}

.log-list {
    display: grid;
    gap: 8px;
}

.log-row {
    display: grid;
    gap: 3px;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: var(--surface-soft);
}

.log-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    font-size: 11px;
}

.log-head strong {
    color: var(--brand-2);
}

.log-head span {
    color: var(--muted);
}

.log-row p {
    margin: 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.5;
}

.log-row time {
    color: var(--muted-soft);
    font-size: 10px;
}

.log-empty {
    margin: 0;
    color: var(--muted);
    font-size: 12px;
}

/* ---------- 桌面端（≥1024）：一行一条审核记录 ---------- */

@media (min-width: 1024px) {
    .review-page {
        gap: 14px;
    }

    .filter-strip {
        flex-wrap: wrap;
        overflow: visible;
    }

    .card-list {
        gap: 10px;
    }

    .review-card {
        grid-template-columns: minmax(260px, 300px) minmax(0, 1fr) auto;
        align-items: center;
        column-gap: 24px;
        row-gap: 6px;
        padding: 14px 20px;
    }

    .review-head {
        grid-column: 1;
        grid-row: 1;
        align-items: center;
    }

    .review-copy h2 {
        margin-bottom: 0;
        font-size: 15px;
    }

    .review-copy p {
        display: none;
    }

    .review-meta {
        grid-column: 1;
        grid-row: 2;
        flex-wrap: nowrap;
    }

    .ai-review {
        grid-column: 2;
        grid-row: 1 / span 2;
        align-self: center;
    }

    .review-actions {
        grid-column: 3;
        grid-row: 1 / span 2;
        flex-wrap: nowrap;
        justify-content: flex-end;
    }
}
</style>
