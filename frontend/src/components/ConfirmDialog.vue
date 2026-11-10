<script setup>
import AppModal from './AppModal.vue'

const props = defineProps({
    modelValue: {
        type: Boolean,
        default: false
    },
    title: {
        type: String,
        default: '确认操作'
    },
    message: {
        type: String,
        default: ''
    },
    confirmText: {
        type: String,
        default: '确认'
    },
    cancelText: {
        type: String,
        default: '取消'
    },
    danger: {
        type: Boolean,
        default: false
    },
    loading: {
        type: Boolean,
        default: false
    }
})

const emit = defineEmits(['update:modelValue', 'confirm'])

function close() {
    if (!props.loading) {
        emit('update:modelValue', false)
    }
}
</script>

<template>
    <AppModal
        :model-value="modelValue"
        :title="title"
        @update:model-value="emit('update:modelValue', $event)"
    >
        <p class="confirm-message">{{ message }}</p>
        <template #footer>
            <button class="btn btn-ghost" type="button" :disabled="loading" @click="close">
                {{ cancelText }}
            </button>
            <button
                class="btn"
                :class="danger ? 'btn-danger' : 'btn-primary'"
                type="button"
                :disabled="loading"
                @click="emit('confirm')"
            >
                {{ loading ? '处理中…' : confirmText }}
            </button>
        </template>
    </AppModal>
</template>

<style scoped>
.confirm-message {
    color: var(--muted);
    font-size: 13px;
    line-height: 1.75;
}
</style>
