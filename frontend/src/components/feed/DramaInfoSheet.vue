<script setup>
import AppModal from '../AppModal.vue'
import DramaInfoContent from './DramaInfoContent.vue'

defineProps({
    modelValue: {
        type: Boolean,
        default: false
    },
    detail: {
        type: Object,
        required: true
    }
})

const emit = defineEmits(['update:modelValue'])
</script>

<template>
    <AppModal
        :model-value="modelValue"
        title="短剧简介"
        @update:model-value="emit('update:modelValue', $event)"
    >
        <DramaInfoContent :detail="detail"/>
    </AppModal>
</template>
