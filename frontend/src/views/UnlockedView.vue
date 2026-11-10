<script setup>
import { useRouter } from 'vue-router'
import { fetchUnlockedEpisodes } from '../api/order'
import { usePagedList } from '../composables/usePagedList'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import GradientCover from '../components/GradientCover.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const router = useRouter()

const {
    items,
    total,
    loading,
    loadingMore,
    error,
    loadMoreError,
    hasMore,
    refresh,
    loadMore
} = usePagedList(
    ({ pageNo, pageSize }) => fetchUnlockedEpisodes({ pageNo, pageSize }),
    { keyOf: (item) => item.unlockId }
)

function open(item) {
    router.push({
        name: 'drama-detail',
        params: { dramaId: item.dramaId },
        query: { episode: item.episodeId }
    })
}
</script>

<template>
    <div class="sub-page">
        <PageHeader title="已解锁剧集" back-to="/me"/>

        <main class="app-main unlocked-page">
            <div v-if="loading" class="unlock-list">
                <SkeletonBlock v-for="index in 4" :key="index" height="96px" radius="14px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <EmptyState
                v-else-if="!items.length"
                title="还没有解锁剧集"
                description="在播放页使用积分解锁后，会出现在这里。"
                action-text="去首页"
                @action="router.push('/')"
            />

            <template v-else>
                <div class="unlock-list">
                    <button
                        v-for="item in items"
                        :key="item.unlockId"
                        class="unlock-item"
                        type="button"
                        @click="open(item)"
                    >
                        <GradientCover
                            class="unlock-cover"
                            :seed="item.episodeId"
                            :title="item.episodeTitle"
                            :src="item.dramaCoverUrl"
                            ratio="poster"
                        />
                        <span class="unlock-copy">
                            <strong>{{ item.dramaTitle || `短剧 ${item.dramaId}` }}</strong>
                            <span>第{{ item.episodeNo || '?' }}集 · {{ item.episodeTitle || '' }}</span>
                            <span class="unlock-meta">
                                {{ item.pointsCost }} 积分 · {{ formatDateTime(item.unlockTime) }}
                            </span>
                        </span>
                        <span class="unlock-arrow" aria-hidden="true">›</span>
                    </button>
                </div>

                <LoadMore
                    :loading="loadingMore"
                    :has-more="hasMore"
                    :error="loadMoreError"
                    :total="total"
                    @load="loadMore"
                />
            </template>
        </main>
    </div>
</template>

<style scoped>
.unlocked-page {
    display: grid;
    gap: 12px;
}

.unlock-list {
    display: grid;
    gap: 10px;
}

.unlock-item {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    padding: 10px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
    text-align: left;
}

.unlock-cover {
    flex: none;
    width: 54px;
}

.unlock-copy {
    display: grid;
    min-width: 0;
    flex: 1;
    gap: 3px;
}

.unlock-copy strong {
    overflow: hidden;
    font-size: 14px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.unlock-copy span {
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.unlock-meta {
    color: var(--muted-soft) !important;
    font-size: 10px !important;
}

.unlock-arrow {
    flex: none;
    color: var(--muted);
    font-size: 22px;
}

/* ---------- 桌面端（≥1024） ---------- */

@media (min-width: 1024px) {
    .unlock-list {
        max-width: 900px;
        gap: 12px;
    }

    .unlock-item {
        padding: 14px 16px;
    }

    .unlock-cover {
        width: 66px;
    }

    .unlock-copy strong {
        font-size: 15px;
    }
}

@media (min-width: 1280px) {
    .unlock-list {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        max-width: none;
    }
}
</style>
