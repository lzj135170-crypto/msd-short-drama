package com.msd.order.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.order.entity.EpisodeUnlock;
import com.msd.order.entity.PointsAccount;
import com.msd.order.entity.PointsLedger;
import com.msd.order.mapper.EpisodeUnlockMapper;
import com.msd.order.mapper.PointsAccountMapper;
import com.msd.order.mapper.PointsLedgerMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解锁的本地事务：扣积分、写流水、写解锁记录。
 */
@Service
public class UnlockLocalService {

    /** 积分变动类型：消费 */
    private static final int CHANGE_TYPE_UNLOCK = 2;
    /** 业务类型：剧集解锁 */
    private static final int BIZ_TYPE_EPISODE_UNLOCK = 2;

    // 积分账户 Mapper
    private final PointsAccountMapper pointsAccountMapper;
    // 积分流水 Mapper
    private final PointsLedgerMapper pointsLedgerMapper;
    // 剧集解锁 Mapper
    private final EpisodeUnlockMapper episodeUnlockMapper;

    /**
     * 注入积分账户、流水和解锁 Mapper。
     */
    public UnlockLocalService(PointsAccountMapper pointsAccountMapper,
                              PointsLedgerMapper pointsLedgerMapper,
                              EpisodeUnlockMapper episodeUnlockMapper) {
        this.pointsAccountMapper = pointsAccountMapper;
        this.pointsLedgerMapper = pointsLedgerMapper;
        this.episodeUnlockMapper = episodeUnlockMapper;
    }

    /**
     * 在本地事务内完成积分扣减和解锁记录写入。
     */
    @Transactional
    public LocalUnlockResult unlock(Long userId, Long dramaId, Long episodeId, Long pointsCost) {
        // 1. 先查解锁记录，重复请求直接返回
        EpisodeUnlock existing = episodeUnlockMapper.selectByUserAndEpisode(userId, episodeId);
        if (existing != null) {
            PointsAccount account = pointsAccountMapper.selectById(userId);
            long balance = account == null ? 0L : account.getBalance();
            return new LocalUnlockResult(existing.getId(), balance, true);
        }

        // 2. 锁定积分账户行，串行化同一用户的并发扣减
        PointsAccount account = pointsAccountMapper.selectForUpdate(userId);
        if (account == null) {
            throw new BizException(ResultCode.CONFLICT, "积分账户不存在");
        }

        // 3. 校验余额
        if (account.getBalance() < pointsCost) {
            throw new BizException(ResultCode.CONFLICT, "积分不足");
        }

        // 4. 写入解锁权益，主键回填到 unlockId
        EpisodeUnlock unlock = new EpisodeUnlock();
        unlock.setUserId(userId);
        unlock.setDramaId(dramaId);
        unlock.setEpisodeId(episodeId);
        unlock.setPointsCost(pointsCost);
        try {
            episodeUnlockMapper.insert(unlock);
        } catch (DuplicateKeyException ex) {
            // 并发请求已经写入同一集：加锁读能看到对方已提交的行，按幂等成功返回
            EpisodeUnlock concurrent = episodeUnlockMapper.selectByUserAndEpisodeForUpdate(userId, episodeId);
            if (concurrent == null) {
                throw ex;
            }
            return new LocalUnlockResult(concurrent.getId(), account.getBalance(), true);
        }

        // 5. 条件扣减余额，影响 0 行说明并发余额已变化
        if (pointsAccountMapper.deduct(userId, pointsCost) != 1) {
            throw new BizException(ResultCode.CONFLICT, "积分扣减失败");
        }

        // 6. 写积分流水，幂等键保证同一用户同一集只记一次
        long balanceAfter = account.getBalance() - pointsCost;
        PointsLedger ledger = new PointsLedger();
        ledger.setUserId(userId);
        ledger.setChangeType(CHANGE_TYPE_UNLOCK);
        ledger.setChangePoints(-pointsCost);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType(BIZ_TYPE_EPISODE_UNLOCK);
        ledger.setBizId(unlock.getId());
        ledger.setIdempotencyKey("unlock:" + userId + ":" + episodeId);
        ledger.setRemark("解锁剧集 " + episodeId);
        pointsLedgerMapper.insert(ledger);

        // 7. 返回本地事务结果
        return new LocalUnlockResult(unlock.getId(), balanceAfter, false);
    }
}
