<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchLikedDramas, unlikeTarget } from '../api/interaction'
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
    ({ pageNo, pageSize }) => fetchLikedDramas({ pageNo, pageSize }),
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
 * 取消点赞后直接从列表移除。
 */
async function cancelLike(item) {
    if (pendingId.value) {
        return
    }
    pendingId.value = item.dramaId
    try {
        await unlikeTarget(1, item.dramaId)
        remove(item.dramaId)
        toast.success('已取消点赞')
    } catch (cause) {
        toast.push(cause?.message || '取消点赞失败')
    } finally {
        pendingId.value = 0
    }
}
</script>

<template>
    <div class="sub-page">
        <PageHeader title="点赞的短剧" back-to="/me"/>

        <main class="app-main">
            <div v-if="loading" class="list-skeleton">
                <SkeletonBlock v-for="index in 5" :key="index" height="92px" radius="14px"/>
            </div>

            <ErrorState v-else-if="error" :message="error" @retry="refresh"/>

            <template v-else>
                <EmptyState
                    v-if="!items.length"
                    title="还没有点赞"
                    description="喜欢哪部短剧，就在详情页点个赞。"
                    action-text="去首页看看"
                    @action="router.push('/')"
                />

                <div v-else class="collection-list">
                    <CollectionItem
                        v-for="item in items"
                        :key="item.dramaId"
                        :item="item"
                        time-label="点赞于"
                        :time-value="item.likedTime"
                        action-text="取消点赞"
                        :action-loading="pendingId === item.dramaId"
                        :layout="isDesktop ? 'grid' : 'row'"
                        @open="open"
                        @action="cancelLike"
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
