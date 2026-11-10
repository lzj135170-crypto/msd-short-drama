package com.msd.order.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 充值下单、模拟支付和积分流水测试。
 */
class RechargeServiceTest {

    private final RechargePlanMapper planMapper = mock(RechargePlanMapper.class);
    private final RechargeOrderMapper orderMapper = mock(RechargeOrderMapper.class);
    private final PointsAccountMapper accountMapper = mock(PointsAccountMapper.class);
    private final PointsLedgerMapper ledgerMapper = mock(PointsLedgerMapper.class);

    private final RechargeService service = new RechargeService(
            planMapper, orderMapper, accountMapper, ledgerMapper);

    /**
     * 档位视图计算总积分。
     */
    @Test
    void listsActivePlans() {
        when(planMapper.selectActive()).thenReturn(List.of(plan(4002L, 3000L, 300L, 30L)));

        List<RechargePlanView> plans = service.listPlans();

        assertThat(plans).singleElement().satisfies(item -> {
            assertThat(item.planId()).isEqualTo(4002L);
            assertThat(item.totalPoints()).isEqualTo(330L);
            assertThat(item.priceCents()).isEqualTo(3000L);
        });
    }

    /**
     * 新订单快照档位价格和积分。
     */
    @Test
    void createsOrderWithPlanSnapshot() {
        when(orderMapper.selectByUserAndRequest(1003L, "req-0001")).thenReturn(null);
        when(planMapper.selectById(4002L)).thenReturn(plan(4002L, 3000L, 300L, 30L));
        when(orderMapper.insert(any(RechargeOrder.class))).thenReturn(1);

        RechargeOrderView view = service.createOrder(1003L,
                new CreateRechargeOrderRequest(4002L, "req-0001"));

        assertThat(view.orderNo()).startsWith("R");
        assertThat(view.amountCents()).isEqualTo(3000L);
        assertThat(view.points()).isEqualTo(330L);
        assertThat(view.status()).isZero();
        assertThat(view.payChannel()).isEqualTo(1);
    }

    /**
     * 相同 clientRequestId 返回原订单，不重复插入。
     */
    @Test
    void returnsExistingOrderForSameRequest() {
        RechargeOrder existing = order(7002L, 1003L, 0, 60L);
        when(orderMapper.selectByUserAndRequest(1003L, "req-0001")).thenReturn(existing);

        RechargeOrderView view = service.createOrder(1003L,
                new CreateRechargeOrderRequest(4001L, "req-0001"));

        assertThat(view.orderId()).isEqualTo(7002L);
        verify(orderMapper, never()).insert(any(RechargeOrder.class));
    }

    /**
     * 下架档位返回参数错误。
     */
    @Test
    void rejectsInactivePlan() {
        when(orderMapper.selectByUserAndRequest(1003L, "req-0001")).thenReturn(null);
        RechargePlan plan = plan(4001L, 600L, 60L, 0L);
        plan.setStatus(0);
        when(planMapper.selectById(4001L)).thenReturn(plan);

        assertThatThrownBy(() -> service.createOrder(1003L,
                new CreateRechargeOrderRequest(4001L, "req-0001")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40000));
    }

    /**
     * 并发插入相同幂等键时返回已提交订单。
     */
    @Test
    void returnsConcurrentOrderOnDuplicateKey() {
        RechargeOrder existing = order(7002L, 1003L, 0, 60L);
        when(orderMapper.selectByUserAndRequest(1003L, "req-0001"))
                .thenReturn(null, existing);
        when(planMapper.selectById(4001L)).thenReturn(plan(4001L, 600L, 60L, 0L));
        when(orderMapper.insert(any(RechargeOrder.class))).thenThrow(new DuplicateKeyException("dup"));

        RechargeOrderView view = service.createOrder(1003L,
                new CreateRechargeOrderRequest(4001L, "req-0001"));

        assertThat(view.orderId()).isEqualTo(7002L);
    }

