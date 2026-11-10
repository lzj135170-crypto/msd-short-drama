<script setup>
import { computed } from 'vue'
import GradientCover from './GradientCover.vue'
import { formatCount } from '../utils/format'

const props = defineProps({
    drama: {
        type: Object,
        required: true
    },
    // 首页「为你推荐」使用的推荐理由，其他页面不传
    reason: {
        type: String,
        default: ''
    },
    // 推荐归因上下文，携带时点击/播放可回溯到推荐请求
    recommendation: {
        type: Object,
        default: null
    }
})

const target = computed(() => {
    const rec = props.recommendation
    return {
        path: `/drama/${props.drama.dramaId}`,
        query: rec?.requestId
            ? {
                rec_request: rec.requestId,
                rec_drama: props.drama.dramaId,
                rec_pos: rec.position || 0
            }
            : {}
    }
})
</script>

<template>
    <RouterLink class="drama-card" :to="target">
        <GradientCover :seed="drama.dramaId" :title="drama.title" :src="drama.coverUrl" ratio="poster">
            <span v-if="drama.categoryName" class="card-chip">{{ drama.categoryName }}</span>
            <span class="card-episodes">{{ drama.episodeCount }} 集</span>
        </GradientCover>
        <div class="card-copy">
            <h3>{{ drama.title || '未命名短剧' }}</h3>
            <p>{{ formatCount(drama.viewCount) }} 播放 · {{ formatCount(drama.likeCount) }} 赞</p>
            <p v-if="reason" class="card-reason">{{ reason }}</p>
        </div>
    </RouterLink>
</template>

<style scoped>
.drama-card {
    display: flex;
    flex: none;
    flex-direction: column;
    gap: 8px;
    width: 136px;
    color: var(--text);
}

.card-chip,
.card-episodes {
    position: absolute;
    z-index: 1;
    padding: 3px 7px;
    border-radius: 7px;
    background: rgba(0, 0, 0, 0.42);
    color: rgba(255, 255, 255, 0.9);
    font-size: 10px;
    line-height: 1.4;
    backdrop-filter: blur(6px);
}

.card-chip {
    top: 8px;
    left: 8px;
}

.card-episodes {
    right: 8px;
    bottom: 8px;
}

.card-copy {
    display: grid;
    gap: 3px;
}

.card-copy h3 {
    display: -webkit-box;
    overflow: hidden;
    font-size: 13px;
    font-weight: 700;
    line-height: 1.4;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}

.card-copy p {
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.card-copy .card-reason {
    display: -webkit-box;
    overflow: hidden;
    color: var(--brand-2);
    font-size: 10px;
    line-height: 1.45;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    white-space: normal;
}
</style>
