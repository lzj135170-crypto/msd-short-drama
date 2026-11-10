<script setup>
/**
 * 侧栏图标：内联 SVG，避免为几个图标引入图标库。
 */
defineProps({
    name: {
        type: String,
        required: true
    }
})
</script>

<template>
    <svg
        class="nav-icon"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.8"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
    >
        <template v-if="name === 'home'">
            <path d="M3 10.5 12 3l9 7.5"/>
            <path d="M5 9.5V20a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V9.5"/>
        </template>

        <template v-else-if="name === 'search'">
            <circle cx="11" cy="11" r="7"/>
            <path d="M20 20l-3.6-3.6"/>
        </template>

        <template v-else-if="name === 'play'">
            <circle cx="12" cy="12" r="9"/>
            <path d="M10 8.5l6 3.5-6 3.5z"/>
        </template>

        <template v-else-if="name === 'user'">
            <circle cx="12" cy="8" r="3.6"/>
            <path d="M4.5 20c.8-3.6 3.7-5.6 7.5-5.6s6.7 2 7.5 5.6"/>
        </template>

        <template v-else-if="name === 'star'">
            <path d="M12 3.6l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8L3.5 9.8l5.9-.9z"/>
        </template>

        <template v-else-if="name === 'heart'">
            <path d="M12 20s-7-4.4-7-9a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 4.6-7 9-7 9z"/>
        </template>

        <template v-else-if="name === 'clock'">
            <circle cx="12" cy="12" r="9"/>
            <path d="M12 7v5l3.5 2"/>
        </template>

        <template v-else-if="name === 'comment'">
            <path d="M20 15a2 2 0 0 1-2 2H8l-4 3V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z"/>
        </template>

        <template v-else-if="name === 'unlock'">
            <path d="M7.5 11V8.5a4.5 4.5 0 0 1 8.6-1.8"/>
            <rect x="4.5" y="11" width="15" height="9" rx="2"/>
        </template>

        <template v-else-if="name === 'wallet'">
            <rect x="3.5" y="6" width="17" height="13" rx="2.5"/>
            <path d="M3.5 10h17"/>
            <path d="M16.5 14.5h1.5"/>
        </template>

        <template v-else-if="name === 'clapper'">
            <path d="M4 9h16v10a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19z"/>
            <path d="M4 9l14.5-4 .9 3.2"/>
            <path d="M9 8.3l3-2.2"/>
            <path d="M14.5 6.8l3-2.2"/>
        </template>

        <template v-else-if="name === 'shield'">
            <path d="M12 3.5 19.5 6v6c0 4.3-3 7.4-7.5 8.5C7.5 19.4 4.5 16.3 4.5 12V6z"/>
            <path d="M9 11.8l2.2 2.2 4-4"/>
        </template>

        <template v-else-if="name === 'filter'">
            <path d="M4 6h16l-6 7v5l-4 2v-7z"/>
        </template>

        <template v-else>
            <circle cx="12" cy="12" r="8"/>
        </template>
    </svg>
</template>

<style scoped>
.nav-icon {
    width: 20px;
    height: 20px;
}
</style>
