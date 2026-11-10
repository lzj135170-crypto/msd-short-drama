let lockCount = 0
let savedOverflow = ''

/**
 * 引用计数式 body 滚动锁，避免 Feed 与弹层各自保存/恢复导致残留 hidden。
 */
export function lockBodyScroll() {
    if (lockCount === 0) {
        savedOverflow = document.body.style.overflow
        document.body.style.overflow = 'hidden'
    }
    lockCount += 1
}

export function unlockBodyScroll() {
    if (lockCount <= 0) {
        return
    }
    lockCount -= 1
    if (lockCount === 0) {
        document.body.style.overflow = savedOverflow
        savedOverflow = ''
    }
}
