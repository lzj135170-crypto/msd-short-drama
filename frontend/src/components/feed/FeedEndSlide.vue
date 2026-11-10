<script setup>
import GradientCover from '../GradientCover.vue'
import RelatedDramas from '../RelatedDramas.vue'

defineProps({
    drama: {
        type: Object,
        required: true
    }
})

defineEmits(['home', 'replay'])
</script>

<template>
    <div class="feed-end">
        <GradientCover
            class="end-bg"
            :seed="drama.id"
            :title="drama.title"
            :src="drama.coverUrl"
            ratio="poster"
            :rounded="false"
        />
        <span class="end-scrim" aria-hidden="true"></span>

        <div class="end-copy">
            <span class="eyebrow">THE END</span>
            <h2>已看完本剧</h2>
            <p>{{ drama.title }} · 共 {{ drama.episodes?.length || 0 }} 集</p>
            <div class="end-actions">
                <button class="btn btn-primary btn-small" type="button" @click="$emit('replay')">
                    重看第 1 集
                </button>
                <button class="btn btn-ghost btn-small" type="button" @click="$emit('home')">
                    返回首页
                </button>
            </div>
            <div class="end-related">
                <RelatedDramas :drama-id="drama.id" :limit="4"/>
            </div>
        </div>
    </div>
</template>

<style scoped>
.feed-end {
    position: absolute;
    inset: 0;
    overflow: hidden;
    background: #000;
}

.feed-end .end-bg {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    aspect-ratio: auto;
    border-radius: 0;
}

.end-scrim {
    position: absolute;
    inset: 0;
    background:
        radial-gradient(circle at 50% 38%, rgba(255, 92, 61, 0.16), transparent 52%),
        linear-gradient(180deg, rgba(0, 0, 0, 0.55), rgba(0, 0, 0, 0.9));
}

.end-copy {
    position: absolute;
    inset: 0;
    display: grid;
    align-content: center;
    justify-items: center;
    gap: 10px;
    overflow-y: auto;
    padding: 24px;
    text-align: center;
}

.end-copy h2 {
    font-size: 26px;
    font-weight: 900;
    letter-spacing: 0.02em;
}

.end-copy p {
    color: var(--muted);
    font-size: 12px;
}

.end-actions {
    display: flex;
    gap: 8px;
    margin-top: 12px;
}

.end-related {
    width: min(100%, 420px);
    margin-top: 14px;
    text-align: left;
}
</style>
