<script setup>
import { computed, ref, watch } from 'vue'
import { fetchRecap } from '../../api/companion'
import { aiFlagLabels } from '../../utils/aiFlags'
import SkeletonBlock from '../SkeletonBlock.vue'

const props = defineProps({
    dramaId: {
        type: [String, Number],
        required: true
    },
    episodeNo: {
        type: [String, Number],
        default: 0
    }
})

const emit = defineEmits(['select'])

const loading = ref(false)
const error = ref('')
const data = ref(null)
let requestToken = 0

const flags = computed(() => aiFlagLabels(data.value?.degradedFlags))
const hasContent = computed(() => Boolean(
    data.value?.current || data.value?.previous?.length
))
const locked = computed(() => (data.value?.degradedFlags || []).includes('locked'))

/**
 * 加载当前集及之前的前情。
 */
async function load() {
    const episodeNo = Number(props.episodeNo)
    const token = ++requestToken
    if (!Number.isFinite(episodeNo) || episodeNo <= 0) {
        data.value = null
        loading.value = false
        return
    }
    loading.value = true
    error.value = ''
    try {
        const result = await fetchRecap({
            dramaId: props.dramaId,
            episodeNo
        })
        if (token === requestToken) {
            data.value = result
        }
    } catch (cause) {
        if (token === requestToken) {
            data.value = null
            error.value = cause?.message || '前情加载失败'
        }
    } finally {
        if (token === requestToken) {
            loading.value = false
        }
    }
}

watch(() => [props.dramaId, props.episodeNo], load, { immediate: true })
</script>

<template>
    <div class="episode-recap">
        <div class="recap-head">
            <div>
                <h3>前情提要</h3>
                <p>只包含当前集及之前的内容，不含后续剧情。</p>
            </div>
            <span class="recap-badge">AI 摘要</span>
        </div>

        <div v-if="loading" class="recap-skeleton">
            <SkeletonBlock height="76px" radius="10px"/>
            <SkeletonBlock height="58px" radius="10px"/>
        </div>

        <p v-else-if="error" class="recap-error">{{ error }}</p>

        <template v-else-if="data">
            <p v-if="locked" class="recap-note">
                本集尚未解锁，解锁后可查看本集看点。
            </p>

            <article v-if="data.current" class="recap-current">
                <span class="recap-label">本集</span>
                <h4>{{ data.current.title || `第${data.current.episodeNo}集` }}</h4>
                <p>{{ data.current.summary || '本集暂无摘要，先看正片吧。' }}</p>
            </article>

            <section v-if="data.previous?.length" class="recap-previous">
                <h4>前情回顾</h4>
                <button
                    v-for="item in data.previous"
                    :key="item.episodeId"
                    class="recap-item"
                    type="button"
                    @click="emit('select', item.episodeId)"
                >
                    <span class="recap-label">第{{ item.episodeNo }}集</span>
                    <span class="recap-item-copy">
                        <strong>{{ item.title || `第${item.episodeNo}集` }}</strong>
                        <small>{{ item.summary }}</small>
                    </span>
                </button>
            </section>

            <p v-if="!hasContent && !locked" class="recap-empty">
                暂无可用摘要，可以先看正片，AI 会在生成后补上。
            </p>

            <p v-if="data.degraded" class="recap-note">
                部分资料不可用{{ flags.length ? `：${flags.join('、')}` : '' }}
            </p>
        </template>
    </div>
</template>

<style scoped>
.episode-recap {
    display: grid;
    gap: 12px;
    min-height: 0;
}

.recap-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 10px;
}

.recap-head h3 {
    font-size: 15px;
    font-weight: 800;
}

.recap-head p {
    margin-top: 3px;
    color: var(--muted);
    font-size: 10.5px;
    line-height: 1.5;
}

.recap-badge,
.recap-label {
    flex: none;
    padding: 2px 7px;
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-size: 9.5px;
    font-weight: 700;
}

.recap-skeleton {
    display: grid;
    gap: 8px;
}

.recap-current {
    display: grid;
    gap: 5px;
    padding: 12px;
    border: 1px solid #f0ddb4;
    border-radius: 11px;
    background: var(--warning-soft);
}

.recap-current h4 {
    font-size: 12.5px;
    font-weight: 700;
}

.recap-current p {
    color: var(--text-soft);
    font-size: 12px;
    line-height: 1.7;
}

.recap-previous {
    display: grid;
    gap: 7px;
}

.recap-previous h4 {
    color: var(--muted);
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.1em;
    text-transform: uppercase;
}

.recap-item {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    width: 100%;
    padding: 9px 10px;
    border: 1px solid var(--line);
    border-radius: 9px;
    background: var(--surface-soft);
    color: var(--text);
    text-align: left;
}

.recap-item:hover {
    border-color: rgba(184, 58, 38, 0.35);
}

.recap-item-copy {
    display: grid;
    min-width: 0;
    gap: 3px;
}

.recap-item-copy strong {
    font-size: 11.5px;
}

.recap-item-copy small {
    display: -webkit-box;
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
}

.recap-note,
.recap-empty,
.recap-error {
    padding: 8px 10px;
    border-radius: 8px;
    background: var(--surface-muted);
    color: var(--muted);
    font-size: 10.5px;
    line-height: 1.55;
}

.recap-error {
    color: var(--danger);
}
</style>
