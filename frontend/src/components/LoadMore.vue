<script setup>
defineProps({
    loading: {
        type: Boolean,
        default: false
    },
    hasMore: {
        type: Boolean,
        default: false
    },
    error: {
        type: String,
        default: ''
    },
    total: {
        type: Number,
        default: 0
    },
    showEnd: {
        type: Boolean,
        default: true
    }
})

defineEmits(['load'])
</script>

<template>
    <div v-if="error || hasMore || (showEnd && total > 0)" class="load-more">
        <p v-if="error" class="load-error">
            {{ error }}
            <button type="button" @click="$emit('load')">重试</button>
        </p>
        <button
            v-else-if="hasMore"
            class="btn btn-ghost btn-small"
            type="button"
            :disabled="loading"
            @click="$emit('load')"
        >
            {{ loading ? '加载中…' : '加载更多' }}
        </button>
        <span v-else-if="total > 0" class="load-end">已显示全部 {{ total }} 条</span>
    </div>
</template>

<style scoped>
.load-more {
    display: flex;
    justify-content: center;
    padding: 18px 0 6px;
}

.load-more .btn {
    min-width: 132px;
}

.load-error {
    color: var(--danger);
    font-size: 12px;
}

.load-error button {
    padding: 0;
    border: none;
    background: none;
    color: var(--brand-2);
    font-size: 12px;
    text-decoration: underline;
}

.load-end {
    color: var(--muted-soft);
    font-size: 11px;
}
</style>
