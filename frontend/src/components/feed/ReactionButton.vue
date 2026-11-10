<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { formatCount } from '../../utils/format'

const props = defineProps({
    type: {
        type: String,
        default: 'like'
    },
    active: {
        type: Boolean,
        default: false
    },
    count: {
        type: Number,
        default: 0
    },
    disabled: {
        type: Boolean,
        default: false
    },
    label: {
        type: String,
        default: ''
    }
})

const emit = defineEmits(['toggle'])

const ICON_PATHS = {
    like: 'M12 20s-7.5-4.6-7.5-10A4.5 4.5 0 0 1 12 6.8 4.5 4.5 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z',
    favorite: 'M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z'
}

const iconPath = computed(() => ICON_PATHS[props.type] || ICON_PATHS.like)

const burstId = ref(0)
const bursting = ref(false)
const bumping = ref(false)

let burstTimer = 0
let bumpTimer = 0
let countSnapshot = props.count

/**
 * 先移除 bursting 让 Vue 落地一次，再在下一帧加回，确保连续点击也能重播动画。
 */
function playBurst() {
    window.clearTimeout(burstTimer)
    bursting.value = false
    window.requestAnimationFrame(() => {
        burstId.value += 1
        bursting.value = true
        burstTimer = window.setTimeout(() => {
            bursting.value = false
        }, 760)
    })
}

/**
 * 计数变化时弹一下，与点赞/收藏主体动画区分。
 */
function playBump() {
    window.clearTimeout(bumpTimer)
    bumping.value = false
    window.requestAnimationFrame(() => {
        bumping.value = true
        bumpTimer = window.setTimeout(() => {
            bumping.value = false
        }, 420)
    })
}

function particleStyle(index) {
    return {
        '--angle': `${(index - 1) * 36 + 12}deg`,
        '--distance': `${20 + (index % 4) * 4}px`,
        '--delay': `${(index % 5) * 18}ms`
    }
}

/**
 * 只在实际点击激活时爆发；避免详情/互动状态加载完成时误触发。
 */
function onClick() {
    if (props.disabled) {
        return
    }
    if (!props.active) {
        playBurst()
    }
    emit('toggle')
}

watch(() => props.count, (count) => {
    if (count === countSnapshot) {
        return
    }
    countSnapshot = count
    playBump()
})

onBeforeUnmount(() => {
    window.clearTimeout(burstTimer)
    window.clearTimeout(bumpTimer)
})
</script>

<template>
    <button
        class="reaction-btn"
        :class="[`is-${type}`, { 'is-active': active, 'is-bursting': bursting }]"
        type="button"
        :disabled="disabled"
        :aria-pressed="active"
        :aria-label="label"
        @click="onClick"
    >
        <span class="reaction-icon">
            <span v-if="bursting" :key="burstId" class="reaction-burst" aria-hidden="true">
                <span class="reaction-ring"></span>
                <span
                    v-for="n in 10"
                    :key="n"
                    class="reaction-particle"
                    :style="particleStyle(n)"
                ></span>
                <svg class="reaction-ghost" viewBox="0 0 24 24">
                    <path :d="iconPath"/>
                </svg>
            </span>
            <svg
                class="reaction-svg"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
            >
                <path :d="iconPath"/>
            </svg>
        </span>
        <span class="reaction-count" :class="{ 'is-bump': bumping }">{{ formatCount(count) }}</span>
    </button>
</template>

<style scoped>
.reaction-btn {
    --reaction-color: #ff3b5c;

    display: grid;
    justify-items: center;
    gap: 3px;
    width: 48px;
    padding: 0;
    border: none;
    background: none;
    color: #fff;
    font-size: 10px;
    -webkit-tap-highlight-color: transparent;
    text-shadow: 0 2px 8px rgba(0, 0, 0, 0.55);
}

.reaction-btn.is-favorite {
    --reaction-color: #ffcf3f;
}

.reaction-btn:disabled {
    opacity: 0.45;
}

.reaction-icon {
    position: relative;
    display: grid;
    width: 32px;
    height: 32px;
    place-items: center;
}

.reaction-svg {
    width: 28px;
    height: 28px;
    overflow: visible;
    fill: transparent;
    filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.5));
    transition: fill 160ms ease, color 160ms ease, transform 140ms ease;
}

.reaction-btn:active:not(:disabled) .reaction-svg {
    transform: scale(0.82);
    transition-duration: 80ms;
}

.reaction-btn.is-active {
    color: var(--reaction-color);
}

.reaction-btn.is-active .reaction-svg {
    fill: currentColor;
}

.reaction-btn.is-like.is-active .reaction-svg {
    filter: drop-shadow(0 0 9px rgba(255, 59, 92, 0.55));
}

.reaction-btn.is-favorite.is-active .reaction-svg {
    filter: drop-shadow(0 0 9px rgba(255, 207, 63, 0.55));
}

.reaction-btn.is-bursting .reaction-svg {
    animation: reaction-pop 520ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

.reaction-burst,
.reaction-ring,
.reaction-particle,
.reaction-ghost {
    position: absolute;
    top: 50%;
    left: 50%;
    pointer-events: none;
}

.reaction-ring {
    width: 36px;
    height: 36px;
    margin: -18px 0 0 -18px;
    border: 2px solid currentColor;
    border-radius: 50%;
    color: var(--reaction-color);
    opacity: 0;
    animation: reaction-ring 560ms ease-out forwards;
}

.reaction-particle {
    width: 4px;
    height: 4px;
    margin: -2px 0 0 -2px;
    border-radius: 50%;
    background: currentColor;
    color: var(--reaction-color);
    opacity: 0;
    animation: reaction-particle 620ms ease-out forwards;
    animation-delay: var(--delay);
}

.reaction-ghost {
    width: 28px;
    height: 28px;
    margin: -14px 0 0 -14px;
    overflow: visible;
    fill: currentColor;
    color: var(--reaction-color);
    opacity: 0;
    animation: reaction-ghost 640ms cubic-bezier(0.22, 0.9, 0.36, 1) forwards;
}

.reaction-count {
    display: inline-block;
    font-variant-numeric: tabular-nums;
    line-height: 1.2;
}

.reaction-count.is-bump {
    animation: reaction-count-bump 420ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

@keyframes reaction-pop {
    0% {
        transform: scale(0.55);
    }
    45% {
        transform: scale(1.32);
    }
    70% {
        transform: scale(0.92);
    }
    88% {
        transform: scale(1.08);
    }
    100% {
        transform: scale(1);
    }
}

@keyframes reaction-ring {
    0% {
        opacity: 0.85;
        transform: scale(0.35);
    }
    100% {
        opacity: 0;
        transform: scale(1.8);
    }
}

@keyframes reaction-particle {
    0% {
        opacity: 0;
        transform: rotate(var(--angle)) translateY(-6px) scale(0.4);
    }
    15% {
        opacity: 1;
    }
    100% {
        opacity: 0;
        transform: rotate(var(--angle)) translateY(calc(var(--distance) * -1)) scale(1);
    }
}

@keyframes reaction-ghost {
    0% {
        opacity: 0.9;
        transform: scale(0.5);
    }
    55% {
        opacity: 0.45;
    }
    100% {
        opacity: 0;
        transform: scale(1.85);
    }
}

@keyframes reaction-count-bump {
    0% {
        color: #fff;
        transform: scale(1);
    }
    35% {
        color: var(--reaction-color);
        transform: scale(1.28);
    }
    100% {
        color: inherit;
        transform: scale(1);
    }
}
</style>
