<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import DramaFeed from '../components/feed/DramaFeed.vue'

defineProps({
    dramaId: {
        type: String,
        required: true
    }
})

const route = useRoute()

const initialEpisodeId = computed(() => {
    const value = route.query.episode
    return Array.isArray(value) ? value[0] : value || null
})

const initialStartMs = computed(() => {
    const value = route.query.start
    const raw = Array.isArray(value) ? value[0] : value
    return Number(raw) || 0
})
</script>

<template>
    <DramaFeed
        :drama-id="dramaId"
        :initial-episode-id="initialEpisodeId"
        :initial-start-ms="initialStartMs"
    />
</template>
