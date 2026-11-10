<script setup>
const props = defineProps({
    episodes: {
        type: Array,
        default: () => []
    },
    currentId: {
        type: Number,
        default: 0
    },
    unlockedIds: {
        type: Set,
        default: () => new Set()
    },
    // rail 为移动端横向条，panel 为桌面侧栏纵向列表
    variant: {
        type: String,
        default: 'rail'
    }
})

defineEmits(['select'])

/**
 * 标题本身常以“第N集”开头，和左侧集号重复时去掉前缀。
 */
function displayName(episode) {
    const title = String(episode.title || '').trim()
    const prefix = `第${episode.episodeNo}集`
    if (title.startsWith(prefix)) {
        return title.slice(prefix.length).trim() || prefix
    }
    return title || prefix
}

/**
 * 剧集角标：免费 / 已解锁 / 积分解锁。
 */
function badge(episode) {
    if (episode.free) {
        return '免费'
    }
    if (props.unlockedIds.has(episode.episodeId)) {
        return '已解锁'
    }
    return `${episode.pricePoints} 积分`
}
</script>

<template>
    <div v-if="episodes.length" class="episode-rail" :class="`is-${variant}`" aria-label="选集">
        <button
            v-for="episode in episodes"
            :key="episode.episodeId"
            class="rail-item"
            :class="{
                'is-active': episode.episodeId === currentId,
                'is-locked': !episode.free && !unlockedIds.has(episode.episodeId)
            }"
            type="button"
            @click="$emit('select', episode.episodeId)"
        >
            <span class="rail-no">第{{ episode.episodeNo }}集</span>
            <span class="rail-name">{{ displayName(episode) }}</span>
            <span class="rail-badge">{{ badge(episode) }}</span>
        </button>
    </div>
</template>

<style scoped>
.episode-rail {
    display: flex;
    gap: 8px;
    margin: 0 -16px;
    padding: 0 16px 2px;
    overflow-x: auto;
    scrollbar-width: none;
}

.episode-rail::-webkit-scrollbar {
    display: none;
}

.rail-item {
    display: grid;
    flex: none;
    gap: 3px;
    width: 94px;
    padding: 8px 9px;
    border: 1px solid var(--line);
    border-radius: 11px;
    background: var(--surface-soft);
    color: var(--text);
    text-align: left;
}

.rail-item.is-active {
    border-color: rgba(184, 58, 38, 0.4);
    background: var(--brand-soft);
}

.rail-item.is-locked {
    color: var(--muted);
}

.rail-no {
    color: var(--brand-2);
    font-size: 9px;
    font-weight: 800;
    letter-spacing: 0.06em;
}

.rail-name {
    overflow: hidden;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.rail-badge {
    color: var(--muted);
    font-size: 9px;
}

.rail-item.is-active .rail-badge {
    color: var(--brand-2);
}

/* ---------- 桌面侧栏纵向列表 ---------- */

.episode-rail.is-panel {
    display: grid;
    gap: 6px;
    margin: 0;
    padding: 0;
    overflow: visible;
}

.is-panel .rail-item {
    grid-template-columns: 46px minmax(0, 1fr) auto;
    align-items: center;
    width: 100%;
    gap: 9px;
    padding: 9px 11px;
}

.is-panel .rail-name {
    font-size: 12px;
}

.is-panel .rail-badge {
    font-size: 10px;
}
</style>
