package com.msd.order.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.msd.common.dto.PageQuery;
import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
import com.msd.common.result.ResultCode;
import com.msd.order.dto.CreateRechargeOrderRequest;
import com.msd.order.dto.MockPayResult;
import com.msd.order.dto.PointsLedgerView;
import com.msd.order.dto.RechargeOrderView;
import com.msd.order.dto.RechargePlanView;
import com.msd.order.entity.PointsAccount;
import com.msd.order.entity.PointsLedger;
import com.msd.order.entity.RechargeOrder;
import com.msd.order.entity.RechargePlan;
import com.msd.order.mapper.PointsAccountMapper;
import com.msd.order.mapper.PointsLedgerMapper;
import com.msd.order.mapper.RechargeOrderMapper;
import com.msd.order.mapper.RechargePlanMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 积分充值与流水服务。
 *
 * <p>下单按 (user_id, client_request_id) 幂等；模拟支付用条件 UPDATE 串行化，
 * 入账和流水在同一本地事务中完成。</p>
 */
@Service
public class RechargeService {

    /** 订单状态：待支付。 */
    private static final int STATUS_PENDING = 0;
    /** 订单状态：已支付。 */
    private static final int STATUS_PAID = 1;
    /** 支付渠道：MOCK。 */
    private static final int PAY_CHANNEL_MOCK = 1;
    /** 积分变动类型：充值。 */
    private static final int CHANGE_TYPE_RECHARGE = 1;
    /** 业务类型：订单。 */
    private static final int BIZ_TYPE_ORDER = 1;
    /** 订单支付超时分钟数。 */
    private static final int EXPIRE_MINUTES = 30;

    // 充值档位 Mapper
    private final RechargePlanMapper planMapper;
    // 充值订单 Mapper
    private final RechargeOrderMapper orderMapper;
    // 积分账户 Mapper
    private final PointsAccountMapper accountMapper;
    // 积分流水 Mapper
    private final PointsLedgerMapper ledgerMapper;

    /**
     * 注入档位、订单、账户和流水 Mapper。
     */
    public RechargeService(RechargePlanMapper planMapper,
                           RechargeOrderMapper orderMapper,
                           PointsAccountMapper accountMapper,
                           PointsLedgerMapper ledgerMapper) {
        this.planMapper = planMapper;
        this.orderMapper = orderMapper;
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
    }

    /**
     * 查询上架充值档位。
     */
    public List<RechargePlanView> listPlans() {
        // 1. 按排序读取上架档位并计算总积分
        return planMapper.selectActive().stream()
                .map(plan -> new RechargePlanView(
                        plan.getId(),
                        plan.getPlanCode(),
                        plan.getName(),
                        nullToZero(plan.getPriceCents()),
                        nullToZero(plan.getBasePoints()),
                        nullToZero(plan.getBonusPoints()),
                        nullToZero(plan.getBasePoints()) + nullToZero(plan.getBonusPoints())))
                .toList();
    }

