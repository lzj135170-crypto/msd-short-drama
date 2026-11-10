<script setup>
import { formatCount } from '../../utils/format'
import GradientCover from '../GradientCover.vue'
import RelatedDramas from '../RelatedDramas.vue'

/**
 * 短剧信息正文：移动端弹层和桌面侧栏共用同一份结构。
 */
defineProps({
    detail: {
        type: Object,
        required: true
    },
    // 桌面右栏已有剧名头部，隐藏重复的封面与标题
    showHead: {
        type: Boolean,
        default: true
    }
})
</script>

<template>
    <div class="info-content">
        <div v-if="showHead" class="info-head">
            <div class="info-cover">
                <GradientCover :seed="detail.id" :title="detail.title" :src="detail.coverUrl" ratio="thumb"/>
            </div>
            <div class="info-copy">
                <h3>{{ detail.title }}</h3>
                <p v-if="detail.subtitle">{{ detail.subtitle }}</p>
                <span v-if="detail.category?.name" class="chip">{{ detail.category.name }}</span>
            </div>
        </div>

        <div class="info-stats">
            <span><strong>{{ formatCount(detail.counters?.viewCount) }}</strong> 播放</span>
            <span><strong>{{ formatCount(detail.counters?.likeCount) }}</strong> 点赞</span>
            <span><strong>{{ formatCount(detail.counters?.favoriteCount) }}</strong> 收藏</span>
            <span><strong>{{ formatCount(detail.counters?.commentCount) }}</strong> 评论</span>
        </div>

        <p v-if="detail.actors?.length" class="info-actors">
            主演：{{ detail.actors.join(' / ') }}
        </p>

        <section class="info-desc">
            <h4>剧情简介</h4>
            <p>{{ detail.description || '暂无简介' }}</p>
        </section>

        <div v-if="detail.tags?.length" class="info-tags">
            <span v-for="tag in detail.tags" :key="tag.id" class="chip">{{ tag.name }}</span>
        </div>

        <RelatedDramas :drama-id="detail.id" :limit="6"/>
    </div>
</template>

<style scoped>
.info-content {
    display: grid;
    gap: 14px;
}

.info-head {
    display: flex;
    gap: 12px;
}

.info-cover {
    flex: none;
    width: 88px;
    overflow: hidden;
    border-radius: 12px;
}

.info-copy {
    display: grid;
    min-width: 0;
    align-content: start;
    gap: 6px;
}

.info-copy h3 {
    font-size: 18px;
    font-weight: 800;
    line-height: 1.35;
}

.info-copy p {
    color: var(--muted);
    font-size: 12px;
    line-height: 1.6;
}

.info-copy .chip {
    justify-self: start;
    height: 26px;
    padding: 0 9px;
    font-size: 10px;
}

.info-stats {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 16px;
    color: var(--muted);
    font-size: 11px;
}

.info-stats strong {
    color: var(--text);
    font-size: 13px;
}

.info-actors {
    color: var(--muted);
    font-size: 12px;
    line-height: 1.6;
}

.info-desc {
    display: grid;
    gap: 6px;
}

.info-desc h4 {
    font-size: 13px;
    font-weight: 700;
}

.info-desc p {
    color: var(--text-soft);
    font-size: 13px;
    line-height: 1.8;
    white-space: pre-wrap;
}

.info-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 7px;
}

.info-tags .chip {
    height: 28px;
    padding: 0 10px;
    font-size: 10px;
}
</style>
