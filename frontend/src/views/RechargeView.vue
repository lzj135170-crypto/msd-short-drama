<script setup>
import { ref } from 'vue'
import {
    createRechargeOrder,
    fetchPoints,
    fetchPointsLedger,
    fetchRechargePlans,
    mockPayOrder
} from '../api/order'
import { usePagedList } from '../composables/usePagedList'
import { useToastStore } from '../stores/toast'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import LoadMore from '../components/LoadMore.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const toast = useToastStore()

const plans = ref([])
const plansLoading = ref(true)
const plansError = ref('')
const points = ref(null)
const pointsLoading = ref(true)
const payingPlanId = ref(0)

const {
    items: ledgerItems,
    total: ledgerTotal,
    loading: ledgerLoading,
    loadingMore,
    error: ledgerError,
    loadMoreError,
    hasMore,
    refresh: refreshLedger,
    loadMore: loadMoreLedger
} = usePagedList(
    ({ pageNo, pageSize }) => fetchPointsLedger({ pageNo, pageSize }),
    { keyOf: (item) => item.ledgerId }
)

/**
 * 加载上架档位。
 */
async function loadPlans() {
    plansLoading.value = true
    plansError.value = ''
    try {
        plans.value = await fetchRechargePlans()
    } catch (cause) {
        plansError.value = cause?.message || '充值档位加载失败'
    } finally {
        plansLoading.value = false
    }
}

/**
 * 加载当前积分余额。
 */
async function loadBalance() {
    pointsLoading.value = true
    try {
        points.value = await fetchPoints()
    } catch {
        points.value = null
    } finally {
        pointsLoading.value = false
    }
}

/**
 * 生成符合后端校验的客户端幂等键。
 */
