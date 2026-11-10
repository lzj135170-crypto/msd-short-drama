<script setup>
defineProps({
    message: {
        type: String,
        default: '加载失败，请稍后重试'
    },
    retryText: {
        type: String,
        default: '重新加载'
    }
})

defineEmits(['retry'])
</script>

<template>
    <div class="error-state" role="alert">
        <span class="error-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
                 stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="9"/>
                <path d="M12 7.5v5M12 16.2v.3"/>
            </svg>
        </span>
        <p>{{ message }}</p>
        <button class="btn btn-ghost btn-small" type="button" @click="$emit('retry')">
            {{ retryText }}
        </button>
    </div>
</template>

<style scoped>
.error-state {
    display: grid;
    justify-items: center;
    gap: 12px;
    padding: 44px 24px;
    text-align: center;
}

.error-icon {
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    border-radius: 50%;
    background: var(--danger-soft);
    color: var(--danger);
}

.error-icon svg {
    width: 24px;
    height: 24px;
}

.error-state p {
    max-width: 32ch;
    color: var(--muted);
    font-size: 13px;
    line-height: 1.7;
}
</style>
