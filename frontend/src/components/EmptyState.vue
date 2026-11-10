<script setup>
defineProps({
    title: {
        type: String,
        default: '暂无内容'
    },
    description: {
        type: String,
        default: ''
    },
    actionText: {
        type: String,
        default: ''
    }
})

defineEmits(['action'])
</script>

<template>
    <div class="empty-state">
        <span class="empty-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
                 stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="4" width="18" height="16" rx="3"/>
                <path d="M3 9h18M8 4l-2 5M16 4l-2 5"/>
            </svg>
        </span>
        <h3>{{ title }}</h3>
        <p v-if="description">{{ description }}</p>
        <button v-if="actionText" class="btn btn-primary btn-small" type="button" @click="$emit('action')">
            {{ actionText }}
        </button>
        <slot/>
    </div>
</template>

<style scoped>
.empty-state {
    display: grid;
    justify-items: center;
    gap: 8px;
    padding: 44px 24px;
    color: var(--muted);
    text-align: center;
}

.empty-icon {
    display: grid;
    place-items: center;
    width: 52px;
    height: 52px;
    margin-bottom: 4px;
    border: 1px solid var(--line);
    border-radius: 50%;
    background: var(--surface-muted);
    color: var(--brand-ink);
}

.empty-icon svg {
    width: 24px;
    height: 24px;
}

.empty-state h3 {
    color: var(--text);
    font-size: 15px;
}

.empty-state p {
    max-width: 30ch;
    font-size: 12px;
    line-height: 1.7;
}

.empty-state .btn {
    margin-top: 8px;
}
</style>
