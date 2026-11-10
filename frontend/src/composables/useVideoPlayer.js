import { computed, onBeforeUnmount, ref } from 'vue'

/**
 * 真实 <video> 播放控制：对外暴露和旧占位播放器一致的 API。
 *
 * 由 MockPlayer 通过函数 ref 调用 attach(el) 绑定元素；
 * durationMs 优先取视频元数据，元数据未就绪时回退到接口时长。
 *
 * @param fallbackDurationMs 后端返回的时长（毫秒）的 ref
 */
export function useVideoPlayer(fallbackDurationMs) {
    const videoEl = ref(null)
    const positionMs = ref(0)
    const playing = ref(false)
    const finished = ref(false)
    const started = ref(false)
    const mediaDurationMs = ref(0)
    const muted = ref(true)

    let pendingSeekMs = 0
    // 首次激活时 <video> 可能还没挂载，先记住播放意图，attach 后再执行
    let pendingPlay = false

    const durationMs = computed(() => (
        mediaDurationMs.value || Number(fallbackDurationMs.value) || 0
    ))

    const progress = computed(() => {
        const duration = durationMs.value
        return duration > 0
            ? Math.min(100, Math.max(0, Math.round(positionMs.value / duration * 100)))
            : 0
    })

    function onLoadedMetadata() {
        const element = videoEl.value
        if (!element) {
            return
        }
        const duration = Number(element.duration)
        if (Number.isFinite(duration) && duration > 0) {
            mediaDurationMs.value = duration * 1000
        }
        if (pendingSeekMs > 0) {
            seekTo(pendingSeekMs)
            pendingSeekMs = 0
        }
    }

    function onTimeUpdate() {
        const element = videoEl.value
        if (!element) {
            return
        }
        positionMs.value = Math.min(durationMs.value, element.currentTime * 1000)
    }

    function onPlay() {
        playing.value = true
        started.value = true
    }

    function onPause() {
        playing.value = false
    }

    function onEnded() {
        playing.value = false
        finished.value = true
        positionMs.value = durationMs.value
    }

    function onError() {
        playing.value = false
    }

    function detach() {
        const element = videoEl.value
        if (!element) {
            return
        }
        element.removeEventListener('loadedmetadata', onLoadedMetadata)
        element.removeEventListener('timeupdate', onTimeUpdate)
        element.removeEventListener('play', onPlay)
        element.removeEventListener('pause', onPause)
        element.removeEventListener('ended', onEnded)
        element.removeEventListener('error', onError)
        videoEl.value = null
    }

    /**
     * 函数 ref：挂载时绑定元素，卸载时解绑。
     */
    function attach(element) {
        if (videoEl.value === element) {
            return
        }
        detach()
        if (!element) {
            return
        }
        videoEl.value = element
        element.muted = muted.value
        element.addEventListener('loadedmetadata', onLoadedMetadata)
        element.addEventListener('timeupdate', onTimeUpdate)
        element.addEventListener('play', onPlay)
        element.addEventListener('pause', onPause)
        element.addEventListener('ended', onEnded)
        element.addEventListener('error', onError)
        if (element.readyState >= 1) {
            onLoadedMetadata()
        }
        if (pendingPlay) {
            pendingPlay = false
            play()
        }
    }

    /**
     * 设置播放位置；元数据未就绪时记录为待应用位置。
     */
    function seekTo(value) {
        const element = videoEl.value
        const duration = durationMs.value
        const target = Math.min(duration, Math.max(0, Math.round(Number(value) || 0)))
        positionMs.value = target
        if (!element) {
            pendingSeekMs = target
            return
        }
        if (element.readyState < 1) {
            pendingSeekMs = target
            return
        }
        try {
            element.currentTime = target / 1000
        } catch {
            pendingSeekMs = target
        }
    }

    function play() {
        if (finished.value) {
            return
        }
        started.value = true
        const element = videoEl.value
        if (!element) {
            pendingPlay = true
            return
        }
        pendingPlay = false
        const result = element.play()
        if (result?.catch) {
            result.catch(() => {
                playing.value = false
            })
        }
    }

    function pause() {
        pendingPlay = false
        videoEl.value?.pause()
    }

    function toggle() {
        if (playing.value) {
            pause()
        } else {
            play()
        }
    }

    function seek(value) {
        if (finished.value) {
            return
        }
        seekTo(value)
    }

    /**
     * 重播只重置本地播放；完成后上报器已锁定，不会再写非完成状态。
     */
    function restart() {
        finished.value = false
        started.value = true
        pendingSeekMs = 0
        seekTo(0)
        play()
    }

    /**
     * 加载完成后重新初始化位置。
     */
    function reset(startMs = 0) {
        pause()
        finished.value = false
        started.value = false
        pendingSeekMs = Math.max(0, Math.round(Number(startMs) || 0))
        seekTo(pendingSeekMs)
    }

    function toggleMuted() {
        muted.value = !muted.value
        if (videoEl.value) {
            videoEl.value.muted = muted.value
        }
    }

    onBeforeUnmount(detach)

    return {
        positionMs,
        playing,
        finished,
        started,
        muted,
        durationMs,
        progress,
        attach,
        play,
        pause,
        toggle,
        seek,
        restart,
        reset,
        toggleMuted
    }
}
