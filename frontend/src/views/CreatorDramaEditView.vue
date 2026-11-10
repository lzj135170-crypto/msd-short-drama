<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
    createDrama,
    fetchMyDrama,
    importSubtitle,
    submitDrama,
    updateDrama
} from '../api/creator'
import { generateCopilot, precheckDrama } from '../api/content'
import { sendAiFeedback } from '../api/ai'
import { fetchHome } from '../api/drama'
import { useToastStore } from '../stores/toast'
import AiReviewNotice from '../components/AiReviewNotice.vue'
import ErrorState from '../components/ErrorState.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const route = useRoute()
const router = useRouter()
const toast = useToastStore()

const dramaId = computed(() => (route.params.id ? Number(route.params.id) : 0))
const isNew = computed(() => !dramaId.value)

const form = reactive({
    title: '',
    subtitle: '',
    coverUrl: '',
    description: '',
    categoryId: 0,
    actors: ''
})

const episodes = ref([emptyEpisode(1)])
const categories = ref([])
const reviewStatus = ref(0)
const aiReviewStatus = ref(0)
const aiReviewResult = ref('')
const loading = ref(!isNew.value)
const saving = ref(false)
const error = ref('')
const prechecking = ref(false)
const precheckResult = ref(null)
const precheckError = ref('')
const copilotGenerating = ref(false)
const copilotResult = ref(null)
const copilotError = ref('')
const copilotTone = ref('自动')
const copilotCount = ref(3)
const copilotAdopted = ref(false)
const copilotChecks = ref({})
const copilotChecking = ref(-1)
const subtitleTarget = ref(0)
const subtitleFormat = ref('SRT')
const subtitleContent = ref('')
const subtitleImporting = ref(false)

const canSave = computed(() => isNew.value || reviewStatus.value === 0 || reviewStatus.value === 3)
const precheckTone = computed(() => {
    const result = precheckResult.value
    if (!result) {
        return ''
    }
    if (result.degraded || !result.verdict) {
        return 'is-warning'
    }
    return result.verdict.is_violation ? 'is-danger' : 'is-ok'
})

/**
 * 生成版本合规检查的颜色。
 */
function copilotCheckTone(result) {
    if (!result || result.error || result.degraded || !result.verdict) {
        return 'is-warning'
    }
    return result.verdict.is_violation ? 'is-danger' : 'is-ok'
}

function emptyEpisode(episodeNo) {
    return {
        episodeId: 0,
        episodeNo,
        title: `第${episodeNo}集`,
        durationSeconds: 120,
        pricePoints: 0,
        status: 1
    }
}

/**
 * 读取本地 SRT/VTT 文件内容，不自动提交。
 */
async function onSubtitleFile(event) {
    const file = event.target.files?.[0]
    if (!file) {
        return
    }
    const name = file.name.toLowerCase()
    subtitleFormat.value = name.endsWith('.vtt') ? 'VTT' : 'SRT'
    subtitleContent.value = await file.text()
    event.target.value = ''
}

/**
 * 导入字幕；需要先保存草稿拿到 episodeId。
 */
async function uploadSubtitle() {
    if (subtitleImporting.value) {
        return
    }
    if (!dramaId.value || !subtitleTarget.value) {
        toast.push('请先保存草稿，再导入字幕')
        return
    }
    if (!subtitleContent.value.trim()) {
        toast.push('请选择 SRT 或 VTT 文件')
        return
    }
    subtitleImporting.value = true
    try {
        const result = await importSubtitle(dramaId.value, subtitleTarget.value, {
            language: 'zh',
            format: subtitleFormat.value,
            content: subtitleContent.value
        })
        toast.success(`字幕已导入，共 ${result?.segmentCount || 0} 段`)
        subtitleContent.value = ''
    } catch (cause) {
        toast.push(cause?.message || '字幕导入失败')
    } finally {
        subtitleImporting.value = false
    }
}

/**
 * 加载可见分类，供新建/编辑时选择。
 */
async function loadCategories() {
    try {
        const home = await fetchHome()
        categories.value = home?.categories || []
        if (!form.categoryId && categories.value.length) {
            form.categoryId = categories.value[0].id
        }
    } catch {
        categories.value = []
    }
}

