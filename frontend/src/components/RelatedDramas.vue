<script setup>
import { ref, watch } from 'vue'
import { fetchRelatedDramas } from '../api/content'
import DramaCard from './DramaCard.vue'
import SkeletonBlock from './SkeletonBlock.vue'

const props = defineProps({
    dramaId: {
        type: [String, Number],
        required: true
    },
    limit: {
        type: Number,
        default: 6
    },
    title: {
        type: String,
        default: '你可能还喜欢'
    }
})

const loading = ref(false)
const items = ref([])
const degraded = ref(false)
let loadToken = 0

/**
 * 加载相似短剧；失败时静默隐藏，不影响主内容。
 */
async function load() {
    if (!props.dramaId) {
        return
    }
    const token = ++loadToken
    loading.value = true
    try {
        const data = await fetchRelatedDramas(props.dramaId, props.limit)
        if (token === loadToken) {
            items.value = Array.isArray(data?.items) ? data.items : []
            degraded.value = Boolean(data?.degraded)
        }
    } catch {
        if (token === loadToken) {
            items.value = []
            degraded.value = false
        }
    } finally {
        if (token === loadToken) {
            loading.value = false
        }
    }
}

watch(() => [props.dramaId, props.limit], load, { immediate: true })
</script>

<template>
    <section v-if="loading || items.length" class="related-dramas" aria-label="相似短剧">
        <div class="related-head">
            <h4>{{ title }}</h4>
            <span v-if="degraded">规则推荐</span>
        </div>

        <div v-if="loading" class="related-scroll">
            <SkeletonBlock v-for="index in 3" :key="index" width="136px" height="204px"
                           radius="12px"/>
        </div>
        <div v-else class="related-scroll">
            <DramaCard
                v-for="item in items"
                :key="item.dramaId"
                :drama="item"
                :reason="item.reason"
            />
        </div>
    </section>
</template>

<style scoped>
.related-dramas {
    display: grid;
    gap: 9px;
    min-width: 0;
}

.related-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
}

.related-head h4 {
    font-size: 13px;
    font-weight: 700;
}

.related-head span {
    color: var(--muted);
    font-size: 9.5px;
}

.related-scroll {
    display: flex;
    gap: 10px;
    margin: 0 -2px;
    padding: 0 2px 2px;
    overflow-x: auto;
    scrollbar-width: thin;
}

.related-scroll :deep(.drama-card) {
    width: 120px;
}

@media (min-width: 1024px) {
    .related-scroll :deep(.drama-card) {
        width: 136px;
    }
}
</style>
