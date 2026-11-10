<script setup>
import { onBeforeUnmount, watch } from 'vue'
import { lockBodyScroll, unlockBodyScroll } from '../composables/useBodyScrollLock'

const props = defineProps({
    modelValue: {
        type: Boolean,
        default: false
    },
    title: {
        type: String,
        default: ''
    },
    closeOnBackdrop: {
        type: Boolean,
        default: true
    },
    // 桌面端宽度档位：md 480px，lg 720px；移动端始终是底部 sheet
    size: {
        type: String,
        default: 'md'
    }
})

const emit = defineEmits(['update:modelValue'])

let locked = false

function close() {
    emit('update:modelValue', false)
}

function onKeydown(event) {
    if (event.key === 'Escape') {
        close()
    }
}

/**
 * 打开时锁定页面滚动并监听 Esc，关闭时恢复。
 */
function syncLock(open) {
    if (open && !locked) {
        lockBodyScroll()
        locked = true
        window.addEventListener('keydown', onKeydown)
        return
    }
    if (!open && locked) {
        unlockBodyScroll()
        locked = false
        window.removeEventListener('keydown', onKeydown)
    }
}

watch(() => props.modelValue, syncLock, { immediate: true })

onBeforeUnmount(() => {
    if (locked) {
        unlockBodyScroll()
        locked = false
    }
    window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
    <Teleport to="body">
        <Transition name="modal">
            <div v-if="modelValue" class="modal-root" role="dialog" aria-modal="true" :aria-label="title">
                <div class="modal-backdrop" @click="closeOnBackdrop && close()"></div>
                <section class="modal-panel" :class="`is-${size}`">
                    <header class="modal-head">
                        <h2>{{ title }}</h2>
                        <button class="modal-close" type="button" aria-label="关闭" @click="close">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                                 stroke-linecap="round" aria-hidden="true">
                                <path d="M6 6l12 12M18 6L6 18"/>
                            </svg>
                        </button>
                    </header>
                    <div class="modal-body">
                        <slot/>
                    </div>
                    <footer v-if="$slots.footer" class="modal-foot">
                        <slot name="footer"/>
                    </footer>
                </section>
            </div>
        </Transition>
    </Teleport>
</template>

<style scoped>
.modal-root {
    position: fixed;
    inset: 0;
    z-index: 80;
    display: flex;
    align-items: flex-end;
    justify-content: center;
}

.modal-backdrop {
    position: absolute;
    inset: 0;
    background: rgba(22, 33, 26, 0.38);
    backdrop-filter: blur(4px);
}

.modal-panel {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 14px;
    width: min(100%, var(--app-width, 480px));
    max-height: 88dvh;
    padding: 18px 18px calc(18px + env(safe-area-inset-bottom));
    border: 1px solid var(--line);
    border-radius: 24px 24px 0 0;
    background: var(--panel-strong);
    box-shadow: var(--shadow);
}

.modal-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}

.modal-head h2 {
    font-size: 17px;
}

.modal-close {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: 50%;
    background: transparent;
    color: var(--muted);
}

.modal-close svg {
    width: 15px;
    height: 15px;
}

.modal-body {
    overflow-y: auto;
    min-height: 0;
}

.modal-foot {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
}

.modal-enter-active,
.modal-leave-active {
    transition: opacity 180ms ease;
}

.modal-enter-active .modal-panel,
.modal-leave-active .modal-panel {
    transition: transform 220ms ease;
}

.modal-enter-from,
.modal-leave-to {
    opacity: 0;
}

.modal-enter-from .modal-panel,
.modal-leave-to .modal-panel {
    transform: translateY(24px);
}

@media (min-width: 640px) {
    .modal-root {
        align-items: center;
    }

    .modal-panel {
        border-radius: 24px;
        padding-bottom: 18px;
    }
}

@media (min-width: 1024px) {
    .modal-panel {
        max-height: 82dvh;
    }

    .modal-panel.is-lg {
        width: min(100%, 720px);
    }
}

@media (prefers-reduced-motion: reduce) {
    .modal-enter-active,
    .modal-leave-active,
    .modal-enter-active .modal-panel,
    .modal-leave-active .modal-panel {
        transition: none;
    }
}
</style>