/**
 * 编辑页回填短剧和剧集；新建页只加载分类。
 */
async function load() {
    if (isNew.value) {
        await loadCategories()
        return
    }
    loading.value = true
    error.value = ''
    try {
        const [detail] = await Promise.all([fetchMyDrama(dramaId.value), loadCategories()])
        const drama = detail?.drama
        if (!drama) {
            error.value = '短剧不存在或不可编辑'
            return
        }
        reviewStatus.value = Number(drama.reviewStatus)
        aiReviewStatus.value = Number(drama.aiReviewStatus) || 0
        aiReviewResult.value = drama.aiReviewResult || ''
        form.title = drama.title || ''
        form.subtitle = drama.subtitle || ''
        form.coverUrl = drama.coverUrl || ''
        form.description = drama.description || ''
        form.categoryId = Number(drama.categoryId) || form.categoryId
        form.actors = drama.actors || ''
        episodes.value = (detail.episodes || []).map((episode) => ({
            episodeId: Number(episode.episodeId) || 0,
            episodeNo: episode.episodeNo,
            title: episode.title || '',
            durationSeconds: Number(episode.durationSeconds) || 0,
            pricePoints: Number(episode.pricePoints) || 0,
            status: Number(episode.status)
        }))
        if (!episodes.value.length) {
            episodes.value = [emptyEpisode(1)]
        } else {
            subtitleTarget.value = Number(episodes.value[0].episodeId) || 0
        }
    } catch (cause) {
        error.value = cause?.message || '短剧加载失败'
    } finally {
        loading.value = false
    }
}

function addEpisode() {
    const maxNo = episodes.value.reduce(
        (max, episode) => Math.max(max, Number(episode.episodeNo) || 0), 0)
    episodes.value.push(emptyEpisode(maxNo + 1))
}

function removeEpisode(index) {
    if (episodes.value.length <= 1) {
        toast.push('至少保留一集')
        return
    }
    episodes.value.splice(index, 1)
}

/**
 * 校验表单并组装后端请求。
 */
function buildPayload() {
    const title = form.title.trim()
    if (!title) {
        toast.push('请填写短剧标题')
        return null
    }
    if (!Number(form.categoryId)) {
        toast.push('请选择分类')
        return null
    }
    if (!episodes.value.length) {
        toast.push('至少添加一集')
        return null
    }
    const seen = new Set()
    const payloadEpisodes = []
    for (let index = 0; index < episodes.value.length; index += 1) {
        const episode = episodes.value[index]
        const episodeNo = Number(episode.episodeNo)
        if (!Number.isFinite(episodeNo) || episodeNo < 1) {
            toast.push(`第 ${index + 1} 行集数不合法`)
            return null
        }
        if (seen.has(episodeNo)) {
            toast.push('集数不能重复')
            return null
        }
        seen.add(episodeNo)
        payloadEpisodes.push({
            episodeNo,
            title: String(episode.title || '').trim(),
            durationSeconds: Math.max(0, Number(episode.durationSeconds) || 0),
            pricePoints: Math.max(0, Number(episode.pricePoints) || 0),
            status: Number(episode.status) === 0 ? 0 : 1
        })
    }
    return {
        title,
        subtitle: form.subtitle.trim(),
        coverUrl: form.coverUrl.trim(),
        description: form.description.trim(),
        categoryId: Number(form.categoryId),
        actors: form.actors.trim(),
        episodes: payloadEpisodes
    }
}

/**
 * 提交前的 AI 预检：只给合规结论和文案建议，不写审核状态。
 */
async function runPrecheck() {
    if (prechecking.value) {
        return
    }
    const title = form.title.trim()
    if (!title) {
        toast.push('请先填写标题')
        return
    }
    prechecking.value = true
    precheckError.value = ''
    try {
        precheckResult.value = await precheckDrama({
            title,
            subtitle: form.subtitle.trim(),
            description: form.description.trim(),
            episodeTitles: episodes.value
                .map((episode) => String(episode.title || '').trim())
                .filter(Boolean)
        })
    } catch (cause) {
        precheckResult.value = null
        precheckError.value = cause?.message || 'AI 预检失败，请稍后再试'
    } finally {
        prechecking.value = false
    }
}

