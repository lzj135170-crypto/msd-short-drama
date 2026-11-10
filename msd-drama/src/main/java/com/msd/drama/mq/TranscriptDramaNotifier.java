package com.msd.drama.mq;

import com.msd.common.event.DramaChangedAction;
import com.msd.drama.mapper.EpisodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 短剧审核通过后补发字幕索引事件。
 *
 * <p>草稿期导入的字幕在剧集不可见时不会进入索引；审核通过后需要为已有字幕的剧集
 * 重新触发 transcript UPSERT。</p>
 */
@Component
public class TranscriptDramaNotifier {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(TranscriptDramaNotifier.class);

    // 剧集 Mapper
    private final EpisodeMapper episodeMapper;
    // 字幕事件发布器
    private final TranscriptChangedPublisher publisher;

    /**
     * 注入剧集 Mapper 和字幕事件发布器。
     */
    public TranscriptDramaNotifier(EpisodeMapper episodeMapper,
                                   TranscriptChangedPublisher publisher) {
        this.episodeMapper = episodeMapper;
        this.publisher = publisher;
    }

    /**
     * 为短剧下有字幕的剧集发布 UPSERT。
     */
    public void publishForDrama(Long dramaId) {
        if (dramaId == null || dramaId <= 0) {
            return;
        }
        try {
            List<Long> episodeIds = episodeMapper.selectEpisodeIdsWithTranscript(dramaId);
            for (Long episodeId : episodeIds) {
                publisher.publishAfterCommit(episodeId, DramaChangedAction.UPSERT);
            }
        } catch (Exception e) {
            log.warn("transcript notify for drama failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
        }
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
