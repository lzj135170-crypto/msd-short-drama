package com.msd.order.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.order.client.DramaClient;
import com.msd.order.dto.EpisodeInfoDTO;
import com.msd.order.dto.UnlockRefRequest;
import com.msd.order.dto.UnlockResult;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 解锁编排：读取剧集信息、执行本地事务、调用 drama 写解锁计数。
 * 整个方法由 Seata 全局事务包住，任一分支失败都会回滚。
 */
@Service
public class UnlockFacade {

    // drama Feign 客户端，查询剧集信息并写解锁引用
    private final DramaClient dramaClient;
    // 本地解锁事务服务
    private final UnlockLocalService unlockLocalService;
    // 环境配置，用于读取演示开关 msd.tx.demo.fail-after-remote
    private final Environment environment;

    /**
     * 注入 drama 客户端、本地解锁服务和环境配置。
     */
    public UnlockFacade(DramaClient dramaClient,
                        UnlockLocalService unlockLocalService,
                        Environment environment) {
        this.dramaClient = dramaClient;
        this.unlockLocalService = unlockLocalService;
        this.environment = environment;
    }

    /**
     * 解锁剧集；免费集不写任何表。
     */
    @GlobalTransactional(name = "unlock-episode", rollbackFor = Exception.class, timeoutMills = 30000)
    public UnlockResult unlock(Long userId, Long episodeId) {
        return doUnlock(userId, episodeId, false);
    }

    /**
     * 测试台入口：远程分支成功后强制失败，用于验证 Seata 全局回滚。
     */
    @GlobalTransactional(name = "unlock-episode-demo", rollbackFor = Exception.class, timeoutMills = 30000)
    public UnlockResult unlockWithForcedFailure(Long userId, Long episodeId) {
        return doUnlock(userId, episodeId, true);
    }

    /**
     * 解锁共享逻辑；forceFail 为 true 时在远程调用成功后抛异常。
     */
    private UnlockResult doUnlock(Long userId, Long episodeId, boolean forceFail) {
        // 1. 调 drama 查询剧集价格和状态
        Result<EpisodeInfoDTO> episodeResult = dramaClient.getEpisode(episodeId);
        if (!episodeResult.isSuccess()) {
            throw new BizException(episodeResult.getCode(), episodeResult.getMessage());
        }
        EpisodeInfoDTO episode = episodeResult.getData();
        if (episode == null) {
            throw new BizException(ResultCode.NOT_FOUND, "剧集不存在");
        }

        // 2. 已下线的剧集不允许解锁
        if (!Integer.valueOf(1).equals(episode.status())) {
            throw new BizException(ResultCode.CONFLICT, "剧集已下线");
        }

        // 3. 父短剧未上架时不允许解锁
        if (!episode.dramaPublished()) {
            throw new BizException(ResultCode.NOT_FOUND, "短剧未上架");
        }

        // 4. 免费集直接返回；强制失败模式没有远程分支，直接报错避免假回滚
        long pointsCost = episode.pricePoints() == null ? 0L : episode.pricePoints();
        if (pointsCost == 0L) {
            if (forceFail) {
                throw new BizException(ResultCode.PARAM_ERROR, "免费集没有远程分支，无法演示回滚");
            }
            return new UnlockResult(0L, episodeId, 0L, 0L, false);
        }

        // 5. 在本地事务内扣积分、写流水、写解锁记录
        LocalUnlockResult local = unlockLocalService.unlock(
                userId, episode.dramaId(), episodeId, pointsCost);

        // 6. 已解锁时不再调用 drama；强制失败模式提示换一集
        if (local.alreadyUnlocked()) {
            if (forceFail) {
                throw new BizException(ResultCode.CONFLICT, "该剧集已解锁，请换一集演示回滚");
            }
            return new UnlockResult(local.unlockId(), episodeId, pointsCost,
                    local.balanceAfter(), true);
        }

        // 7. 首次解锁，调用 drama 写引用并增加解锁计数
        Result<Boolean> refResult = dramaClient.addUnlockRef(
                episodeId, new UnlockRefRequest(local.unlockId(), userId));
        if (!refResult.isSuccess()) {
            throw new BizException(refResult.getCode(), refResult.getMessage());
        }

        // 8. 远程成功后主动抛异常：测试台强制失败，或 Nacos 演示开关打开
        if (forceFail || Boolean.TRUE.equals(environment.getProperty(
                "msd.tx.demo.fail-after-remote", Boolean.class, Boolean.FALSE))) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "演示全局事务回滚");
        }

        // 9. 返回解锁结果
        return new UnlockResult(local.unlockId(), episodeId, pointsCost,
                local.balanceAfter(), false);
    }
}
