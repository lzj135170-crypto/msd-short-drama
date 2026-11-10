const STORAGE_KEY = 'msd.auth'
const AUTH_PREFIX = '/api/user/auth/'

/**
 * 后端统一响应或网络错误。
 *
 * status 是 HTTP 状态码，code 是后端业务码，便于页面区分 401/403/404/409。
 */
export class ApiError extends Error {
    constructor(code, message, status = 0, payload = null, timedOut = false) {
        super(message)
        this.name = 'ApiError'
        this.code = code
        this.status = status
        this.payload = payload
        // 区分内部超时和调用方主动取消：两者共用 code -2，但只有超时需要展示错误
        this.timedOut = timedOut
    }
}

/**
 * 从 localStorage 读取当前 Token，避免和 Pinia 形成循环依赖。
 */
function currentToken() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY)
        return raw ? JSON.parse(raw).token || '' : ''
    } catch {
        return ''
    }
}

/**
 * 拼接查询参数，跳过空值但保留数字 0。
 */
function withQuery(path, query) {
    if (!query) {
        return path
    }
    const params = new URLSearchParams()
    Object.entries(query).forEach(([key, value]) => {
        if (value === undefined || value === null || value === '') {
            return
        }
        params.append(key, String(value))
    })
    const search = params.toString()
    if (!search) {
        return path
    }
    return `${path}${path.includes('?') ? '&' : '?'}${search}`
}

/**
 * 统一请求封装：解析 Result、附加 Token、处理 401。
 */
export async function request(path, options = {}) {
    const {
        method = 'GET',
        body,
        query,
        headers: extraHeaders,
        auth = true,
        authRedirect = true,
        keepalive = false,
        signal,
        timeoutMs = 0
    } = options

    // 1. 组装请求头
    const headers = { Accept: 'application/json', ...extraHeaders }
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json'
    }
    const token = currentToken()
    if (auth && token) {
        headers.Authorization = `Bearer ${token}`
    }

    // 2. 超时和外部取消共用一个 AbortController
    let controller = null
    let timeoutId = null
    let timedOut = false
    let onExternalAbort = null
    if (timeoutMs > 0 || signal) {
        controller = new AbortController()
        if (signal) {
            onExternalAbort = () => controller.abort()
            if (signal.aborted) {
                controller.abort()
            } else {
                signal.addEventListener('abort', onExternalAbort, { once: true })
            }
        }
        if (timeoutMs > 0) {
            timeoutId = setTimeout(() => {
                timedOut = true
                controller.abort()
            }, timeoutMs)
        }
    }

    // 3. 发起请求，网络异常统一转成业务可展示的错误
    const target = withQuery(path, query)
    let response
    try {
        response = await fetch(target, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            keepalive,
            signal: controller?.signal
        })
    } catch (cause) {
        if (cause?.name === 'AbortError') {
            throw new ApiError(
                -2,
                timedOut ? '请求超时，请稍后重试' : '请求已取消',
                0,
                null,
                timedOut
            )
        }
        throw new ApiError(-1, '网络异常，请确认网关和服务已启动')
    } finally {
        if (timeoutId) {
            clearTimeout(timeoutId)
        }
        if (signal && onExternalAbort) {
            signal.removeEventListener('abort', onExternalAbort)
        }
    }

    // 3. 尽量解析统一响应体
    let payload = null
    try {
        payload = await response.json()
    } catch {
        payload = null
    }

    // 4. 非认证接口的 401 触发会话清理
    if (response.status === 401 && authRedirect && !path.startsWith(AUTH_PREFIX)) {
        window.dispatchEvent(new CustomEvent('msd:unauthorized'))
    }

    // 5. HTTP 错误和业务错误都抛 ApiError
    if (!response.ok) {
        throw new ApiError(
            payload?.code ?? response.status,
            payload?.message || `请求失败（HTTP ${response.status}）`,
            response.status,
            payload
        )
    }
    if (!payload || payload.success !== true) {
        throw new ApiError(
            payload?.code ?? -1,
            payload?.message || '请求失败',
            response.status,
            payload
        )
    }

    return payload.data
}

