<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
    name: {
        type: String,
        default: '剧'
    },
    size: {
        type: Number,
        default: 40
    },
    src: {
        type: String,
        default: ''
    }
})

const failed = ref(false)

watch(() => props.src, () => {
    failed.value = false
})

const initial = computed(() => (props.name || '剧').trim().slice(0, 1) || '剧')

const hue = computed(() => {
    const code = (props.name || '剧').codePointAt(0)
    return Number.isFinite(code) ? code % 360 : 12
})

const sizeStyle = computed(() => ({
    width: `${props.size}px`,
    height: `${props.size}px`,
    fontSize: `${Math.max(12, Math.round(props.size * 0.42))}px`
}))

const style = computed(() => ({
    ...sizeStyle.value,
    background: `linear-gradient(135deg, hsl(${hue.value} 72% 54%), hsl(${(hue.value + 38) % 360} 76% 40%))`
}))
</script>

<template>
    <img
        v-if="src && !failed"
        class="avatar avatar-image"
        :src="src"
        :alt="name"
        :style="sizeStyle"
        @error="failed = true"
    >
    <span v-else class="avatar" :style="style" aria-hidden="true">{{ initial }}</span>
</template>

<style scoped>
.avatar-image {
    object-fit: cover;
}

.avatar-image.avatar {
    display: inline-block;
}
</style>