/**
 * 采纳标题建议，仍允许继续手动修改。
 */
function adoptTitle(value) {
    form.title = value
}

/**
 * 采纳副标题建议，仍允许继续手动修改。
 */
function adoptSubtitle(value) {
    form.subtitle = value
}

/**
 * 调用创作者 Copilot 生成多版本文案。
 */
async function runCopilot() {
    if (copilotGenerating.value) {
        return
    }
    const title = form.title.trim()
    const description = form.description.trim()
    if (!title && !description) {
        toast.push('请先填写标题或简介')
        return
    }
    copilotGenerating.value = true
    copilotError.value = ''
    try {
        copilotResult.value = await generateCopilot({
            title,
            subtitle: form.subtitle.trim(),
            description,
            actors: form.actors.trim(),
            episodeTitles: episodes.value
                .map((episode) => String(episode.title || '').trim())
                .filter(Boolean),
            tone: copilotTone.value === '自动' ? '' : copilotTone.value,
            count: Number(copilotCount.value) || 3
        })
        copilotAdopted.value = false
        copilotChecks.value = {}
    } catch (cause) {
        copilotResult.value = null
        copilotError.value = cause?.message || 'AI 创作助手暂时不可用'
    } finally {
        copilotGenerating.value = false
    }
}

/**
 * 采纳整套文案，仍允许继续手动修改。
 */
function adoptCopilotVersion(version) {
    form.title = version.title || form.title
    form.subtitle = version.subtitle || form.subtitle
    trackCopilotAdoption(version, '整套')
}

/**
 * 只采纳标题。
 */
function adoptCopilotTitle(version) {
    form.title = version.title || form.title
    trackCopilotAdoption(version, '标题')
}

/**
 * 只采纳副标题。
 */
function adoptCopilotSubtitle(version) {
    form.subtitle = version.subtitle || form.subtitle
    trackCopilotAdoption(version, '副标题')
}

/**
 * 首次采纳时上报一次反馈，形成采纳率指标。
 */
function trackCopilotAdoption(version, mode) {
    if (copilotAdopted.value || !copilotResult.value?.requestId) {
        return
    }
    copilotAdopted.value = true
    sendAiFeedback({
        scene: 'CREATOR_COPY',
        requestId: copilotResult.value.requestId,
        rating: 1,
        reason: 'ADOPT',
        comment: `采纳${mode}${version?.angle ? `：${version.angle}` : ''}`,
        targetId: dramaId.value || undefined
    }).catch(() => {
        // 反馈失败不影响创作者继续编辑
    })
}

/**
 * 对某个生成版本执行正式合规预检。
 */
async function checkCopilotVersion(version, index) {
    if (copilotChecking.value >= 0) {
        return
    }
    copilotChecking.value = index
    try {
        const result = await precheckDrama({
            title: version.title,
            subtitle: version.subtitle,
            description: form.description.trim(),
            episodeTitles: episodes.value
                .map((episode) => String(episode.title || '').trim())
                .filter(Boolean)
        })
        copilotChecks.value = { ...copilotChecks.value, [index]: result }
    } catch (cause) {
        copilotChecks.value = {
            ...copilotChecks.value,
            [index]: { error: cause?.message || '合规检查失败' }
        }
    } finally {
        copilotChecking.value = -1
    }
}

/**
 * 保存草稿，可选择保存后立即提交审核。
 */
async function save(submitAfter) {
    if (saving.value || !canSave.value) {
        return
    }
    const payload = buildPayload()
    if (!payload) {
        return
    }
    saving.value = true
    try {
        let targetId = dramaId.value
        if (isNew.value) {
            const detail = await createDrama(payload)
            targetId = detail?.drama?.dramaId || 0
        } else {
            await updateDrama(targetId, payload)
        }
        if (submitAfter && targetId) {
            await submitDrama(targetId)
            toast.success('已保存并提交审核')
        } else {
            toast.success('草稿已保存')
        }
        router.push('/creator/dramas')
    } catch (cause) {
        toast.push(cause?.message || '保存失败')
    } finally {
        saving.value = false
    }
}

