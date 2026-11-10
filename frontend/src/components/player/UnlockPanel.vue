<script setup>
import { computed } from 'vue'
import GradientCover from '../GradientCover.vue'

const props = defineProps({
    playback: {
        type: Object,
        required: true
    },
    points: {
        type: Object,
        default: null
    },
    pointsLoading: {
        type: Boolean,
        default: false
    },
    pointsError: {
        type: String,
        default: ''
    },
    unlocking: {
        type: Boolean,
        default: false
    }
})

const emit = defineEmits(['unlock', 'retry-points', 'recharge'])

const price = computed(() => Number(props.playback?.pricePoints) || 0)
const balance = computed(() => Number(props.points?.balance) || 0)
const noAccount = computed(() => Boolean(props.points) && props.points.exists === false)
const insufficient = computed(() => Boolean(props.points?.exists) && balance.value < price.value)
const canUnlock = computed(() => (
    Boolean(props.points?.exists)
    && !insufficient.value
    && !props.unlocking
))
</script>

<template>
    <section class="unlock-panel">
        <div class="lock-stage">
            <GradientCover
                class="lock-cover"
                :seed="playback.episodeId"
                :title="playback.episodeTitle"
                ratio="wide"
                :rounded="false"
            />
            <span class="lock-scrim" aria-hidden="true"></span>
            <span class="lock-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
                     stroke-linecap="round" stroke-linejoin="round">
                    <rect x="5" y="10" width="14" height="10" rx="2.4"/>
                    <path d="M8.5 10V7.5a3.5 3.5 0 0 1 7 0V10"/>
                </svg>
            </span>
            <div class="lock-copy">
                <span>第 {{ playback.episodeNo }} 集</span>
                <strong>{{ playback.episodeTitle }}</strong>
            </div>
        </div>

        <div class="unlock-card">
            <div class="price-row">
                <span>本集价格</span>
                <strong>{{ price }} 积分</strong>
            </div>
            <div class="price-row">
                <span>当前余额</span>
                <strong v-if="pointsLoading">加载中…</strong>
                <strong v-else-if="noAccount">未开通积分账户</strong>
                <strong v-else-if="points">{{ balance }} 积分</strong>
                <strong v-else>—</strong>
            </div>

            <p v-if="pointsError" class="unlock-error">
                {{ pointsError }}
                <button type="button" @click="emit('retry-points')">重试</button>
            </p>
            <p v-else-if="noAccount" class="unlock-hint">当前账号没有积分账户，充值后自动开通。</p>
            <p v-else-if="insufficient" class="unlock-hint">积分不足，还差 {{ price - balance }} 积分。</p>

            <button
                class="btn btn-primary btn-block"
                type="button"
                :disabled="!canUnlock"
                @click="emit('unlock')"
            >
                {{ unlocking ? '解锁中…' : `用 ${price} 积分解锁` }}
            </button>

            <button
                v-if="noAccount || insufficient"
                class="btn btn-ghost btn-block"
                type="button"
                @click="emit('recharge')"
            >
                去充值
            </button>
            <p class="unlock-foot">解锁后即可播放本集，重复解锁不会重复扣积分。</p>
        </div>
    </section>
</template>

<style scoped>
.unlock-panel {
    display: grid;
    gap: 12px;
}

.lock-stage {
    position: relative;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 18px;
    box-shadow: var(--shadow);
}

.lock-cover {
    width: 100%;
    height: 100%;
}

.lock-scrim {
    position: absolute;
    inset: 0;
    background: linear-gradient(180deg, rgba(0, 0, 0, 0.1), rgba(0, 0, 0, 0.8));
}

.lock-icon {
    position: absolute;
    top: 16px;
    right: 16px;
    display: grid;
    place-items: center;
    width: 38px;
    height: 38px;
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: 50%;
    background: rgba(0, 0, 0, 0.38);
    color: var(--brand-2);
    backdrop-filter: blur(6px);
}

.lock-icon svg {
    width: 19px;
    height: 19px;
}

.lock-copy {
    position: absolute;
    right: 16px;
    bottom: 14px;
    left: 16px;
    display: grid;
    gap: 4px;
}

.lock-copy span {
    color: var(--brand-2);
    font-size: 10px;
    font-weight: 800;
    letter-spacing: 0.1em;
}

.lock-copy strong {
    overflow: hidden;
    color: #fff;
    font-size: 19px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.unlock-card {
    display: grid;
    gap: 10px;
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--panel);
}

.price-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    color: var(--muted);
    font-size: 12px;
}

.price-row strong {
    color: var(--text);
    font-size: 14px;
}

.unlock-hint {
    color: var(--brand-2);
    font-size: 12px;
    line-height: 1.7;
}

.unlock-error {
    color: var(--danger);
    font-size: 12px;
}

.unlock-error button {
    padding: 0;
    border: none;
    background: none;
    color: var(--brand-2);
    font-size: 12px;
    text-decoration: underline;
}

.unlock-foot {
    color: rgba(255, 255, 255, 0.32);
    font-size: 10px;
    line-height: 1.6;
    text-align: center;
}
</style>