    /**
     * 创建充值订单；同一 clientRequestId 重复请求返回原订单。
     */
    public RechargeOrderView createOrder(Long userId, CreateRechargeOrderRequest request) {
        // 1. 先按幂等键查询，重复下单直接返回
        RechargeOrder existing = orderMapper.selectByUserAndRequest(
                userId, request.clientRequestId());
        if (existing != null) {
            return toView(existing);
        }

        // 2. 校验档位上架状态并快照价格和积分
        RechargePlan plan = planMapper.selectById(request.planId());
        if (plan == null || !Integer.valueOf(1).equals(plan.getStatus())) {
            throw new BizException(ResultCode.PARAM_ERROR, "充值档位不存在或已下架");
        }
        long base = nullToZero(plan.getBasePoints());
        long bonus = nullToZero(plan.getBonusPoints());

        // 3. 落库待支付订单，orderNo 使用雪花 ID 加前缀
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo("R" + IdWorker.getIdStr());
        order.setUserId(userId);
        order.setPlanId(plan.getId());
        order.setPlanName(plan.getName());
        order.setAmountCents(nullToZero(plan.getPriceCents()));
        order.setPoints(base + bonus);
        order.setStatus(STATUS_PENDING);
        order.setPayChannel(PAY_CHANNEL_MOCK);
        order.setClientRequestId(request.clientRequestId());
        order.setExpireTime(LocalDateTime.now().plusMinutes(EXPIRE_MINUTES));
        order.setCreateTime(LocalDateTime.now());
        order.setRemark("");
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException ex) {
            // 4. 并发同 clientRequestId：读取已提交订单按幂等返回
            RechargeOrder concurrent = orderMapper.selectByUserAndRequest(
                    userId, request.clientRequestId());
            if (concurrent == null) {
                throw ex;
            }
            return toView(concurrent);
        }
        return toView(order);
    }

    /**
     * 查询当前用户的订单；不存在或不属于当前用户时返回 404。
     */
    public RechargeOrderView getOrder(Long userId, Long orderId) {
        RechargeOrder order = orderMapper.selectByIdAndUser(orderId, userId);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return toView(order);
    }

    /**
     * 模拟支付并给积分入账；重复支付不重复加积分。
     */
    @Transactional
    public MockPayResult mockPay(Long userId, Long orderId) {
        // 1. 锁定订单行，串行化同一订单的并发支付
        RechargeOrder order = orderMapper.selectByIdAndUserForUpdate(orderId, userId);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (Integer.valueOf(STATUS_PAID).equals(order.getStatus())) {
            return new MockPayResult(orderId, order.getOrderNo(), STATUS_PAID,
                    nullToZero(order.getPoints()), balance(userId), true);
        }
        if (!Integer.valueOf(STATUS_PENDING).equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单已取消或已过期");
        }

        // 2. 待支付但已超时，先标记过期
        if (order.getExpireTime() != null
                && order.getExpireTime().isBefore(LocalDateTime.now())) {
            orderMapper.expireIfPending(orderId, userId);
            throw new BizException(ResultCode.CONFLICT, "订单已过期");
        }

        // 3. 条件更新为已支付，0 行时按幂等或冲突处理
        String payTradeNo = "MOCK" + order.getOrderNo();
        if (orderMapper.markPaid(orderId, userId, payTradeNo) != 1) {
            RechargeOrder latest = orderMapper.selectByIdAndUser(orderId, userId);
            if (latest != null && Integer.valueOf(STATUS_PAID).equals(latest.getStatus())) {
                return new MockPayResult(orderId, latest.getOrderNo(), STATUS_PAID,
                        nullToZero(latest.getPoints()), balance(userId), true);
            }
            throw new BizException(ResultCode.CONFLICT, "订单支付状态已变化");
        }

        // 4. 自动创建积分账户并锁定余额行
        accountMapper.ensureAccount(userId);
        PointsAccount account = accountMapper.selectForUpdate(userId);
        if (account == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "积分账户创建失败");
        }

        // 5. 增加余额并写充值流水，幂等键固定为 order:{orderId}
        long points = nullToZero(order.getPoints());
        long balanceAfter = account.getBalance() + points;
        if (accountMapper.credit(userId, points) != 1) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "积分入账失败");
        }
        PointsLedger ledger = new PointsLedger();
        ledger.setUserId(userId);
        ledger.setChangeType(CHANGE_TYPE_RECHARGE);
        ledger.setChangePoints(points);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType(BIZ_TYPE_ORDER);
        ledger.setBizId(orderId);
        ledger.setIdempotencyKey("order:" + orderId);
        ledger.setRemark("充值 " + order.getPlanName());
        ledgerMapper.insert(ledger);

        // 6. 返回最新余额，前端据此刷新积分卡片
        return new MockPayResult(orderId, order.getOrderNo(), STATUS_PAID,
                points, balanceAfter, false);
    }

    /**
     * 分页查询当前用户积分流水。
     */
    public PageResult<PointsLedgerView> getLedger(Long userId,
                                                  Integer pageNo,
                                                  Integer pageSize) {
        // 1. 归一化分页参数
        PageQuery page = PageQuery.of(pageNo, pageSize);
        long total = ledgerMapper.countByUser(userId);
        if (total <= 0) {
            return PageResult.empty(page.pageNo(), page.pageSize());
        }
        // 2. 查询当前页并转换为视图
        List<PointsLedgerView> records = ledgerMapper.selectPageByUser(
                        userId, (int) page.offset(), page.pageSize())
                .stream()
                .map(ledger -> new PointsLedgerView(
                        ledger.getId(),
                        ledger.getChangeType(),
                        ledger.getChangePoints(),
                        ledger.getBalanceAfter(),
                        ledger.getBizType(),
                        ledger.getBizId(),
                        ledger.getRemark(),
                        ledger.getCreateTime()))
                .toList();
        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 查询当前余额，账户不存在时返回 0。
     */
    private long balance(Long userId) {
        PointsAccount account = accountMapper.selectById(userId);
        return account == null ? 0L : account.getBalance();
    }

    /**
     * 订单实体转视图。
     */
    private RechargeOrderView toView(RechargeOrder order) {
        return new RechargeOrderView(
                order.getId(),
                order.getOrderNo(),
                order.getPlanId(),
                order.getPlanName(),
                nullToZero(order.getAmountCents()),
                nullToZero(order.getPoints()),
                order.getStatus(),
                order.getPayChannel(),
                order.getClientRequestId(),
                order.getExpireTime(),
                order.getPayTime(),
                order.getCreateTime());
    }

    /**
     * null 按 0 处理。
     */
    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
