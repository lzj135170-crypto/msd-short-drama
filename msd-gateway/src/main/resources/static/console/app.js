'use strict';

/* ============================================================
 * 1. 状态与常量
 * ============================================================ */

const STORAGE = {
    token: 'msd.console.token',
    expiresAt: 'msd.console.expiresAt',
    userId: 'msd.console.userId',
    role: 'msd.console.role',
};

const STATUS_LABELS = {
    0: 'PENDING',
    1: 'SENT',
    2: 'DEAD',
};

const state = {
    token: sessionStorage.getItem(STORAGE.token) || '',
    expiresAt: Number(sessionStorage.getItem(STORAGE.expiresAt) || 0),
    userId: sessionStorage.getItem(STORAGE.userId) || '1003',
    role: sessionStorage.getItem(STORAGE.role) || 'USER',
    lastEventId: '',
    lastCommentId: null,
    statsKey: '',
    counterPolling: false,
    burstRunning: false,
};

const seata = {
    episode: null,
    points: null,
    unlockState: null,
    lastResult: '',
};

class ApiError extends Error {
    constructor(message, status, body) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
        this.body = body;
    }
}

/* ============================================================
 * 2. 通用工具
 * ============================================================ */

function el(id) {
    return document.getElementById(id);
}

function val(id) {
    return el(id).value;
}

