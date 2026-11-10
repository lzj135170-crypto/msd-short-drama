const FLAG_LABELS = {
    drama: '短剧资料不可用',
    summary: '本集摘要缺失',
    context: '前情检索降级',
    vector: '向量检索降级',
    bm25: '关键词检索降级',
    rerank: '精排降级',
    generation: '回答生成降级',
    episode_index: '分集索引未建立',
    memory_summary: '会话记忆降级',
    personalization: '个性化降级',
    timeout: '响应超时',
    not_found: '剧集不可见',
    locked: '本集未解锁',
    summary_missing: '摘要暂未生成',
    invalid_input: '消息包含不支持的指令',
    no_result: '没有找到匹配内容',
    access_check: '权限校验暂不可用',
    output_guard: '回答已做安全处理',
    audit_timeout: 'AI 审核超时',
    audit_parse_error: 'AI 审核结果解析失败',
    audit_unavailable: 'AI 审核暂不可用',
    audit_empty_content: '审核内容为空',
    audit_input_guard: '审核内容包含不支持的指令',
    audit_serialize_error: 'AI 审核结果保存失败',
    rex_unavailable: '情感/标签建议暂不可用',
    feature_disabled: 'AI 功能暂未开放',
    budget_exceeded: '今日 AI 额度已用完'
}

/**
 * 把后端降级标记转成可读中文；未知标记统一兜底，避免裸英文。
 */
export function aiFlagLabel(flag) {
    return FLAG_LABELS[flag] || '部分资料不可用'
}

/**
 * 批量转换降级标记并去重。
 */
export function aiFlagLabels(flags) {
    if (!Array.isArray(flags)) {
        return []
    }
    return [...new Set(flags.map(aiFlagLabel))]
}

/**
 * 把请求异常转成用户能理解的文案，不直接展示技术细节。
 */
export function aiErrorText(cause) {
    if (cause?.status === 429) {
        return '问得有点快，歇一下再试'
    }
    if (cause?.name === 'AbortError' || cause?.code === -2) {
        return '这次等待太久了，可以重试一次'
    }
    if (cause?.status >= 500) {
        return 'AI 暂时忙不过来，请稍后再试'
    }
    return cause?.message || '网络不稳定，请稍后重试'
}
