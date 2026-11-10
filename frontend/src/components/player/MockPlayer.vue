<script setup>
import { computed, ref, watch } from 'vue'
import GradientCover from '../GradientCover.vue'
import { formatMs } from '../../utils/format'

/**
 * 沉浸式播放器。组件名沿用 MockPlayer，但实际会播放真实 <video>；
 * 没有视频地址或加载失败时回退到封面占位画面。
 */
const props = defineProps({
    seed: {
        type: [String, Number],
        default: ''
    },
    title: {
        type: String,
        default: ''
    },
    coverUrl: {
        type: String,
        default: ''
    },
    videoUrl: {
        type: String,
        default: ''
    },
    muted: {
        type: Boolean,
        default: true
    },
    attach: {
        type: Function,
        default: null
    },
    episodeTitle: {
        type: String,
        default: ''
    },
    durationMs: {
        type: Number,
        default: 0
    },
    positionMs: {
        type: Number,
        default: 0
    },
    playing: {
        type: Boolean,
        default: false
    },
    finished: {
        type: Boolean,
        default: false
    },
    immersive: {
        type: Boolean,
        default: false
    },
    showHead: {
        type: Boolean,
        default: true
    }
})

defineEmits(['toggle', 'seek', 'seek-commit', 'replay', 'toggle-muted'])

const videoError = ref(false)

// 换集后重试加载新视频
watch(() => props.videoUrl, () => {
    videoError.value = false
})

const showVideo = computed(() => Boolean(props.videoUrl) && !videoError.value)

/**
 * 把 range 的值转成毫秒。
 */
function readValue(event) {
    return Number(event.target.value) || 0
}
</script>

<template>
    <section class="mock-player" :class="{ 'is-immersive': immersive }" aria-label="模拟播放器">
        <div class="stage" :class="{ 'has-video': showVideo }">
            <GradientCover
                class="stage-cover"
                :seed="seed"
                :title="title"
                :src="coverUrl"
                ratio="poster"
                :rounded="false"
            />
            <video
                v-if="showVideo"
                :ref="attach"
                class="stage-video"
                :src="videoUrl"
                :poster="coverUrl"
                preload="metadata"
                playsinline
                :muted="muted"
                @error="videoError = true"
            ></video>
            <span class="stage-scrim" aria-hidden="true"></span>
            <span v-if="!immersive" class="mock-badge">模拟播放</span>

            <div v-if="showHead" class="stage-head">
                <span class="stage-episode">{{ episodeTitle }}</span>
                <strong>{{ title }}</strong>
            </div>

            <div v-if="playing && !finished && !immersive" class="equalizer" aria-hidden="true">
                <i v-for="bar in 5" :key="bar" :style="{ animationDelay: `${bar * 0.12}s` }"></i>
            </div>

            <button
                v-if="!finished"
                class="stage-toggle"
                type="button"
                :aria-label="playing ? '暂停' : '播放'"
                @click="$emit('toggle')"
            >
                <svg v-if="!playing" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                    <path d="M8 5.5v13l11-6.5z"/>
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                    <path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/>
                </svg>
            </button>

            <div v-else class="finish-panel">
                <span class="finish-title">本集完</span>
                <p>已记录为看完</p>
                <button class="btn btn-ghost btn-small" type="button" @click="$emit('replay')">
                    重播
                </button>
            </div>
        </div>

        <div class="controls">
            <button
                class="control-btn"
                type="button"
                :disabled="finished || durationMs <= 0"
                :aria-label="playing ? '暂停' : '播放'"
                @click="$emit('toggle')"
            >
                <svg v-if="!playing" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                    <path d="M8 5.5v13l11-6.5z"/>
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                    <path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/>
                </svg>
            </button>

            <button
                v-if="showVideo"
                class="control-btn"
                type="button"
                :aria-label="muted ? '取消静音' : '静音'"
                @click="$emit('toggle-muted')"
            >
                <svg v-if="muted" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
                     aria-hidden="true">
                    <path d="M4 9v6h4l5 4V5L8 9z"/>
                    <path d="M16.5 9.5l5 5M21.5 9.5l-5 5"/>
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
                     aria-hidden="true">
                    <path d="M4 9v6h4l5 4V5L8 9z"/>
                    <path d="M16 8.5a5 5 0 0 1 0 7M18.5 6a8.5 8.5 0 0 1 0 12"/>
                </svg>
            </button>

            <span class="time">{{ formatMs(positionMs) }}</span>

            <input
                class="progress"
                type="range"
                min="0"
                :max="durationMs"
                step="1000"
                :value="positionMs"
                :disabled="finished || durationMs <= 0"
                aria-label="播放进度"
                @input="$emit('seek', readValue($event))"
                @change="$emit('seek-commit', readValue($event))"
            >

            <span class="time">{{ formatMs(durationMs) }}</span>
        </div>

        <p v-if="durationMs <= 0" class="duration-warning">缺少视频时长，无法播放。</p>
    </section>
</template>

<style scoped>
.mock-player {
    display: grid;
    gap: 10px;
    padding: 10px 16px 0;
}

.stage {
    position: relative;
    overflow: hidden;
    width: 100%;
    height: min(60dvh, 540px);
    max-width: 100%;
    aspect-ratio: 9 / 16;
    margin: 0 auto;
    border: 1px solid var(--line);
    border-radius: 20px;
    background: #000;
    box-shadow: var(--shadow);
}