/**
 * 发起 SSE 请求并逐帧回调。
 *
 * @param {string} path 接口路径
 * @param {{method?: string, body?: object, headers?: object, auth?: boolean,
 *   authRedirect?: boolean, signal?: AbortSignal, timeoutMs?: number,
 *   onEvent?: (event: string, data: any) => void}} options 请求选项
 * @returns {Promise<{streamed: boolean}>}
 */
export async function streamRequest(path, options = {}) {
    const {
        method = 'POST',
        body,
        headers: extraHeaders,
        auth = true,
        authRedirect = true,
        signal,
        timeoutMs = 0,
        onEvent
    } = options

    // 1. 组装请求头
    const headers = { Accept: 'text/event-stream', ...extraHeaders }
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json'
    }
    const token = currentToken()
    if (auth && token) {
        headers.Authorization = `Bearer ${token}`
    }

    // 2. 超时和外部取消共用一个 AbortController
    const controller = new AbortController()
    let timeoutId = null
    let timedOut = false
    let onExternalAbort = null
    if (signal) {
        onExternalAbort = () => controller.abort()
        if (signal.aborted) {
            controller.abort()
        } else {
            signal.addEventListener('abort', onExternalAbort, { once: true })
        }
    }
    if (timeoutMs > 0) {
        timeoutId = setTimeout(() => {
            timedOut = true
            controller.abort()
        }, timeoutMs)
    }

    /**
     * 清理超时计时器和外部信号监听。
     */
    function cleanup() {
        if (timeoutId) {
            clearTimeout(timeoutId)
            timeoutId = null
        }
        if (signal && onExternalAbort) {
            signal.removeEventListener('abort', onExternalAbort)
            onExternalAbort = null
        }
    }

    // 3. 发起请求，非 2xx 时尽量解析统一错误体
    let response
    try {
        response = await fetch(path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            signal: controller.signal
        })
    } catch (cause) {
        cleanup()
        if (cause?.name === 'AbortError') {
            throw new ApiError(-2, timedOut ? '请求超时，请稍后重试' : '请求已取消',
                0, null, timedOut)
        }
        throw new ApiError(-1, '网络异常，请确认网关和服务已启动')
    }

    try {
        if (response.status === 401 && authRedirect) {
            window.dispatchEvent(new CustomEvent('msd:unauthorized'))
        }
        if (!response.ok) {
            let payload = null
            try {
                payload = await response.json()
            } catch {
                payload = null
            }
            throw new ApiError(
                payload?.code ?? response.status,
                payload?.message || `请求失败（HTTP ${response.status}）`,
                response.status,
                payload
            )
        }

        // 4. 网关不支持 SSE 时明确抛错，调用方回退普通 JSON 接口
        const contentType = response.headers.get('Content-Type') || ''
        if (!contentType.includes('text/event-stream') || !response.body) {
            throw new ApiError(-3, '流式响应不可用', response.status)
        }

        // 5. 按空行切分事件，逐帧回调
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        try {
            while (true) {
                const { done, value } = await reader.read()
                if (done) {
                    break
                }
                buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
                let boundary = buffer.indexOf('\n\n')
                while (boundary >= 0) {
                    const block = buffer.slice(0, boundary)
                    buffer = buffer.slice(boundary + 2)
                    dispatchSseBlock(block, onEvent)
                    boundary = buffer.indexOf('\n\n')
                }
            }
            if (buffer.trim()) {
                dispatchSseBlock(buffer, onEvent)
            }
        } catch (cause) {
            if (cause?.name === 'AbortError') {
                throw new ApiError(-2, timedOut ? '请求超时，请稍后重试' : '请求已取消',
                    0, null, timedOut)
            }
            throw new ApiError(-1, '流式连接中断，请重试')
        } finally {
            try {
                reader.releaseLock()
            } catch {
                // 连接已关闭时忽略
            }
        }
        return { streamed: true }
    } finally {
        cleanup()
    }
}

/**
 * 解析一个 SSE block 并回调；data 优先按 JSON 解析。
 */
function dispatchSseBlock(block, onEvent) {
    let event = 'message'
    const dataLines = []
    for (const line of block.split('\n')) {
        if (line.startsWith('event:')) {
            event = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trimStart())
        }
    }
    if (!dataLines.length) {
        return
    }
    const raw = dataLines.join('\n')
    let data = raw
    try {
        data = JSON.parse(raw)
    } catch {
        // 非 JSON data 保留原文
    }
    onEvent?.(event, data)
}