load()
</script>

<template>
    <div class="sub-page">
        <PageHeader
            :title="isNew ? '新建短剧' : '编辑短剧'"
            back-to="/creator/dramas"
        />

        <main class="app-main edit-page is-narrow">
            <div v-if="loading" class="edit-skeleton">
                <SkeletonBlock height="180px" radius="16px"/>
                <SkeletonBlock height="220px" radius="16px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="load"/>

            <template v-else>
                <p v-if="!canSave" class="readonly-hint">
                    当前状态为{{ reviewStatus === 1 ? '审核中' : reviewStatus === 2 ? '已上架' : '已下架' }}，
                    只有草稿或驳回状态可以编辑。
                </p>

                <AiReviewNotice
                    :review-status="reviewStatus"
                    :ai-review-status="aiReviewStatus"
                    :ai-review-result="aiReviewResult"
                />

                <section class="form-card copilot-card">
                    <div class="section-title">
                        <div>
                            <h2>AI 创作助手</h2>
                            <p class="copilot-hint">
                                一次生成多个标题/副标题/标签方案，按角度对比后手动采纳。
                            </p>
                        </div>
                        <div class="copilot-controls">
                            <select v-model="copilotTone" aria-label="文案风格">
                                <option>自动</option>
                                <option>甜宠</option>
                                <option>悬疑</option>
                                <option>爽感</option>
                                <option>克制</option>
                            </select>
                            <select v-model.number="copilotCount" aria-label="生成版本数">
                                <option :value="2">2 版</option>
                                <option :value="3">3 版</option>
                            </select>
                            <button
                                class="btn btn-ghost btn-small"
                                type="button"
                                :disabled="copilotGenerating || !canSave"
                                @click="runCopilot"
                            >
                                {{ copilotGenerating ? '生成中…' : '生成文案' }}
                            </button>
                        </div>
                    </div>

                    <p v-if="copilotError" class="precheck-error">{{ copilotError }}</p>

                    <div v-if="copilotResult" class="copilot-result">
                        <p v-if="copilotResult.degraded" class="copilot-degraded">
                            AI 文案生成暂不可用，可以先手动填写，稍后再试。
                        </p>
                        <p v-else-if="!copilotResult.versions?.length" class="copilot-degraded">
                            这次没有生成可用方案，可以换一种风格再试。
                        </p>

                        <div v-if="copilotResult.versions?.length" class="copilot-grid">
                            <article
                                v-for="(version, index) in copilotResult.versions"
                                :key="`${version.title}-${index}`"
                                class="copilot-version"
                            >
                                <div class="copilot-version-head">
                                    <span class="copilot-angle">{{ version.angle || '文案方案' }}</span>
                                    <span class="copilot-index">方案 {{ index + 1 }}</span>
                                </div>
                                <h3>{{ version.title }}</h3>
                                <p class="copilot-subtitle">{{ version.subtitle }}</p>
                                <div v-if="version.tags?.length" class="copilot-tags">
                                    <span
                                        v-for="tag in version.tags"
                                        :key="`${index}-${tag}`"
                                        class="suggest-chip is-static"
                                    >
                                        {{ tag }}
                                    </span>
                                </div>
                                <p v-if="version.rationale" class="copilot-rationale">
                                    {{ version.rationale }}
                                </p>
                                <div class="copilot-actions">
                                    <button
                                        class="btn btn-ghost btn-small"
                                        type="button"
                                        @click="adoptCopilotVersion(version)"
                                    >
                                        采用整套
                                    </button>
                                    <button type="button" class="text-btn"
                                            @click="adoptCopilotTitle(version)">
                                        用标题
                                    </button>
                                    <button type="button" class="text-btn"
                                            @click="adoptCopilotSubtitle(version)">
                                        用副标题
                                    </button>
                                    <button
                                        type="button"
                                        class="text-btn"
                                        :disabled="copilotChecking >= 0"
                                        @click="checkCopilotVersion(version, index)"
                                    >
                                        {{ copilotChecking === index ? '检查中…' : '合规检查' }}
                                    </button>
                                </div>
                                <div
                                    v-if="copilotChecks[index]"
                                    class="copilot-check"
                                    :class="copilotCheckTone(copilotChecks[index])"
                                >
                                    <template v-if="copilotChecks[index].error">
                                        {{ copilotChecks[index].error }}
                                    </template>
                                    <template v-else-if="copilotChecks[index].degraded
                                        || !copilotChecks[index].verdict">
                                        合规检查未完成，请以人工审核为准。
                                    </template>
                                    <template v-else-if="copilotChecks[index].verdict.is_violation">
                                        发现风险：{{ copilotChecks[index].verdict.violation_reason
                                            || '请修改后再提交。' }}
                                    </template>
                                    <template v-else>未发现明显合规风险</template>
                                </div>
                            </article>
                        </div>

                        <ul v-if="copilotResult.tips?.length" class="copilot-tips">
                            <li v-for="tip in copilotResult.tips" :key="tip">{{ tip }}</li>
                        </ul>
                        <p class="copilot-footnote">
                            AI 生成仅供参考；采纳后仍可编辑，正式结论以 AI 预检和管理员审核为准。
                        </p>
                    </div>
                </section>

                <section class="form-card precheck-card">
                    <div class="section-title">
                        <div>
                            <h2>AI 预检</h2>
                            <p class="precheck-hint">
                                提交前检查合规风险，并获取文案建议；只作参考，不改变审核状态。
                            </p>
                        </div>
                        <button
                            class="btn btn-ghost btn-small"
                            type="button"
                            :disabled="prechecking || !canSave"
                            @click="runPrecheck"
                        >
                            {{ prechecking ? '预检中…' : 'AI 预检' }}
                        </button>
                    </div>

                    <p v-if="precheckError" class="precheck-error">{{ precheckError }}</p>

                    <div v-if="precheckResult" class="precheck-result" :class="precheckTone">
                        <strong v-if="precheckResult.degraded || !precheckResult.verdict">
                            AI 预检未完成
                        </strong>
                        <strong v-else-if="precheckResult.verdict.is_violation">
                            AI 预检发现风险
                        </strong>
                        <strong v-else>未发现明显合规风险</strong>

                        <p v-if="precheckResult.verdict?.is_violation">
                            {{ precheckResult.verdict.violation_reason || '请修改后再提交。' }}
                        </p>
                        <p v-if="precheckResult.verdict?.tags?.length" class="precheck-tags">
                            命中标签：{{ precheckResult.verdict.tags.join('、') }}
                        </p>
                        <p v-if="precheckResult.degraded" class="precheck-tags">
                            合规判断暂不可用，请以人工审核为准。
                        </p>
                        <p v-if="precheckResult.suggestionDegraded" class="precheck-tags">
                            文案建议暂不可用，可以稍后再试。
                        </p>

                        <div v-if="precheckResult.titleSuggestions?.length" class="suggest-group">
                            <span>标题建议</span>
                            <button
                                v-for="item in precheckResult.titleSuggestions"
                                :key="`title-${item}`"
                                class="suggest-chip"
                                type="button"
                                @click="adoptTitle(item)"
                            >
                                {{ item }}
                            </button>
                        </div>
                        <div v-if="precheckResult.subtitleSuggestions?.length" class="suggest-group">
                            <span>副标题建议</span>
                            <button
                                v-for="item in precheckResult.subtitleSuggestions"
                                :key="`subtitle-${item}`"
                                class="suggest-chip"
                                type="button"
                                @click="adoptSubtitle(item)"
                            >
                                {{ item }}
                            </button>
                        </div>
                        <div v-if="precheckResult.tagSuggestions?.length" class="suggest-group">
                            <span>标签建议（仅供参考）</span>
                            <span
                                v-for="tag in precheckResult.tagSuggestions"
                                :key="`tag-${tag}`"
                                class="suggest-chip is-static"
                            >
                                {{ tag }}
                            </span>
                        </div>
                        <ul v-if="precheckResult.tips?.length" class="precheck-tips">
                            <li v-for="tip in precheckResult.tips" :key="tip">{{ tip }}</li>
                        </ul>
                        <p class="precheck-footnote">AI 预检仅供参考，最终以管理员审核为准。</p>
                    </div>
                </section>

                <section class="form-card">
                    <h2>基础信息</h2>
                    <div class="field-grid">
                        <label class="field">
                            <span>标题</span>
                            <input v-model="form.title" maxlength="128" placeholder="短剧标题">
                        </label>
                        <label class="field">
                            <span>副标题</span>
                            <input v-model="form.subtitle" maxlength="255" placeholder="一句话简介">
                        </label>
                        <label class="field">
                            <span>封面地址</span>
                            <input v-model="form.coverUrl" maxlength="512" placeholder="留空使用渐变封面">
                        </label>
                        <label class="field">
                            <span>主演</span>
                            <input v-model="form.actors" maxlength="255" placeholder="逗号分隔">
                        </label>
                        <label class="field">
                            <span>分类</span>
                            <select v-if="categories.length" v-model.number="form.categoryId">
                                <option v-for="category in categories" :key="category.id" :value="category.id">
                                    {{ category.name }}
                                </option>
                            </select>
                            <input v-else v-model.number="form.categoryId" type="number" min="1">
                        </label>
                        <label class="field field-wide">
                            <span>简介</span>
                            <textarea v-model="form.description" maxlength="1000" rows="4"
                                      placeholder="剧情简介"></textarea>
                        </label>
                    </div>
                </section>

                <section class="form-card">
                    <div class="section-title">
                        <h2>剧集</h2>
                        <button class="text-btn" type="button" @click="addEpisode">添加剧集</button>
                    </div>

                    <div class="episode-list">
                        <div v-for="(episode, index) in episodes" :key="index" class="episode-row">
                            <div class="episode-head">
                                <strong>第 {{ index + 1 }} 行</strong>
                                <button
                                    class="text-btn danger"
                                    type="button"
                                    @click="removeEpisode(index)"
                                >
                                    移除
                                </button>
                            </div>
                            <div class="episode-fields">
                                <label class="field">
                                    <span>集数</span>
                                    <input v-model.number="episode.episodeNo" type="number" min="1">
                                </label>
                                <label class="field episode-title">
                                    <span>标题</span>
                                    <input v-model="episode.title" maxlength="128">
                                </label>
                                <label class="field">
                                    <span>时长（秒）</span>
                                    <input v-model.number="episode.durationSeconds" type="number" min="0">
                                </label>
                                <label class="field">
                                    <span>解锁积分</span>
                                    <input v-model.number="episode.pricePoints" type="number" min="0">
                                </label>
                                <label class="field">
                                    <span>状态</span>
                                    <select v-model.number="episode.status">
                                        <option :value="1">上线</option>
                                        <option :value="0">下线</option>
                                    </select>
                                </label>
                            </div>
                        </div>
                    </div>
                </section>

                <section class="form-card subtitle-card">
                    <div class="section-title">
                        <div>
                            <h2>字幕导入</h2>
                            <p class="subtitle-hint">
                                导入 SRT/VTT 后，字幕会带时间戳进入剧情问答；需先保存草稿。
                            </p>
                        </div>
                    </div>
                    <div class="subtitle-row">
                        <label class="field">
                            <span>剧集</span>
                            <select v-model.number="subtitleTarget">
                                <option value="0" disabled>请选择已保存剧集</option>
                                <option
                                    v-for="episode in episodes.filter((item) => item.episodeId)"
                                    :key="episode.episodeId"
                                    :value="episode.episodeId"
                                >
                                    第{{ episode.episodeNo }}集 {{ episode.title }}
                                </option>
                            </select>
                        </label>
                        <label class="field">
                            <span>字幕文件</span>
                            <input type="file" accept=".srt,.vtt" @change="onSubtitleFile">
                        </label>
                        <button
                            class="btn btn-ghost btn-small"
                            type="button"
                            :disabled="subtitleImporting"
                            @click="uploadSubtitle"
                        >
                            {{ subtitleImporting ? '导入中…' : '导入字幕' }}
                        </button>
                    </div>
                    <p v-if="subtitleContent" class="subtitle-selected">
                        已选择 {{ subtitleFormat }}，点击“导入字幕”写入。
                    </p>
                </section>

                <div class="edit-actions">
                    <button
                        class="btn btn-ghost btn-block"
                        type="button"
                        :disabled="saving || !canSave"
                        @click="save(false)"
                    >
                        {{ saving ? '保存中…' : '保存草稿' }}
                    </button>
                    <button
                        class="btn btn-primary btn-block"
                        type="button"
                        :disabled="saving || !canSave"
                        @click="save(true)"
                    >
                        {{ saving ? '提交中…' : '保存并提交审核' }}
                    </button>
                </div>
            </template>
        </main>
    </div>