function newRequestId() {
    if (globalThis.crypto?.randomUUID) {
        return globalThis.crypto.randomUUID()
    }
    return `rc-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * 创建订单并模拟支付，成功后刷新余额和流水。
 */
async function pay(plan) {
    if (payingPlanId.value) {
        return
    }
    payingPlanId.value = plan.planId
    try {
        const order = await createRechargeOrder({
            planId: plan.planId,
            clientRequestId: newRequestId()
        })
        const result = await mockPayOrder(order.orderId)
        toast.success(result.alreadyPaid
            ? '订单已支付'
            : `充值成功，到账 ${result.points} 积分`)
        await Promise.all([loadBalance(), refreshLedger()])
    } catch (cause) {
        toast.push(cause?.message || '充值失败，请稍后再试')
    } finally {
        payingPlanId.value = 0
    }
}

/**
 * 价格分转元，整数不显示小数。
 */
function formatPrice(cents) {
    return (Number(cents || 0) / 100).toFixed(2).replace(/\.00$/, '')
}

/**
 * 流水类型文案。
 */
function ledgerType(type) {
    return {
        1: '充值',
        2: '解锁剧集',
        3: '人工调整'
    }[type] || '积分变动'
}

/**
 * 带符号的积分变动。
 */
function signedPoints(value) {
    const number = Number(value) || 0
    return number > 0 ? `+${number}` : String(number)
}

loadPlans()
loadBalance()
</script>

<template>
    <div class="sub-page">
        <PageHeader title="充值中心" back-to="/me"/>

        <main class="app-main recharge-page">
            <div class="recharge-main">
            <section class="balance-card">
                <div class="balance-copy">
                    <span class="eyebrow">当前积分</span>
                    <strong v-if="pointsLoading">…</strong>
                    <strong v-else>{{ points?.balance ?? 0 }}</strong>
                </div>
                <p>充值后自动开通积分账户，可直接用于解锁付费剧集。</p>
            </section>

            <section class="section-block">
                <div class="section-title">
                    <h2>选择档位</h2>
                    <span>模拟支付</span>
                </div>

                <div v-if="plansLoading" class="plan-grid">
                    <SkeletonBlock v-for="index in 3" :key="index" height="150px" radius="16px"/>
                </div>

                <ErrorState v-else-if="plansError" :message="plansError" @retry="loadPlans"/>

                <div v-else class="plan-grid">
                    <article
                        v-for="plan in plans"
                        :key="plan.planId"
                        class="plan-card"
                        :class="{ 'is-featured': plan.planCode === 'standard' }"
                    >
                        <span class="plan-name">{{ plan.name }}</span>
                        <strong class="plan-price">¥{{ formatPrice(plan.priceCents) }}</strong>
                        <span class="plan-points">{{ plan.totalPoints }} 积分</span>
                        <span class="plan-detail">
                            {{ plan.basePoints }} 基础<template v-if="plan.bonusPoints"> + {{ plan.bonusPoints }} 赠送</template>
                        </span>
                        <button
                            class="btn btn-primary btn-small"
                            type="button"
                            :disabled="payingPlanId !== 0"
                            @click="pay(plan)"
                        >
                            {{ payingPlanId === plan.planId ? '支付中…' : '模拟支付' }}
                        </button>
                    </article>
                </div>

                <p class="recharge-note">
                    演示环境：点击后直接模拟支付成功，不会调用真实支付渠道。
                </p>
            </section>
            </div>

            <section class="section-block recharge-history">
                <div class="section-title">
                    <h2>积分流水</h2>
                    <button class="text-btn" type="button" @click="refreshLedger">刷新</button>
                </div>

                <div v-if="ledgerLoading" class="ledger-skeleton">
                    <SkeletonBlock v-for="index in 3" :key="index" height="64px" radius="12px"/>
                </div>

                <ErrorState v-else-if="ledgerError" :message="ledgerError" @retry="refreshLedger"/>

                <EmptyState
                    v-else-if="!ledgerItems.length"
                    title="还没有积分流水"
                    description="充值或解锁付费剧集后，记录会显示在这里。"
                />

                <template v-else>
                    <ul class="ledger-list">
                        <li v-for="item in ledgerItems" :key="item.ledgerId" class="ledger-row">
                            <div class="ledger-main">
                                <strong>{{ ledgerType(item.changeType) }}</strong>
                                <span>{{ item.remark || '积分变动' }}</span>
                                <time>{{ formatDateTime(item.createTime) }}</time>
                            </div>
                            <div class="ledger-amount" :class="{ income: item.changePoints > 0 }">
                                <strong>{{ signedPoints(item.changePoints) }}</strong>
                                <span>余额 {{ item.balanceAfter }}</span>
                            </div>
                        </li>
                    </ul>

                    <LoadMore
                        :loading="loadingMore"
                        :has-more="hasMore"
                        :error="loadMoreError"
                        :total="ledgerTotal"
                        @load="loadMoreLedger"
                    />
                </template>
            </section>
        </main>
    </div>
</template>

<style scoped>
.recharge-page {
    display: grid;
    gap: 16px;
}

.recharge-main {
    display: grid;
    gap: 16px;
}

.balance-card {
    display: grid;
    gap: 8px;
    padding: 18px;
    border: 1px solid var(--line);
    border-radius: 18px;
    background:
        radial-gradient(260px 140px at 100% 0%, rgba(47, 174, 122, 0.18), transparent 72%),
        linear-gradient(135deg, var(--surface), #fff6f1);
    box-shadow: var(--shadow-soft);
}

.balance-copy {
    display: flex;
    align-items: baseline;
    gap: 12px;
}

.balance-copy strong {
    color: var(--brand-2);
    font-size: 34px;
    font-weight: 900;
    letter-spacing: -0.02em;
}

.balance-card p {
    margin: 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
}

.section-block {
    display: grid;
    gap: 10px;
}

.plan-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
}

.plan-card {
    display: grid;
    gap: 6px;
    justify-items: center;
    padding: 14px 8px 10px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--panel);
    box-shadow: var(--shadow-soft);
    text-align: center;
}

.plan-card.is-featured {
    border-color: rgba(255, 107, 74, 0.55);
    background:
        radial-gradient(180px 100px at 50% 0%, rgba(255, 107, 74, 0.13), transparent 75%),
        var(--panel);
}

.plan-name {
    color: var(--muted);
    font-size: 11px;
}

.plan-price {
    color: var(--text);
    font-size: 22px;
    font-weight: 900;
    letter-spacing: -0.02em;
}

.plan-points {
    color: var(--brand-2);
    font-size: 13px;
    font-weight: 800;
}

.plan-detail {
    min-height: 14px;
    color: var(--muted-soft);
    font-size: 9.5px;
    line-height: 1.4;
}

.plan-card .btn {
    width: 100%;
    margin-top: 2px;
    padding-right: 4px;
    padding-left: 4px;
}

.recharge-note {
    margin: 0;
    color: var(--muted-soft);
    font-size: 10.5px;
    line-height: 1.6;
}

.ledger-skeleton,
.ledger-list {
    display: grid;
    gap: 8px;
}

.ledger-list {
    margin: 0;
    padding: 0;
    list-style: none;
}

.ledger-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 12px 14px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
    box-shadow: var(--shadow-soft);
}

.ledger-main {
    display: grid;
    min-width: 0;
    gap: 3px;
}

.ledger-main strong {
    font-size: 13px;
}

.ledger-main span {
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.ledger-main time {
    color: var(--muted-soft);
    font-size: 10px;
}

.ledger-amount {
    display: grid;
    flex: none;
    justify-items: end;
    gap: 3px;
}

.ledger-amount strong {
    color: var(--text);
    font-size: 15px;
}

.ledger-amount.income strong {
    color: var(--ok);
}

.ledger-amount span {
    color: var(--muted);
    font-size: 10px;
}

.text-btn {
    padding: 0;
    border: none;
    background: none;
    color: var(--brand-2);
    font-size: 11px;
}

/* ---------- 桌面端（≥1024）：左侧充值，右侧流水 ---------- */

@media (min-width: 1024px) {
    .recharge-main {
        gap: 20px;
    }

    .balance-card {
        padding: 24px;
    }

    .balance-copy strong {
        font-size: 42px;
    }

    .plan-grid {
        gap: 14px;
    }

    .plan-card {
        padding: 22px 12px 16px;
    }

    .plan-price {
        font-size: 26px;
    }

    .recharge-history {
        position: sticky;
        top: calc(var(--topbar-height) + 24px);
    }

    .ledger-row {
        padding: 14px 16px;
    }
}

/* 视口足够宽时才分两栏，避免 1024 下档位卡片过窄 */
@media (min-width: 1280px) {
    .recharge-page {
        grid-template-columns: minmax(0, 1.1fr) minmax(400px, 0.9fr);
        align-items: start;
        gap: 24px;
    }

    .recharge-history {
        position: sticky;
        top: calc(var(--topbar-height) + 24px);
    }
}

@media (max-width: 380px) {
    .plan-grid {
        gap: 7px;
    }

    .plan-card {
        padding-right: 6px;
        padding-left: 6px;
    }

    .plan-price {
        font-size: 19px;
    }
}
</style>
