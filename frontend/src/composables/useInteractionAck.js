import { getCurrentScope, onScopeDispose } from 'vue'
import { fetchEventStatus } from '../api/interaction'

const ACK_DELAYS = [0, 300, 800, 1500]

/**
 * 异步互动确认：按 0/300/800/1500ms 轮询事件状态。
 *
 * 页面隐藏时暂停，恢复后继续；状态 SENT 且已有消费组处理时触发 onConsumed，
 * DEAD 触发 onDead，超过四轮仍未消费触发 onTimeout。
 */
export function useInteractionAck() {
    const tasks = new Map()
    let paused = false

    function clearTimer(task) {
        if (task?.timer) {
            window.clearTimeout(task.timer)
            task.timer = 0
        }
    }

    function schedule(task) {
        if (paused || task.done) {
            return
        }
        clearTimer(task)
        const delay = ACK_DELAYS[Math.min(task.attempt, ACK_DELAYS.length - 1)]
        task.timer = window.setTimeout(() => poll(task), delay)
    }

    async function poll(task) {
        if (paused || task.done) {
            return
        }
        try {
            const status = await fetchEventStatus(task.eventId)
            if (task.done || paused) {
                return
            }
            task.handlers?.onUpdate?.(status)
            if (status?.status === 2) {
                finish(task, 'onDead', status)
                return
            }
            if (status?.status === 1 && Array.isArray(status.consumedGroups)
                && status.consumedGroups.length > 0) {
                finish(task, 'onConsumed', status)
                return
            }
        } catch {
            // 网络抖动按剩余轮次重试，超时后交给页面降级
        }
        task.attempt += 1
        if (task.attempt >= ACK_DELAYS.length) {
            finish(task, 'onTimeout')
            return
        }
        schedule(task)
    }

    function finish(task, callback, payload) {
        task.done = true
        clearTimer(task)
        tasks.delete(task.eventId)
        task.handlers?.[callback]?.(payload)
    }

    /**
     * 跟踪一个事件；返回取消函数。
     */
    function track(eventId, handlers = {}) {
        if (!eventId) {
            return () => {}
        }
        stop(eventId)
        const task = { eventId, handlers, attempt: 0, timer: 0, done: false }
        tasks.set(eventId, task)
        schedule(task)
        return () => stop(eventId)
    }

    /**
     * 停止单个事件轮询。
     */
    function stop(eventId) {
        const task = tasks.get(eventId)
        if (task) {
            task.done = true
            clearTimer(task)
            tasks.delete(eventId)
        }
    }

    /**
     * 停止所有轮询。
     */
    function stopAll() {
        tasks.forEach((task) => {
            task.done = true
            clearTimer(task)
        })
        tasks.clear()
    }

    function onVisibilityChange() {
        paused = document.visibilityState === 'hidden'
        if (!paused) {
            tasks.forEach(schedule)
        } else {
            tasks.forEach(clearTimer)
        }
    }

    document.addEventListener('visibilitychange', onVisibilityChange)
    if (getCurrentScope()) {
        onScopeDispose(() => {
            document.removeEventListener('visibilitychange', onVisibilityChange)
            stopAll()
        })
    }

    return { track, stop, stopAll }
}