</template>

<style scoped>
.edit-page {
    display: grid;
    gap: 14px;
}

.edit-skeleton {
    display: grid;
    gap: 12px;
}

.readonly-hint {
    margin: 0;
    padding: 10px 12px;
    border: 1px solid #f0ddb4;
    border-radius: 12px;
    background: var(--warning-soft);
    color: var(--warning-ink);
    font-size: 11px;
    line-height: 1.6;
}

.form-card {
    display: grid;
    gap: 12px;
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--panel);
    box-shadow: var(--shadow-soft);
}

.form-card h2 {
    margin: 0;
    font-size: 15px;
}

.field-wide {
    grid-column: 1 / -1;
}

.field select {
    height: 48px;
    padding: 0 12px;
    border: 1px solid var(--line-strong);
    border-radius: 12px;
    background-color: var(--surface);
    color: var(--text);
    font-size: 15px;
}

.episode-list {
    display: grid;
    gap: 10px;
}

.episode-row {
    display: grid;
    gap: 8px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface-soft);
}

.episode-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    color: var(--muted);
    font-size: 11px;
}

.episode-fields {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
}

.episode-title {
    grid-column: 1 / -1;
}

.text-btn {
    padding: 0;
    border: none;
    background: none;
    color: var(--brand-2);
    font-size: 11px;
}

