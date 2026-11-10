/**
 * 解析短剧 AI 审核结果 JSON；旧数据或损坏数据返回 null。
 */
export function parseAiReviewResult(raw) {
    if (!raw) {
        return null
    }
    try {
        const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw
        return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
    } catch {
        return null
    }
}

/**
 * 把 AI 审核状态转成创作者可读的提示。
 *
 * - 管理员已驳回时优先展示驳回提示；
 * - AI 驳回时展示违规原因和命中标签；
 * - AI 失败只提示等待人工复核，不暴露技术标记。
 */
export function aiReviewNotice({ reviewStatus, aiReviewStatus, aiReviewResult }) {
    const status = Number(reviewStatus)
    const aiStatus = Number(aiReviewStatus)
    const payload = parseAiReviewResult(aiReviewResult)
    const verdict = payload?.verdict

    // 1. 已上架/已下架不再提示 AI 风险，避免人工覆盖后仍显示红条
    if (status === 2 || status === 4) {
        return null
    }

    // 2. 人工驳回优先，但如果 AI 同时给出明确违规原因，直接展示原因
    if (status === 3) {
        if (aiStatus === 3 && verdict?.is_violation) {
            return aiViolationNotice(verdict)
        }
        return {
            type: 'danger',
            title: '作品已被驳回',
            text: '请根据审核意见修改后重新提交。',
            tags: []
        }
    }

    // 3. AI 失败或降级时只提示等待人工复核
    if (aiStatus === 4 || payload?.degraded) {
        return {
            type: 'warning',
            title: 'AI 审核未完成',
            text: '等待管理员复核，不需要处理技术问题。',
            tags: []
        }
    }

    // 4. AI 判定违规时展示可读原因
    if (aiStatus === 3 && verdict?.is_violation) {
        return aiViolationNotice(verdict)
    }
    return null
}

/**
 * 构造违规提示，过滤空原因和未知标签。
 */
function aiViolationNotice(verdict) {
    const tags = Array.isArray(verdict?.tags)
        ? verdict.tags.filter((tag) => typeof tag === 'string' && tag.trim())
        : []
    return {
        type: 'danger',
        title: 'AI 发现的风险',
        text: verdict?.violation_reason || '内容存在合规风险，请修改后重新提交。',
        tags
    }
}

/**
 * 读取 Rex 建议标签，仅返回名称列表。
 */
export function aiSuggestedTags(aiReviewResult) {
    const payload = parseAiReviewResult(aiReviewResult)
    const tags = payload?.advisory?.tags
    if (!Array.isArray(tags)) {
        return []
    }
    return tags
        .map((tag) => (typeof tag?.name === 'string' ? tag.name.trim() : ''))
        .filter(Boolean)
}
