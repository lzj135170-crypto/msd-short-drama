<script setup>
import GradientCover from './GradientCover.vue'

defineProps({
    source: {
        type: Object,
        required: true
    }
})
</script>

<template>
    <RouterLink
        class="ai-source-card"
        :to="`/drama/${source.dramaId}`"
        :aria-label="`查看《${source.title || '短剧'}》详情`"
    >
        <GradientCover
            :seed="source.dramaId"
            :title="source.title"
            :src="source.coverUrl"
            ratio="poster"
        />
        <div class="ai-source-copy">
            <h3>{{ source.title || '未命名短剧' }}</h3>
            <p>{{ source.subtitle || source.snippet || '查看详情' }}</p>
            <span v-if="source.snippet" class="ai-source-snippet">{{ source.snippet }}</span>
        </div>
    </RouterLink>
</template>

<style scoped>
.ai-source-card {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-width: 0;
    color: var(--text);
}

.ai-source-copy {
    display: grid;
    gap: 4px;
    min-width: 0;
}

.ai-source-copy h3 {
    display: -webkit-box;
    overflow: hidden;
    font-size: 14px;
    line-height: 1.45;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}

.ai-source-copy p {
    display: -webkit-box;
    overflow: hidden;
    margin: 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.5;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}

.ai-source-snippet {
    display: -webkit-box;
    overflow: hidden;
    color: var(--text-soft);
    font-size: 11px;
    line-height: 1.55;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
}
</style>