.text-btn.danger {
    color: var(--danger);
}

.precheck-card .section-title {
    align-items: flex-start;
}

.precheck-hint {
    margin: 4px 0 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.precheck-error {
    margin: 0;
    color: var(--danger);
    font-size: 11px;
    line-height: 1.6;
}

.subtitle-hint {
    margin: 4px 0 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.subtitle-row {
    display: grid;
    gap: 10px;
}

.subtitle-row .btn {
    justify-self: start;
}

.subtitle-selected {
    margin: 0;
    color: var(--brand-2);
    font-size: 11px;
}

@media (min-width: 720px) {
    .subtitle-row {
        grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr) auto;
        align-items: end;
    }
}

.precheck-result {
    display: grid;
    gap: 6px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface-soft);
    color: var(--muted);
    font-size: 11px;
    line-height: 1.65;
}

.precheck-result strong {
    color: var(--text);
    font-size: 12px;
}

.precheck-result.is-ok {
    border-color: #b9e6d0;
    background: var(--ok-soft);
}

.precheck-result.is-ok strong {
    color: var(--ok);
}

.precheck-result.is-warning {
    border-color: #f0ddb4;
    background: var(--warning-soft);
}

.precheck-result.is-warning strong {
    color: var(--warning-ink);
}

.precheck-result.is-danger {
    border-color: #f0c6c6;
    background: var(--danger-soft);
}

