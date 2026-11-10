import { reportHistory } from '../api/interaction'

/**
 * 观看历史上报器。
 *
 * <p>同一时刻只发一个请求，期间的新位置合并为最新快照，避免旧位置后到覆盖新位置。
 * 完成态只发一次 finished=true，之后拒绝任何非完成上报。</p>
 */
export function useWatchReporter({ enabled, snapshot }) {
    let busy = false
    let pending = null
    let locked = false
    let lastPosition = -1
    let lastFinished = false
    let disposed = false
    const waiters = []

    function notifyIdle() {
        if (busy || pending || disposed) {
            return
        }
        waiters.splice(0).forEach((resolve) => resolve())
    }

    function same(data) {
        return data.positionMs === lastPosition && data.finished === lastFinished
    }

    async function deliver(item) {
        const data = item.snapshot
        if (!enabled.value || !data) {
            return
        }
        if (locked && !data.finished) {
            return
        }
        if (same(data)) {
            return
        }
        try {
            await reportHistory({
                dramaId: Number(data.dramaId),
                episodeId: Number(data.episodeId),
                positionMs: Math.max(0, Math.round(Number(data.positionMs) || 0)),
                durationMs: Math.max(0, Math.round(Number(data.durationMs) || 0)),
                finished: Boolean(data.finished)
            }, { keepalive: item.keepalive })
            lastPosition = data.positionMs
            lastFinished = Boolean(data.finished)
        } catch {
            // 周期上报失败静默，等下一次；401 由全局事件处理
        }
    }

    async function drain() {
        if (busy || disposed) {
            return
        }
        if (!pending) {
            notifyIdle()
            return
        }
        const item = pending
        pending = null
        busy = true
        try {
            await deliver(item)
        } finally {
            busy = false
            if (pending && !disposed) {
                void drain()
            } else {
                notifyIdle()
            }
        }
    }

    function enqueue(data, { force = false, keepalive = false } = {}) {
        if (disposed || !enabled.value || !data) {
            return
        }
        if (locked && !data.finished) {
            return
        }
        if (!force && same(data)) {
            return
        }
        pending = { snapshot: data, keepalive }
        void drain()
    }

    function report(options = {}) {
        enqueue(snapshot(), options)
    }

    /**
     * 完成态：锁定上报器并发送最终快照。
     */
    function finish() {
        const data = snapshot()
        if (!data) {
            return
        }
        locked = true
        pending = {
            snapshot: { ...data, positionMs: data.durationMs, finished: true },
            keepalive: false
        }
        void drain()
    }

    /**
     * 路由离开时等待队列清空，最多等 timeoutMs。
     */
    async function flush({ keepalive = false, timeoutMs = 1500 } = {}) {
        enqueue(snapshot(), { force: true, keepalive })
        if (!busy && !pending) {
            return
        }
        await new Promise((resolve) => {
            let settled = false
            const done = () => {
                if (!settled) {
                    settled = true
                    resolve()
                }
            }
            waiters.push(done)
            window.setTimeout(done, timeoutMs)
        })
    }

    function dispose() {
        disposed = true
        pending = null
        notifyIdle()
    }

    return { report, finish, flush, dispose }
}