.stage-cover {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    aspect-ratio: auto;
}

/* 播放真实视频时，封面退成模糊背景，视频本身按比例完整显示 */
.stage.has-video .stage-cover {
    filter: blur(18px) brightness(0.45);
    transform: scale(1.08);
}

.stage-video {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: contain;
    background: transparent;
}

.stage-scrim {
    position: absolute;
    inset: 0;
    background:
        linear-gradient(180deg, rgba(0, 0, 0, 0.48) 0%, transparent 26%, transparent 58%, rgba(0, 0, 0, 0.78) 100%);
}

.mock-badge {
    position: absolute;
    top: 12px;
    right: 12px;
    z-index: 2;
    padding: 4px 8px;
    border: 1px solid rgba(255, 255, 255, 0.18);
    border-radius: 999px;
    background: rgba(0, 0, 0, 0.35);
    color: rgba(255, 255, 255, 0.72);
    font-size: 9px;
    letter-spacing: 0.08em;
    backdrop-filter: blur(6px);
}

.stage-head {
    position: absolute;
    top: 14px;
    right: 76px;
    left: 14px;
    z-index: 2;
    display: grid;
    gap: 4px;
}

.stage-episode {
    color: var(--brand-2);
    font-size: 10px;
    font-weight: 800;
    letter-spacing: 0.08em;
}

.stage-head strong {
    overflow: hidden;
    color: #fff;
    font-size: 17px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.stage-toggle {
    position: absolute;
    top: 50%;
    left: 50%;
    z-index: 3;
    display: grid;
    place-items: center;
    width: 62px;
    height: 62px;
    padding: 0;
    border: 1px solid rgba(255, 255, 255, 0.22);
    border-radius: 50%;
    background: rgba(0, 0, 0, 0.45);
    color: #fff;
    transform: translate(-50%, -50%);
    backdrop-filter: blur(8px);
}

.stage-toggle svg {
    width: 26px;
    height: 26px;
}

.equalizer {
    position: absolute;
    bottom: 18px;
    left: 14px;
    z-index: 2;
    display: flex;
    align-items: flex-end;
    gap: 3px;
    height: 24px;
}

.equalizer i {
    width: 3px;
    height: 8px;
    border-radius: 2px;
    background: var(--brand-2);
    animation: equalize 760ms ease-in-out infinite alternate;
}

.equalizer i:nth-child(2) {
    animation-duration: 620ms;
}

.equalizer i:nth-child(3) {
    animation-duration: 880ms;
}

.equalizer i:nth-child(4) {
    animation-duration: 700ms;
}

.equalizer i:nth-child(5) {
    animation-duration: 820ms;
}

@keyframes equalize {
    from {
        height: 5px;
        opacity: 0.55;
    }
    to {
        height: 22px;
        opacity: 1;
    }
}

.finish-panel {
    position: absolute;
    inset: 0;
    z-index: 3;
    display: grid;
    align-content: center;
    justify-items: center;
    gap: 8px;
    background: rgba(0, 0, 0, 0.46);
    text-align: center;
    backdrop-filter: blur(2px);
}

.finish-title {
    color: var(--brand-2);
    font-size: 22px;
    font-weight: 900;
    letter-spacing: 0.08em;
}

.finish-panel p {
    color: rgba(255, 255, 255, 0.65);
    font-size: 12px;
}

.finish-panel .btn {
    margin-top: 6px;
}

.controls {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
}

.control-btn {
    display: grid;
    flex: none;
    place-items: center;
    width: 34px;
    height: 34px;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.05);
    color: var(--brand-2);
}

.control-btn:disabled {
    opacity: 0.4;
}

.control-btn svg {
    width: 16px;
    height: 16px;
}

.time {
    flex: none;
    color: var(--muted);
    font-family: "Cascadia Code", Consolas, monospace;
    font-size: 10px;
}

.progress {
    flex: 1;
    min-width: 0;
    accent-color: var(--brand-2);
    touch-action: pan-x;
}

.duration-warning {
    color: var(--danger);
    font-size: 12px;
    text-align: center;
}

/* ---------- 全屏 Feed 变体 ---------- */

.mock-player.is-immersive {
    position: absolute;
    inset: 0;
    display: block;
    padding: 0;
}

.mock-player.is-immersive .stage {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    max-width: none;
    aspect-ratio: auto;
    margin: 0;
    border: 0;
    border-radius: 0;
    box-shadow: none;
}

.mock-player.is-immersive .stage-cover {
    aspect-ratio: auto;
}

/* 竖屏 Feed 内让 16:9 素材铺满舞台，非沉浸模式仍完整显示 */
.mock-player.is-immersive .stage-video {
    object-fit: cover;
}

.mock-player.is-immersive .controls {
    position: absolute;
    right: 12px;
    bottom: calc(env(safe-area-inset-bottom) + 10px);
    left: 12px;
    z-index: 12;
    border-color: rgba(255, 255, 255, 0.14);
    background: rgba(0, 0, 0, 0.38);
    backdrop-filter: blur(10px);
}

.mock-player.is-immersive .duration-warning {
    position: absolute;
    top: 50%;
    right: 16px;
    left: 16px;
    z-index: 4;
}

@media (prefers-reduced-motion: reduce) {
    .equalizer i {
        animation: none;
        height: 12px;
    }
}
</style>
