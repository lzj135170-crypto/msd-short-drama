<script setup>
import DramaInfoContent from './DramaInfoContent.vue'

/**
 * 桌面宽屏左侧信息栏，只展示短剧资料，不承载互动操作。
 */
defineProps({
    detail: {
        type: Object,
        required: true
    }
})
</script>

<template>
    <aside class="feed-info-panel" aria-label="短剧信息">
        <DramaInfoContent :detail="detail"/>
    </aside>
</template>

<style scoped>
.feed-info-panel {
    height: 100dvh;
    padding: 28px 22px;
    overflow-y: auto;
    border-right: 1px solid var(--line);
    background: #0b0e14;
    scrollbar-width: thin;
}
</style>