.precheck-result.is-danger strong {
    color: var(--danger);
}

.precheck-result p {
    margin: 0;
}

.precheck-tags {
    color: var(--brand-2);
}

.suggest-group {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    padding-top: 4px;
}

.suggest-group > span:first-child {
    color: var(--muted);
}

.suggest-chip {
    padding: 4px 9px;
    border: 1px solid rgba(184, 58, 38, 0.3);
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-size: 11px;
    line-height: 1.4;
    text-align: left;
}

.suggest-chip.is-static {
    border-color: var(--line);
    background: var(--surface-muted);
    color: var(--text);
}

.precheck-tips {
    margin: 4px 0 0;
    padding-left: 18px;
    color: var(--muted);
}

.precheck-footnote {
    padding-top: 4px;
    color: var(--muted);
    opacity: 0.8;
}

.edit-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
}

@media (min-width: 560px) {
    .episode-fields {
        grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    .episode-title {
        grid-column: span 2;
    }
}

/* ---------- 桌面端（≥1024）：表单两列，剧集单行编辑 ---------- */

@media (min-width: 1024px) {
    .edit-page {
        gap: 18px;
    }

    .form-card {
        gap: 16px;
        padding: 22px 24px;
    }

    .form-card h2 {
        font-size: 16px;
    }

    .field-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 14px 16px;
    }

    .field-wide {
        grid-column: 1 / -1;
    }

    .episode-row {
        padding: 14px 16px;
    }

    .episode-fields {
        grid-template-columns: 88px minmax(0, 1fr) 118px 108px 96px;
        align-items: end;
    }

    .episode-title {
        grid-column: auto;
    }

    .episode-head {
        font-size: 12px;
    }

    .text-btn {
        font-size: 12px;
    }

    /* 保存操作固定在视口底部，长表单不用滚回底部 */
    .edit-actions {
        position: sticky;
        bottom: 0;
        z-index: 20;
        grid-template-columns: auto auto;
        justify-content: end;
        gap: 12px;
        padding: 14px 0;
        background: linear-gradient(180deg, transparent, var(--bg) 42%);
    }

    .edit-actions .btn {
        width: auto;
        min-width: 160px;
        padding: 0 22px;
    }
}

