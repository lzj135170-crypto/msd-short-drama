<script setup>
import { computed, ref, watch } from 'vue'
import { coverPalette } from '../utils/gradient'

const props = defineProps({
    seed: {
        type: [String, Number],
        default: ''
    },
    title: {
        type: String,
        default: ''
    },
    // 有真实封面时优先显示；为空或加载失败回退渐变
    src: {
        type: String,
        default: ''
    },
    ratio: {
        type: String,
        default: 'poster'
    },
    rounded: {
        type: Boolean,
        default: true
    },
    showMark: {
        type: Boolean,
        default: true
    }
})

const palette = computed(() => coverPalette(props.seed || props.title || 'msd'))

const style = computed(() => ({
    '--cover-angle': `${palette.value.angle}deg`,
    '--cover-from': palette.value.from,
    '--cover-via': palette.value.via,
    '--cover-to': palette.value.to,
    '--cover-glow': palette.value.glow,
    '--cover-mark': palette.value.mark
}))

const mark = computed(() => (props.title || '').trim().slice(0, 1) || '剧')

// 1. 封面地址变化时重新尝试加载
const imageFailed = ref(false)
watch(() => props.src, () => {
    imageFailed.value = false
})

const showImage = computed(() => Boolean(props.src) && !imageFailed.value)
</script>

<template>
    <div
        class="cover"
        :class="[`ratio-${ratio}`, { 'is-rounded': rounded }]"
        :style="style"
        role="img"
        :aria-label="title || '短剧封面'"
    >
        <img
            v-if="showImage"
            class="cover-image"
            :src="src"
            :alt="title || '短剧封面'"
            loading="lazy"
            decoding="async"
            @error="imageFailed = true"
        >
        <template v-else>
            <span class="cover-glow" aria-hidden="true"></span>
            <span class="cover-lines" aria-hidden="true"></span>
            <svg v-if="showMark" class="cover-mark" viewBox="0 0 100 100" aria-hidden="true">
                <text x="100" y="96" text-anchor="end" font-size="96" font-weight="900"
                      fill="var(--cover-mark)">{{ mark }}</text>
            </svg>
        </template>
        <slot/>
    </div>
</template>

<style scoped>
.cover {
    position: relative;
    overflow: hidden;
    width: 100%;
    background: linear-gradient(var(--cover-angle), var(--cover-from), var(--cover-via) 55%, var(--cover-to));
    isolation: isolate;
}

.cover.is-rounded {
    border-radius: 14px;
}

.cover-image {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
}

.ratio-poster {
    aspect-ratio: 3 / 4;
}

.ratio-wide {
    aspect-ratio: 16 / 9;
}

.ratio-square {
    aspect-ratio: 1;
}

.ratio-thumb {
    aspect-ratio: 4 / 3;
}

.cover-glow {
    position: absolute;
    top: -28%;
    right: -22%;
    width: 72%;
    aspect-ratio: 1;
    border-radius: 50%;
    background: radial-gradient(circle, var(--cover-glow), transparent 66%);
    pointer-events: none;
}

.cover-lines {
    position: absolute;
    inset: 0;
    background-image: repeating-linear-gradient(
        115deg,
        rgba(255, 255, 255, 0.05) 0 1px,
        transparent 1px 14px
    );
    mix-blend-mode: soft-light;
    pointer-events: none;
}

.cover-mark {
    position: absolute;
    right: -2%;
    bottom: -16%;
    width: 56%;
    opacity: 0.3;
    pointer-events: none;
}
</style>
