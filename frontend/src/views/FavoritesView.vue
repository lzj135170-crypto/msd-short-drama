<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchFavorites, unfavoriteDrama } from '../api/interaction'
import { useToastStore } from '../stores/toast'
import { usePagedList } from '../composables/usePagedList'
import { useMediaQuery } from '../composables/useMediaQuery'
import CollectionItem from '../components/CollectionItem.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const router = useRouter()
const toast = useToastStore()
const isDesktop = useMediaQuery('(min-width: 1024px)')

const {
    items,
    total,
    loading,
    loadingMore,
    error,
    loadMoreError,
    hasMore,
    refresh,
    loadMore,
    remove
} = usePagedList(
    ({ pageNo, pageSize }) => fetchFavorites({ pageNo, pageSize }),
    { keyOf: (item) => item.dramaId }
)

const pendingId = ref(0)

function open(item) {
    if (!item.title) {
        return
    }
    router.push(`/drama/${item.dramaId}`)
}

/**
 * 取消收藏后直接从列表移除。
 */
async function cancelFavorite(item) {
    if (pendingId.value) {
        return
    }
    pendingId.value = item.dramaId
    try {
        await unfavoriteDrama(item.dramaId)
        remove(item.dramaId)
        toast.success('已取消收藏')
    } catch (cause) {
        toast.push(cause?.message || '取消收藏失败')
    } finally {
        pendingId.value = 0
    }
}
</script>

<template>
    <div class="sub-page">
        <PageHeader title="我的收藏" back-to="/me"/>

        <main class="app-main">
            <div v-if="loading" class="list-skeleton">
                <SkeletonBlock v-for="index in 5" :key="index" height="92px" radius="14px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <template v-else>
                <EmptyState
                    v-if="!items.length"
                    title="还没有收藏"
                    description="在短剧详情页点收藏，之后就能在这里找到它。"
                    action-text="去首页看看"
                    @action="router.push('/')"
                />

                <div v-else class="collection-list">
                    <CollectionItem
                        v-for="item in items"
                        :key="item.dramaId"
                        :item="item"
                        time-label="收藏于"
                        :time-value="item.favoriteTime"
                        action-text="取消收藏"
                        :action-loading="pendingId === item.dramaId"
                        :layout="isDesktop ? 'grid' : 'row'"
                        @open="open"
                        @action="cancelFavorite"
                    />
                </div>

                <LoadMore
                    v-if="items.length"
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
.list-skeleton,
.collection-list {
    display: grid;
    gap: 10px;
}

@media (min-width: 1024px) {
    .list-skeleton,
    .collection-list {
        grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
        gap: 14px;
    }
}
</style>
