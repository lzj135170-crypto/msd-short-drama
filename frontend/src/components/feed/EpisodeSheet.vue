<script setup>
import AppModal from '../AppModal.vue'

const props = defineProps({
    modelValue: {
        type: Boolean,
        default: false
    },
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
    }
})

const emit = defineEmits(['update:modelValue', 'select'])

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
    <AppModal
        :model-value="modelValue"
        title="选集"
        @update:model-value="emit('update:modelValue', $event)"
    >
        <div class="episode-grid">
            <button
                v-for="episode in episodes"
                :key="episode.episodeId"
                class="episode-tile"
                :class="{
                    'is-active': episode.episodeId === currentId,
                    'is-locked': !episode.free && !unlockedIds.has(episode.episodeId)
                }"
                type="button"
                @click="emit('select', episode.episodeId)"
            >
                <span class="episode-no">第{{ episode.episodeNo }}集</span>
                <span class="episode-name">{{ episode.title }}</span>
                <span
                    class="episode-badge"
                    :class="{
                        'is-free': episode.free,
                        'is-unlocked': !episode.free && unlockedIds.has(episode.episodeId),
                        'is-price': !episode.free && !unlockedIds.has(episode.episodeId)
                    }"
                >
                    {{ badge(episode) }}
                </span>
            </button>
        </div>
    </AppModal>
</template>

<style scoped>
.episode-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    padding-bottom: 4px;
}

.episode-tile {
    display: grid;
    gap: 4px;
    padding: 10px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface-soft);
    color: var(--text);
    text-align: left;
}

.episode-tile.is-active {
    border-color: rgba(184, 58, 38, 0.4);
    background: var(--brand-soft);
}

.episode-tile.is-locked {
    color: var(--muted);
}

.episode-no {
    color: var(--brand-2);
    font-size: 10px;
    font-weight: 800;
    letter-spacing: 0.06em;
}

.episode-name {
    overflow: hidden;
    font-size: 12px;
    line-height: 1.4;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.episode-badge {
    width: fit-content;
    padding: 2px 6px;
    border-radius: 999px;
    background: var(--surface-muted);
    color: var(--muted);
    font-size: 9px;
}

.episode-badge.is-free {
    background: var(--ok-soft);
    color: var(--ok);
}

.episode-badge.is-unlocked {
    background: var(--brand-soft);
    color: var(--brand-ink);
}

.episode-badge.is-price {
    background: var(--surface-soft);
    color: var(--muted);
}
</style>
