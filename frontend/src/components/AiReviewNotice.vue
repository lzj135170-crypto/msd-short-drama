<script setup>
import { computed } from 'vue'
import { aiReviewNotice, aiSuggestedTags } from '../utils/aiReview'

const props = defineProps({
    reviewStatus: {
        type: [String, Number],
        default: 0
    },
    aiReviewStatus: {
        type: [String, Number],
        default: 0
    },
    aiReviewResult: {
        type: String,
        default: ''
    }
})

const notice = computed(() => aiReviewNotice(props))
const suggestedTags = computed(() => aiSuggestedTags(props.aiReviewResult))
</script>

<template>
    <div
        v-if="notice || suggestedTags.length"
        class="ai-review-notice"
        :class="notice ? `is-${notice.type}` : ''"
    >
        <template v-if="notice">
            <strong>{{ notice.title }}</strong>
            <p>{{ notice.text }}</p>
            <p v-if="notice.tags.length" class="notice-tags">
                命中标签：{{ notice.tags.join('、') }}
            </p>
        </template>
        <p v-if="suggestedTags.length" class="notice-suggest">
            建议标签（仅供参考）：{{ suggestedTags.join('、') }}
        </p>
    </div>
</template>

<style scoped>
.ai-review-notice {
    display: grid;
    gap: 4px;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface-muted);
    color: var(--muted);
    font-size: 11px;
    line-height: 1.65;
}

.ai-review-notice.is-danger {
    border-color: #f0c6c6;
    background: var(--danger-soft);
}

.ai-review-notice.is-warning {
    border-color: #f0ddb4;
    background: var(--warning-soft);
}

.ai-review-notice strong {
    color: var(--text);
    font-size: 12px;
}

.ai-review-notice.is-danger strong {
    color: var(--danger);
}

.ai-review-notice.is-warning strong {
    color: var(--warning-ink);
}

.ai-review-notice p {
    margin: 0;
}

.notice-tags,
.notice-suggest {
    color: var(--muted);
}

.notice-tags {
    color: var(--brand-2);
}
</style>