function num(id) {
    return Number(val(id));
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function setBadge(id, text, kind) {
    const node = el(id);
    node.textContent = text;
    node.className = `badge ${kind || 'muted'}`;
}

function setText(id, text) {
    el(id).textContent = text;
}

function displayValue(value) {
    if (value === null || value === undefined || value === '') {
        return '-';
    }
    if (typeof value === 'boolean') {
        return value ? '是' : '否';
    }
    if (typeof value === 'object') {
        return JSON.stringify(value);
    }
    return String(value);
}

function formatTime(value) {
    if (!value) {
        return '-';
    }
    return String(value).replace('T', ' ').replace(/(\.\d{3})\d*/, '$1');
}

function log(message, level, detail) {
    const box = el('log');
    const row = document.createElement('div');
    row.className = `log-entry ${level || 'info'}`;

    const time = document.createElement('span');
    time.className = 'log-time';
    time.textContent = new Date().toLocaleTimeString('zh-CN');

    const text = document.createElement('span');
    text.textContent = message;
    row.append(time, text);

    if (detail !== undefined) {
        const pre = document.createElement('pre');
        pre.textContent = typeof detail === 'string' ? detail : JSON.stringify(detail, null, 2);
        row.append(pre);
    }

    box.prepend(row);
    while (box.children.length > 200) {
        box.lastChild.remove();
    }
}

function resultGrid(targetId, items) {
    const box = el(targetId);
    box.replaceChildren();
    for (const [label, value] of items) {
        if (value === undefined || value === null || value === '') {
            continue;
        }
        const item = document.createElement('div');
        item.className = 'result-item';
        const labelNode = document.createElement('span');
        labelNode.textContent = label;
        const valueNode = document.createElement('span');
        valueNode.textContent = displayValue(value);
        item.append(labelNode, valueNode);
        box.append(item);
    }
    if (!box.children.length) {
        const empty = document.createElement('span');
        empty.className = 'muted';
        empty.textContent = '-';
        box.append(empty);
    }
}

/* ============================================================
 * 3. API 封装
 * ============================================================ */

async function api(path, options = {}) {
    const method = options.method || 'GET';
    const auth = options.auth !== false;
    const silent = options.silent === true;
    const headers = {};
    if (options.body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }
    if (auth && state.token) {
        headers.Authorization = `Bearer ${state.token}`;
    }

    const started = performance.now();
    let response;
    let text;
    try {
        response = await fetch(path, {
            method,
            headers,
            body: options.body === undefined ? undefined : JSON.stringify(options.body),
        });
        text = await response.text();
    } catch (error) {
        if (!silent) {
            log(`${method} ${path} 网络错误：${error.message}`, 'error');
        }
        throw new ApiError(`网络错误：${error.message}`, 0, null);
    }

    let body = null;
    try {
        body = text ? JSON.parse(text) : null;
    } catch {
        body = text;
    }

    const elapsed = Math.round(performance.now() - started);
    if (!silent) {
        log(`${method} ${path} → HTTP ${response.status}（${elapsed} ms）`,
            response.ok ? 'info' : 'warn');
    }

    if (response.status === 401 && auth) {
        clearToken('登录已过期，请重新连接');
    }

    if (!response.ok || (body && typeof body === 'object' && body.success === false)) {
        const message = body && typeof body === 'object' && body.message
            ? body.message : `HTTP ${response.status}`;
        throw new ApiError(message, response.status, body);
    }

    return body && typeof body === 'object' && 'data' in body ? body.data : body;
}

function hasToken() {
    return Boolean(state.token) && Date.now() < state.expiresAt;
}

function clearToken(message) {
    state.token = '';
    state.expiresAt = 0;
    sessionStorage.removeItem(STORAGE.token);
    sessionStorage.removeItem(STORAGE.expiresAt);
    renderTokenStatus();
    if (message) {
        log(message, 'warn');
    }
}

function requireToken() {
    if (!hasToken()) {
        log('请先点击右上角「连接」获取 Token', 'warn');
        return false;
    }
    return true;
}

/* ============================================================
 * 4. 连接与 Token
 * ============================================================ */

function renderTokenStatus() {
    const badge = el('tokenStatus');
    if (!state.token) {
        badge.textContent = '未连接';
        badge.className = 'badge muted';
        return;
    }
    const left = state.expiresAt - Date.now();
    if (left <= 0) {
        badge.textContent = '已过期';
        badge.className = 'badge danger';
        return;
    }
    const minutes = Math.floor(left / 60000);
    const seconds = Math.floor((left % 60000) / 1000);
    badge.textContent = `已连接 ${state.userId}/${state.role} · ${minutes}:${String(seconds).padStart(2, '0')}`;
    badge.className = 'badge ok';
}

async function connect() {
    const userId = val('userId').trim() || '1003';
    const role = val('role');
    setBadge('tokenStatus', '连接中…', 'warn');
    try {
        const data = await api('/internal/dev/token', {
            method: 'POST',
            auth: false,
            body: { userId, role },
        });
        state.token = data.token;
        state.expiresAt = Date.now() + data.expiresInSeconds * 1000;
        state.userId = String(data.userId);
        state.role = data.role;
        sessionStorage.setItem(STORAGE.token, state.token);
        sessionStorage.setItem(STORAGE.expiresAt, String(state.expiresAt));
        sessionStorage.setItem(STORAGE.userId, state.userId);
        sessionStorage.setItem(STORAGE.role, state.role);
        renderTokenStatus();
        log(`已连接用户 ${state.userId}，角色 ${state.role}，issuer=${data.issuer}`, 'ok');
        await refreshAll();
    } catch (error) {
        setBadge('tokenStatus', '连接失败', 'danger');
        log(`连接失败：${error.message}`, 'error', error.body);
    }
}

/* ============================================================
 * 5. 服务健康
 * ============================================================ */

const HEALTH_SERVICES = [
    { name: 'gateway', path: '/actuator/health', auth: false },
    { name: 'user', path: '/api/user/internal/ping', auth: true },
    { name: 'drama', path: '/api/drama/internal/ping', auth: true },
    { name: 'order', path: '/api/order/internal/ping', auth: true },
    { name: 'interaction', path: '/api/interaction/internal/ping', auth: true },
];

async function refreshHealth() {
    const box = el('healthChips');
    box.replaceChildren();
    const results = await Promise.all(HEALTH_SERVICES.map(async (service) => {
        try {
            await api(service.path, { auth: service.auth, silent: true });
            return { service, ok: true };
        } catch (error) {
            return { service, ok: false, status: error.status };
        }
    }));
    for (const result of results) {
        const chip = document.createElement('span');
        chip.className = `badge ${result.ok ? 'ok' : 'danger'}`;
        chip.textContent = result.ok
            ? `${result.service.name} UP`
            : `${result.service.name} DOWN${result.status ? ` (${result.status})` : ''}`;
        box.append(chip);
    }
}

/* ============================================================
 * 6. Seata 解锁
 * ============================================================ */

function renderSeata() {
    const items = [
        ['解锁剧集', seata.episode ? seata.episode.episodeId : null],
        ['所属短剧', seata.episode ? seata.episode.dramaId : null],
        ['价格 / 状态', seata.episode
            ? `${seata.episode.pricePoints} 积分 / ${seata.episode.status === 1 ? '上线' : '下线'}` : null],
        ['解锁次数', seata.episode ? seata.episode.unlockCount : null],
        ['积分余额', seata.points
            ? `${seata.points.balance}${seata.points.exists === false ? '（无账户）' : ''}` : null],
        ['解锁状态', seata.unlockState
            ? (seata.unlockState.unlocked
                ? `已解锁（unlockId=${seata.unlockState.unlockId}，花费 ${seata.unlockState.pointsCost}）`
                : '未解锁') : null],
        ['最近结果', seata.lastResult],
    ];
    resultGrid('seataResult', items);
}

async function queryEpisode(silent = false, episodeId) {
    const id = episodeId || val('unlockEpisodeId').trim();
    const episode = await api(`/api/drama/internal/episodes/${id}`, { silent });
    seata.episode = episode;
    renderSeata();
    return episode;
}

async function queryPoints(silent = false) {
    const points = await api('/api/order/internal/orders/points', { silent });
    seata.points = points;
    renderSeata();
    return points;
}

async function queryUnlockState(silent = false, episodeId) {
    const id = episodeId || val('unlockEpisodeId').trim();
    const unlockState = await api(
        `/api/order/internal/orders/episodes/${id}/unlock-state`, { silent });
    seata.unlockState = unlockState;
    renderSeata();
    return unlockState;
}

async function unlockEpisode() {
    if (!requireToken()) {
        return;
    }
    const id = val('unlockEpisodeId').trim();
    setBadge('seataStatus', '解锁中…', 'warn');
    try {
        const result = await api(`/api/order/internal/orders/episodes/${id}/unlock`, {
            method: 'POST',
        });
        seata.lastResult = result.alreadyUnlocked
            ? `已解锁，未重复扣费（unlockId=${result.unlockId}，余额=${result.balanceAfter}）`
            : `解锁成功：扣 ${result.pointsCost} 积分，余额 ${result.balanceAfter}`;
        setBadge('seataStatus', result.alreadyUnlocked ? '已解锁' : '解锁成功', 'ok');
        await Promise.allSettled([
            queryPoints(true),
            queryUnlockState(true),
            queryEpisode(true),
        ]);
        log(`解锁 ${id} 完成`, 'ok', result);
    } catch (error) {
        seata.lastResult = `解锁失败：${error.message}`;
        setBadge('seataStatus', '解锁失败', 'danger');
        log(`解锁 ${id} 失败`, 'error', error.body);
        await Promise.allSettled([queryPoints(true), queryUnlockState(true), queryEpisode(true)]);
    }
    renderSeata();
}

async function rollbackTest() {
    if (!requireToken()) {
        return;
    }
    const id = val('rollbackEpisodeId').trim();
    setBadge('seataStatus', '回滚测试中…', 'warn');
    try {
        await api(`/api/order/internal/orders/dev/episodes/${id}/unlock-with-failure`, {
            method: 'POST',
        });
        seata.lastResult = '异常：强制失败接口返回成功，未触发回滚';
        setBadge('seataStatus', '未回滚', 'danger');
        log(`回滚测试 ${id} 未按预期失败`, 'error');
    } catch (error) {
        seata.lastResult = `已触发失败：${error.message}（预期 500）`;
        setBadge('seataStatus', '已回滚', 'ok');
        log(`回滚测试 ${id} 触发失败，检查余额和解锁计数是否不变`, 'ok', error.body);
    }

    const [pointsResult, stateResult, episodeResult] = await Promise.allSettled([
        queryPoints(true),
        queryUnlockState(true, id),
        queryEpisode(true, id),
    ]);
    if (episodeResult.status === 'fulfilled') {
        seata.lastResult += `；${id} unlockCount=${episodeResult.value.unlockCount}`;
    }
    if (stateResult.status === 'fulfilled') {
        seata.lastResult += `；unlocked=${stateResult.value.unlocked}`;
    }
    if (pointsResult.status === 'fulfilled') {
        seata.lastResult += `；余额=${pointsResult.value.balance}`;
    }
    renderSeata();
}

/* ============================================================
 * 7. 异步计数
 * ============================================================ */

function statsSignature(stats, episodes) {
    return JSON.stringify([
        stats.viewCount, stats.likeCount, stats.favoriteCount, stats.commentCount,
        episodes.map((episode) => [
            episode.episodeId, episode.playCount, episode.likeCount,
            episode.commentCount, episode.unlockCount,
        ]),
    ]);
}

async function refreshStats(silent = false) {
    const dramaId = val('dramaId').trim();
    const [stats, episodes] = await Promise.all([
        api(`/api/drama/internal/dramas/${dramaId}/stats`, { silent }),
        api(`/api/drama/internal/dramas/${dramaId}/episodes`, { silent }),
    ]);

    setText('statView', stats.viewCount);
    setText('statLike', stats.likeCount);
    setText('statFavorite', stats.favoriteCount);
    setText('statComment', stats.commentCount);
    setText('statUpdated',
        `${stats.title} · ${stats.episodeCount} 集 · ${new Date().toLocaleTimeString('zh-CN')}`);

    const tbody = el('episodeTableBody');
    tbody.replaceChildren();
    const selectedEpisode = String(val('interactionEpisodeId') || '');
    for (const episode of episodes) {
        const row = document.createElement('tr');
        if (String(episode.episodeId) === selectedEpisode) {
            row.className = 'episode-hit';
        }
        const cells = [
            episode.episodeNo,
            episode.title,
            `${episode.pricePoints} 分`,
            episode.status === 1 ? '上线' : '下线',
            episode.playCount,
            episode.likeCount,
            episode.commentCount,
            episode.unlockCount,
        ];
        for (const value of cells) {
            const cell = document.createElement('td');
            cell.textContent = displayValue(value);
            row.append(cell);
        }
        tbody.append(row);
    }
    if (!episodes.length) {
        const row = document.createElement('tr');
        const cell = document.createElement('td');
        cell.colSpan = 8;
        cell.className = 'muted';
        cell.textContent = '该短剧没有剧集';
        row.append(cell);
        tbody.append(row);
    }

    state.statsKey = statsSignature(stats, episodes);
    if (!silent) {
        setBadge('counterStatus', '已刷新', 'ok');
    }
    return state.statsKey;
}

async function pollCounterChange(previousKey) {
    if (state.counterPolling || !previousKey) {
        return;
    }
    state.counterPolling = true;
    setBadge('counterStatus', '等待 Kafka 消费…', 'warn');
    const deadline = Date.now() + 10000;
    try {
        while (Date.now() < deadline) {
            await sleep(500);
            if (document.hidden || state.burstRunning) {
                continue;
            }
            try {
                const currentKey = await refreshStats(true);
                if (currentKey !== previousKey) {
                    setBadge('counterStatus', '计数已更新', 'ok');
                    return;
                }
            } catch (error) {
                log(`轮询计数失败：${error.message}`, 'warn');
            }
        }
        setBadge('counterStatus', '等待超时，请检查 relay/consumer', 'danger');
    } finally {
        state.counterPolling = false;
    }
}

/* ============================================================
 * 8. 互动操作
 * ============================================================ */

function renderInteractionResult(label, result) {
    if (!result || typeof result !== 'object') {
        resultGrid('interactionState', [
            ['操作', label],
            ['结果', result],
        ]);
        return;
    }
    const items = [
        ['操作', label],
        ['active', result.active],
        ['firstWatch', result.firstWatch],
        ['commentId', result.commentId],
        ['eventId', result.eventId],
        ['message', result.message],
    ];
    resultGrid('interactionState', items);
}

function updateCommentState() {
    const hasComment = Boolean(state.lastCommentId);
    setText('commentState',
        hasComment ? `最近评论：${state.lastCommentId}` : '最近评论：无');
    el('likeCommentBtn').disabled = !hasComment;
    el('deleteCommentBtn').disabled = !hasComment;
}

async function refreshInteractionState(silent = false) {
    if (!hasToken()) {
        return;
    }
    const dramaId = val('interactionDramaId').trim();
    const episodeId = val('interactionEpisodeId').trim();
    const results = await Promise.allSettled([
        api(`/api/interaction/internal/interactions/likes/1/${dramaId}`, { silent }),
        api(`/api/interaction/internal/interactions/likes/2/${episodeId}`, { silent }),
        api(`/api/interaction/internal/interactions/favorites/${dramaId}`, { silent }),
    ]);
    const [dramaLike, episodeLike, favorite] = results.map((result) =>
        result.status === 'fulfilled' ? result.value.active : '?');
    setText('socialState',
        `短剧点赞：${displayValue(dramaLike)} · 剧集点赞：${displayValue(episodeLike)} · 收藏：${displayValue(favorite)}`);
    if (!silent) {
        log(`互动状态：短剧点赞=${displayValue(dramaLike)}，剧集点赞=${displayValue(episodeLike)}，收藏=${displayValue(favorite)}`);
    }
}

async function runInteraction(label, action, options = {}) {
    if (!requireToken()) {
        return;
    }
    let beforeKey = state.statsKey;
    if (!beforeKey) {
        try {
            beforeKey = await refreshStats(true);
        } catch (error) {
            beforeKey = '';
        }
    }
    setBadge('interactionStatus', `${label}中…`, 'warn');
    try {
        const result = await action();
        setBadge('interactionStatus', `${label}成功`, 'ok');
        if (result && result.commentId) {
            state.lastCommentId = result.commentId;
            updateCommentState();
        }
        renderInteractionResult(label, result);
        if (result && result.eventId) {
            state.lastEventId = result.eventId;
            el('eventIdInput').value = result.eventId;
            watchOutbox(result.eventId);
            if (options.pollCounter !== false) {
                pollCounterChange(beforeKey);
            }
        } else if (result === true) {
            // 删除评论接口只返回布尔值，没有 eventId；直接刷新 outbox 和计数
            log(`${label} 成功，接口未返回 eventId`, 'ok');
            refreshOutbox(true);
            if (options.pollCounter !== false) {
                pollCounterChange(beforeKey);
            }
        } else {
            setBadge('interactionStatus', `${label}：状态未变化`, 'warn');
            log(`${label} 未产生事件（幂等或状态未变化）`, 'warn', result);
        }
        log(`${label} 完成`, 'ok', result);
        await refreshInteractionState(true);
    } catch (error) {
        setBadge('interactionStatus', `${label}失败`, 'danger');
        renderInteractionResult(`${label}失败`, { message: error.message });
        log(`${label} 失败：${error.message}`, 'error', error.body);
    }
}

function likeTarget(targetType) {
    const targetId = targetType === 1
        ? Number(val('interactionDramaId'))
        : Number(val('interactionEpisodeId'));
    return api('/api/interaction/internal/interactions/likes', {
        method: 'POST',
        body: { targetType, targetId },
    });
}

function unlikeTarget(targetType) {
    const targetId = targetType === 1
        ? Number(val('interactionDramaId'))
        : Number(val('interactionEpisodeId'));
    return api(`/api/interaction/internal/interactions/likes/${targetType}/${targetId}`, {
        method: 'DELETE',
    });
}

function favoriteDrama() {
    return api('/api/interaction/internal/interactions/favorites', {
        method: 'POST',
        body: { dramaId: Number(val('interactionDramaId')) },
    });
}

function unfavoriteDrama() {
    return api(`/api/interaction/internal/interactions/favorites/${val('interactionDramaId')}`, {
        method: 'DELETE',
    });
}

function createComment() {
    return api('/api/interaction/internal/interactions/comments', {
        method: 'POST',
        body: {
            dramaId: Number(val('interactionDramaId')),
            episodeId: Number(val('interactionEpisodeId')),
            content: val('commentContent').trim() || '[test-console] 空内容',
        },
    });
}

function likeComment() {
    if (!state.lastCommentId) {
        log('还没有创建评论，无法点赞', 'warn');
        return Promise.reject(new Error('最近评论不存在'));
    }
    return api('/api/interaction/internal/interactions/likes', {
        method: 'POST',
        body: { targetType: 3, targetId: state.lastCommentId },
    });
}

function deleteComment() {
    if (!state.lastCommentId) {
        log('还没有创建评论，无法删除', 'warn');
        return Promise.reject(new Error('最近评论不存在'));
    }
    const commentId = state.lastCommentId;
    return api(`/api/interaction/internal/interactions/comments/${commentId}`, {
        method: 'DELETE',
    }).then((result) => {
        if (result) {
            state.lastCommentId = null;
            updateCommentState();
        }
        return result;
    });
}

function recordHistory() {
    return api('/api/interaction/internal/interactions/history', {
        method: 'POST',
        body: {
            dramaId: Number(val('interactionDramaId')),
            episodeId: Number(val('interactionEpisodeId')),
            positionMs: 10000,
            durationMs: 120000,
            finished: false,
        },
    });
}

/* ============================================================
 * 9. Outbox
 * ============================================================ */

function outboxStatusBadge(status) {
    if (status === 0) {
        return { text: 'PENDING', kind: 'warn' };
    }
    if (status === 1) {
        return { text: 'SENT', kind: 'ok' };
    }
    return { text: 'DEAD', kind: 'danger' };
}

function renderOutboxDetail(event) {
    if (!event) {
        resultGrid('outboxDetail', [['状态', '未找到事件']]);
        return;
    }
    const badge = outboxStatusBadge(event.status);
    setBadge('outboxStatus', badge.text, badge.kind);
    resultGrid('outboxDetail', [
        ['eventId', event.eventId],
        ['类型', event.eventType],
        ['Topic', event.topic],
        ['状态', `${badge.text}（${event.status}）`],
        ['重试次数', event.retryCount],
        ['下次重试', formatTime(event.nextRetryTime)],
        ['发送时间', formatTime(event.sentAt)],
        ['最后错误', event.lastError],
    ]);
}

async function queryEvent(eventId) {
    const id = eventId || val('eventIdInput').trim() || state.lastEventId;
    if (!id) {
        log('请输入 eventId', 'warn');
        return;
    }
    if (!requireToken()) {
        return;
    }
    el('eventIdInput').value = id;
    try {
        const event = await api(`/api/interaction/internal/interactions/outbox/${id}`);
        renderOutboxDetail(event);
        log(`outbox 事件 ${id}：${STATUS_LABELS[event.status] || event.status}`, 'ok', event);
    } catch (error) {
        setBadge('outboxStatus', '查询失败', 'danger');
        resultGrid('outboxDetail', [['错误', error.message]]);
        log(`查询 outbox 失败：${error.message}`, 'error', error.body);
    }
}

async function watchOutbox(eventId) {
    const delays = [500, 1000, 2000, 3000, 3000];
    for (const delay of delays) {
        await sleep(delay);
        try {
            const event = await api(
                `/api/interaction/internal/interactions/outbox/${eventId}`, { silent: true });
            renderOutboxDetail(event);
            if (event.status !== 0) {
                await refreshOutbox(true);
                return;
            }
        } catch (error) {
            log(`追踪 outbox 失败：${error.message}`, 'warn');
            return;
        }
    }
    setBadge('outboxStatus', '仍未发送，检查 relay', 'danger');
    await refreshOutbox(true);
}

async function refreshOutbox(silent = false) {
    if (!requireToken()) {
        return;
    }
    try {
        const events = await api('/api/interaction/internal/interactions/outbox?limit=10', { silent });
        const tbody = el('outboxTableBody');
        tbody.replaceChildren();
        for (const event of events) {
            const row = document.createElement('tr');
            const badge = outboxStatusBadge(event.status);
            const cells = [
                event.id,
                event.eventType,
                event.topic,
                badge.text,
                event.retryCount,
                formatTime(event.sentAt),
            ];
            for (const value of cells) {
                const cell = document.createElement('td');
                cell.textContent = displayValue(value);
                row.append(cell);
            }
            const actionCell = document.createElement('td');
            const button = document.createElement('button');
            button.className = 'ghost small';
            button.textContent = '查看';
            button.dataset.eventId = event.eventId;
            actionCell.append(button);
            row.append(actionCell);
            tbody.append(row);
        }
        if (!events.length) {
            const row = document.createElement('tr');
            const cell = document.createElement('td');
            cell.colSpan = 7;
            cell.className = 'muted';
            cell.textContent = '暂无 outbox 事件，先做一次点赞/收藏/评论/观看';
            row.append(cell);
            tbody.append(row);
        }
        if (!silent) {
            setBadge('outboxStatus', `已加载 ${events.length} 条`, 'ok');
        }
    } catch (error) {
        log(`加载 outbox 失败：${error.message}`, 'error', error.body);
    }
}

/* ============================================================
 * 10. DLQ
 * ============================================================ */

function renderDlq(inspection) {
    const box = el('dlqMessages');
    box.replaceChildren();
    if (!inspection.topicReady) {
        const note = document.createElement('span');
        note.className = 'muted small';
        note.textContent = inspection.note;
        box.append(note);
        setBadge('dlqStatus', '不可用', 'danger');
        return;
    }
    if (!inspection.messages.length) {
        const note = document.createElement('span');
        note.className = 'muted small';
        note.textContent = `${inspection.note}；当前没有消息`;
        box.append(note);
        setBadge('dlqStatus', '空', 'muted');
        return;
    }
    for (const message of inspection.messages) {
        const item = document.createElement('div');
        item.className = 'dlq-item';
        const meta = document.createElement('div');
        meta.className = 'dlq-meta';
        meta.textContent = `partition=${message.partition} · offset=${message.offset} · key=${message.key || '-'}`;
        const pre = document.createElement('pre');
        pre.textContent = message.value;
        item.append(meta, pre);
        box.append(item);
    }
    setBadge('dlqStatus', `${inspection.messages.length} 条`, 'warn');
}

async function inspectDlq(silent = false) {
    if (!requireToken()) {
        return 0;
    }
    try {
        const inspection = await api(
            '/api/interaction/internal/interactions/dev/dlq-messages?limit=10', { silent });
        renderDlq(inspection);
        return inspection.messages.length;
    } catch (error) {
        setBadge('dlqStatus', '巡检失败', 'danger');
        log(`DLQ 巡检失败：${error.message}`, 'error', error.body);
        return 0;
    }
}

async function injectDlq() {
    if (!requireToken()) {
        return;
    }
    setBadge('dlqStatus', '投递中…', 'warn');
    try {
        const result = await api('/api/interaction/internal/interactions/dev/dlq-event', {
            method: 'POST',
        });
        log(`已投递非法事件 key=${result.key}，${result.hint}`, 'warn', result);
        setBadge('dlqStatus', '等待重试…', 'warn');
        for (let i = 0; i < 10; i += 1) {
            await sleep(1000);
            const count = await inspectDlq(true);
            if (count > 0) {
                setBadge('dlqStatus', '已进入 DLQ', 'ok');
                return;
            }
        }
        setBadge('dlqStatus', '10 秒内未见 DLQ 消息', 'danger');
        log('DLQ 未出现消息：确认 drama 正在运行、DLQ topic 存在', 'warn');
    } catch (error) {
        setBadge('dlqStatus', '投递失败', 'danger');
        log(`DLQ 注入失败：${error.message}`, 'error', error.body);
    }
}

/* ============================================================
 * 11. Sentinel 压测
 * ============================================================ */

async function runBurst() {
    if (!requireToken()) {
        return;
    }
    const count = Math.max(1, Math.min(Number(val('burstCount')) || 60, 200));
    const rounds = Math.max(1, Math.min(Number(val('burstRounds')) || 3, 10));
    const path = `/api/interaction/internal/users/${state.userId}/profile`;

    state.burstRunning = true;
    el('burstBtn').disabled = true;
    el('burstResult').replaceChildren();
    log(`开始 Sentinel 压测：${rounds} 轮 × ${count} 并发`, 'warn');

    try {
        for (let round = 1; round <= rounds; round += 1) {
            const started = performance.now();
            const results = await Promise.allSettled(
                Array.from({ length: count }, () => api(path, { silent: true }))
            );
            const elapsed = Math.round(performance.now() - started);
            const tally = { 200: 0, 429: 0, other: 0 };
            for (const result of results) {
                if (result.status === 'fulfilled') {
                    tally[200] += 1;
                } else if (result.reason && result.reason.status === 429) {
                    tally[429] += 1;
                } else {
                    tally.other += 1;
                }
            }

            const row = document.createElement('div');
            row.className = 'burst-row';
            const title = document.createElement('strong');
            title.textContent = `第 ${round} 轮`;
            row.append(title);
            for (const [label, value, kind] of [
                ['200', tally[200], 'ok'],
                ['429', tally[429], 'danger'],
                ['其他', tally.other, 'muted'],
                ['耗时', `${elapsed} ms`, 'muted'],
            ]) {
                const badge = document.createElement('span');
                badge.className = `badge ${kind}`;
                badge.textContent = `${label} ${value}`;
                row.append(badge);
            }
            el('burstResult').append(row);
            log(`压测第 ${round} 轮：200=${tally[200]}, 429=${tally[429]}, 其他=${tally.other}, ${elapsed}ms`,
                tally[429] > 0 ? 'ok' : 'warn');

            if (round < rounds) {
                await sleep(1000);
            }
        }
    } finally {
        state.burstRunning = false;
        el('burstBtn').disabled = false;
    }
}

/* ============================================================
 * 12. 初始化与事件绑定
 * ============================================================ */

async function refreshAll() {
    await Promise.allSettled([
        refreshHealth(),
        refreshStats(true),
        queryEpisode(true).catch(() => {}),
        queryPoints(true).catch(() => {}),
        queryUnlockState(true).catch(() => {}),
        refreshOutbox(true),
        refreshInteractionState(true),
    ]);
}

function updateLinks() {
    const host = location.hostname || '127.0.0.1';
    el('zipkinLink').href = `http://${host}:9411`;
    el('nacosLink').href = `http://${host}:8080`;
    el('sentinelLink').href = `http://${host}:8858`;
    el('seataLink').href = `http://${host}:9988`;
}

function bindEvents() {
    el('connectBtn').addEventListener('click', connect);
    el('refreshHealthBtn').addEventListener('click', refreshHealth);

    el('queryEpisodeBtn').addEventListener('click', () => {
        queryEpisode(false).catch((error) => log(`查询剧集失败：${error.message}`, 'error', error.body));
    });
    el('queryPointsBtn').addEventListener('click', () => {
        queryPoints(false).catch((error) => log(`查询余额失败：${error.message}`, 'error', error.body));
    });
    el('queryUnlockStateBtn').addEventListener('click', () => {
        queryUnlockState(false).catch((error) => log(`查询解锁状态失败：${error.message}`, 'error', error.body));
    });
    el('unlockBtn').addEventListener('click', unlockEpisode);
    el('rollbackBtn').addEventListener('click', rollbackTest);

    el('refreshStatsBtn').addEventListener('click', () => {
        refreshStats(false).catch((error) => log(`刷新统计失败：${error.message}`, 'error', error.body));
    });
    el('refreshInteractionBtn').addEventListener('click', () => refreshInteractionState(false));

    el('likeDramaBtn').addEventListener('click', () =>
        runInteraction('点赞短剧', () => likeTarget(1)));
    el('unlikeDramaBtn').addEventListener('click', () =>
        runInteraction('取消短剧点赞', () => unlikeTarget(1)));
    el('likeEpisodeBtn').addEventListener('click', () =>
        runInteraction('点赞剧集', () => likeTarget(2)));
    el('unlikeEpisodeBtn').addEventListener('click', () =>
        runInteraction('取消剧集点赞', () => unlikeTarget(2)));
    el('favoriteBtn').addEventListener('click', () =>
        runInteraction('收藏短剧', favoriteDrama));
    el('unfavoriteBtn').addEventListener('click', () =>
        runInteraction('取消收藏', unfavoriteDrama));
    el('createCommentBtn').addEventListener('click', () =>
        runInteraction('创建评论', createComment));
    el('likeCommentBtn').addEventListener('click', () =>
        runInteraction('点赞评论', likeComment, { pollCounter: false }));
    el('deleteCommentBtn').addEventListener('click', () =>
        runInteraction('删除评论', deleteComment));
    el('historyBtn').addEventListener('click', () =>
        runInteraction('首次观看', recordHistory));
    el('repeatHistoryBtn').addEventListener('click', () =>
        runInteraction('重复观看', recordHistory));

    el('queryEventBtn').addEventListener('click', () => queryEvent());
    el('refreshOutboxBtn').addEventListener('click', () => refreshOutbox(false));
    el('outboxTableBody').addEventListener('click', (event) => {
        const button = event.target.closest('button[data-event-id]');
        if (button) {
            queryEvent(button.dataset.eventId);
        }
    });

    el('injectDlqBtn').addEventListener('click', injectDlq);
    el('inspectDlqBtn').addEventListener('click', () => inspectDlq(false));
    el('burstBtn').addEventListener('click', runBurst);
    el('clearLogBtn').addEventListener('click', () => {
        el('log').replaceChildren();
    });
}

function init() {
    el('userId').value = state.userId;
    el('role').value = state.role;
    updateLinks();
    bindEvents();
    updateCommentState();
    renderTokenStatus();

    if (hasToken()) {
        log(`发现未过期 Token，用户 ${state.userId}/${state.role}`, 'info');
        refreshAll();
    } else {
        log('尚未连接，请输入用户 ID 后点击「连接」', 'info');
    }

    setInterval(renderTokenStatus, 1000);
    setInterval(() => {
        if (!document.hidden && !state.burstRunning && hasToken()) {
            refreshStats(true).catch(() => {});
        }
    }, 5000);
}

document.addEventListener('DOMContentLoaded', init);
