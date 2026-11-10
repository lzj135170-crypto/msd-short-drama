import { computed, getCurrentScope, onScopeDispose, ref } from 'vue'

/**
 * 手写分页列表：加载更多、刷新、本地增删。
 *
 * @param fetcher 接收 { pageNo, pageSize }，返回 PageResult
 * @param options pageSize、keyOf、immediate
 */
export function usePagedList(fetcher, options = {}) {
    const {
        pageSize = 20,
        keyOf = (item) => item?.commentId ?? item?.dramaId ?? item?.episodeId ?? item?.id,
        immediate = true
    } = options

    const items = ref([])
    const total = ref(0)
    const pageNo = ref(1)
    const loading = ref(false)
    const loadingMore = ref(false)
    const error = ref('')
    const loadMoreError = ref('')
    const initialized = ref(false)

    let generation = 0
    let disposed = false

    if (getCurrentScope()) {
        onScopeDispose(() => {
            disposed = true
            generation += 1
        })
    }

    const hasMore = computed(() => items.value.length < total.value)
    const isEmpty = computed(() => initialized.value && !loading.value && items.value.length === 0)

    function keyFor(item) {
        return keyOf(item)
    }

    /**
     * 合并新一页并按 key 去重。
     */
    function merge(existing, incoming) {
        const seen = new Set(existing.map(keyFor))
        const merged = [...existing]
        incoming.forEach((item) => {
            const key = keyFor(item)
            if (key !== undefined && key !== null && seen.has(key)) {
                return
            }
            seen.add(key)
            merged.push(item)
        })
        return merged
    }

    /**
     * 拉取指定页；append 为 true 时追加，否则替换。
     */
    async function fetchPage(targetPage, append) {
        if (disposed) {
            return
        }
        const current = ++generation
        if (append) {
            loadingMore.value = true
            loadMoreError.value = ''
        } else {
            loading.value = true
            error.value = ''
        }

        try {
            const result = await fetcher({ pageNo: targetPage, pageSize })
            if (disposed || current !== generation) {
                return
            }
            const records = Array.isArray(result?.records) ? result.records : []
            items.value = append ? merge(items.value, records) : records
            total.value = Number(result?.total ?? records.length)
            pageNo.value = targetPage
        } catch (cause) {
            if (disposed || current !== generation) {
                return
            }
            const message = cause?.message || '加载失败，请稍后重试'
            if (append) {
                loadMoreError.value = message
            } else {
                error.value = message
            }
        } finally {
            if (!disposed && current === generation) {
                loading.value = false
                loadingMore.value = false
                initialized.value = true
            }
        }
    }

    /**
     * 重新加载第一页。
     */
    function refresh() {
        return fetchPage(1, false)
    }

    /**
     * 加载下一页。
     */
    function loadMore() {
        if (loading.value || loadingMore.value || !hasMore.value) {
            return Promise.resolve()
        }
        return fetchPage(pageNo.value + 1, true)
    }

    /**
     * 本地插入一条并修正总数。
     */
    function prepend(item) {
        const key = keyFor(item)
        if (key !== undefined && items.value.some((existing) => keyFor(existing) === key)) {
            return
        }
        items.value = [item, ...items.value]
        total.value += 1
        initialized.value = true
    }

    /**
     * 本地删除一条并修正总数。
     */
    function remove(key) {
        const before = items.value.length
        items.value = items.value.filter((item) => keyFor(item) !== key)
        if (items.value.length < before) {
            total.value = Math.max(0, total.value - 1)
        }
    }

    /**
     * 本地追加一条并修正总数。
     */
    function append(item) {
        const key = keyFor(item)
        if (key !== undefined && items.value.some((existing) => keyFor(existing) === key)) {
            return
        }
        items.value = [...items.value, item]
        total.value += 1
        initialized.value = true
    }

    /**
     * 本地更新一条。
     */
    function patch(key, changes) {
        items.value = items.value.map((item) => (
            keyFor(item) === key ? { ...item, ...changes } : item
        ))
    }

    if (immediate) {
        refresh()
    }

    return {
        items,
        total,
        pageNo,
        loading,
        loadingMore,
        error,
        loadMoreError,
        initialized,
        hasMore,
        isEmpty,
        refresh,
        loadMore,
        prepend,
        append,
        remove,
        patch
    }
}