    /**
     * 模拟支付成功后写流水并返回最新余额。
     */
    @Test
    void mockPayCreditsPointsAndWritesLedger() {
        RechargeOrder order = order(7002L, 1003L, 0, 60L);
        order.setOrderNo("R7002");
        order.setPlanName("体验包");
        when(orderMapper.selectByIdAndUserForUpdate(7002L, 1003L)).thenReturn(order);
        when(orderMapper.markPaid(eq(7002L), eq(1003L), any())).thenReturn(1);
        when(accountMapper.ensureAccount(1003L)).thenReturn(1);
        PointsAccount account = new PointsAccount();
        account.setUserId(1003L);
        account.setBalance(250L);
        when(accountMapper.selectForUpdate(1003L)).thenReturn(account);
        when(accountMapper.credit(1003L, 60L)).thenReturn(1);

        MockPayResult result = service.mockPay(1003L, 7002L);

        assertThat(result.alreadyPaid()).isFalse();
        assertThat(result.status()).isEqualTo(1);
        assertThat(result.points()).isEqualTo(60L);
        assertThat(result.balance()).isEqualTo(310L);

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        PointsLedger ledger = captor.getValue();
        assertThat(ledger.getChangeType()).isEqualTo(1);
        assertThat(ledger.getChangePoints()).isEqualTo(60L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(310L);
        assertThat(ledger.getBizType()).isEqualTo(1);
        assertThat(ledger.getBizId()).isEqualTo(7002L);
        assertThat(ledger.getIdempotencyKey()).isEqualTo("order:7002");
    }

    /**
     * 已支付订单重复调用不再加积分。
     */
    @Test
    void mockPayIsIdempotentForPaidOrder() {
        RechargeOrder order = order(7002L, 1003L, 1, 60L);
        when(orderMapper.selectByIdAndUserForUpdate(7002L, 1003L)).thenReturn(order);
        PointsAccount account = new PointsAccount();
        account.setUserId(1003L);
        account.setBalance(310L);
        when(accountMapper.selectById(1003L)).thenReturn(account);

        MockPayResult result = service.mockPay(1003L, 7002L);

        assertThat(result.alreadyPaid()).isTrue();
        assertThat(result.balance()).isEqualTo(310L);
        verify(orderMapper, never()).markPaid(any(), any(), any());
        verify(ledgerMapper, never()).insert(any(PointsLedger.class));
    }

    /**
     * 已超时订单标记过期并返回冲突。
     */
    @Test
    void mockPayRejectsExpiredOrder() {
        RechargeOrder order = order(7002L, 1003L, 0, 60L);
        order.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(orderMapper.selectByIdAndUserForUpdate(7002L, 1003L)).thenReturn(order);
        when(orderMapper.expireIfPending(7002L, 1003L)).thenReturn(1);

        assertThatThrownBy(() -> service.mockPay(1003L, 7002L))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40900));
        verify(orderMapper, never()).markPaid(any(), any(), any());
    }

    /**
     * 流水分页映射字段。
     */
    @Test
    void listsLedgerPage() {
        PointsLedger ledger = new PointsLedger();
        ledger.setId(7101L);
        ledger.setChangeType(1);
        ledger.setChangePoints(330L);
        ledger.setBalanceAfter(330L);
        ledger.setBizType(1);
        ledger.setBizId(7001L);
        ledger.setRemark("充值 标准包");
        ledger.setCreateTime(LocalDateTime.of(2026, 10, 25, 12, 0));
        when(ledgerMapper.countByUser(1003L)).thenReturn(1L);
        when(ledgerMapper.selectPageByUser(1003L, 0, 20)).thenReturn(List.of(ledger));

        PageResult<PointsLedgerView> page = service.getLedger(1003L, 1, 20);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).singleElement().satisfies(item -> {
            assertThat(item.ledgerId()).isEqualTo(7101L);
            assertThat(item.changePoints()).isEqualTo(330L);
            assertThat(item.balanceAfter()).isEqualTo(330L);
        });
    }

    /**
     * 构造上架档位。
     */
    private RechargePlan plan(Long id, Long priceCents, Long basePoints, Long bonusPoints) {
        RechargePlan plan = new RechargePlan();
        plan.setId(id);
        plan.setPlanCode("test-" + id);
        plan.setName("测试档位");
        plan.setPriceCents(priceCents);
        plan.setBasePoints(basePoints);
        plan.setBonusPoints(bonusPoints);
        plan.setStatus(1);
        return plan;
    }

    /**
     * 构造订单。
     */
    private RechargeOrder order(Long id, Long userId, Integer status, Long points) {
        RechargeOrder order = new RechargeOrder();
        order.setId(id);
        order.setOrderNo("R" + id);
        order.setUserId(userId);
        order.setPlanId(4001L);
        order.setPlanName("体验包");
        order.setAmountCents(600L);
        order.setPoints(points);
        order.setStatus(status);
        order.setPayChannel(1);
        order.setClientRequestId("req-0001");
        order.setExpireTime(LocalDateTime.now().plusMinutes(30));
        return order;
    }
}
