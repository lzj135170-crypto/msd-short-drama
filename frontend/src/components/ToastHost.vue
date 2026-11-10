<template>
    <div
        class="toast-stack"
        :class="{ 'is-tab': hasTab, 'is-feed': immersive }"
        aria-live="polite"
        aria-atomic="true"
    >
        <TransitionGroup name="toast">
            <button
                v-for="item in toasts.items"
                :key="item.id"
                class="toast"
                :class="`toast-${item.type}`"
                type="button"
                @click="toasts.dismiss(item.id)"
            >
                {{ item.message }}
            </button>
        </TransitionGroup>
    </div>
</template>

<script setup>
import { useToastStore } from '../stores/toast'

defineProps({
    hasTab: {
        type: Boolean,
        default: false
    },
    immersive: {
        type: Boolean,
        default: false
    }
})

const toasts = useToastStore()
</script>
