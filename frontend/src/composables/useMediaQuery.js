import { onScopeDispose, readonly, ref } from 'vue'

/**
 * 响应式断点：封装 matchMedia，初始化时同步取值并监听变化。
 *
 * @param query 媒体查询字符串，例如 '(min-width: 1024px)'
 * @returns 只读的布尔 ref，组件卸载时自动移除监听
 */
export function useMediaQuery(query) {
    const supported = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    const media = supported ? window.matchMedia(query) : null
    const matches = ref(media ? media.matches : false)

    if (media) {
        const onChange = (event) => {
            matches.value = event.matches
        }
        // 1. 优先使用 addEventListener，老内核回退到 addListener
        if (typeof media.addEventListener === 'function') {
            media.addEventListener('change', onChange)
            onScopeDispose(() => media.removeEventListener('change', onChange))
        } else if (typeof media.addListener === 'function') {
            media.addListener(onChange)
            onScopeDispose(() => media.removeListener(onChange))
        }
    }

    return readonly(matches)
}