.copilot-card .section-title {
    align-items: flex-start;
    gap: 12px;
}

.copilot-hint {
    margin: 4px 0 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.copilot-controls {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    justify-content: flex-end;
}

.copilot-controls select {
    height: 32px;
    padding: 0 10px;
    border: 1px solid var(--line-strong);
    border-radius: 9px;
    background: var(--surface);
    color: var(--text);
    font-size: 12px;
}

.copilot-result {
    display: grid;
    gap: 10px;
}

.copilot-degraded {
    margin: 0;
    padding: 10px 12px;
    border: 1px solid #f0ddb4;
    border-radius: 10px;
    background: var(--warning-soft);
    color: var(--warning-ink);
    font-size: 12px;
}

.copilot-grid {
    display: grid;
    gap: 12px;
}

.copilot-version {
    display: flex;
    flex-direction: column;
    gap: 7px;
    padding: 14px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--surface-soft);
}

.copilot-version-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
}

.copilot-angle {
    padding: 3px 8px;
    border-radius: 999px;
    background: var(--warning-soft);
    color: var(--warning-ink);
    font-size: 11px;
}

.copilot-index {
    color: var(--muted);
    font-size: 10px;
}

.copilot-version h3 {
    margin: 0;
    font-size: 16px;
    line-height: 1.4;
}

.copilot-subtitle {
    margin: 0;
    color: var(--muted);
    font-size: 12px;
    line-height: 1.5;
}

.copilot-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}

.copilot-rationale {
    margin: 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.copilot-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    margin-top: auto;
    padding-top: 4px;
}

.copilot-check {
    padding: 8px 10px;
    border: 1px solid var(--line);
    border-radius: 10px;
    font-size: 11px;
    line-height: 1.6;
}

.copilot-check.is-ok {
    border-color: #b9e6d0;
    background: var(--ok-soft);
    color: var(--ok);
}

.copilot-check.is-warning {
    border-color: #f0ddb4;
    background: var(--warning-soft);
    color: var(--warning-ink);
}

.copilot-check.is-danger {
    border-color: #f0c6c6;
    background: var(--danger-soft);
    color: var(--danger);
}

.copilot-tips {
    margin: 0;
    padding-left: 18px;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.7;
}

.copilot-footnote {
    margin: 0;
    color: var(--muted);
    font-size: 10px;
    line-height: 1.6;
}

@media (min-width: 720px) {
    .copilot-grid {
        grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    }
}
</style>
