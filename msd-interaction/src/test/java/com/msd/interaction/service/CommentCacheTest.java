package com.msd.interaction.service;

import com.msd.interaction.entity.Comment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论首页缓存与失效测试。
 */
class CommentCacheTest {

    /**
     * 同一页命中缓存，创建/删除评论后按短剧失效。
     */
    @Test
    void cachesPageAndInvalidatesDrama() {
        CommentCache cache = new CommentCache(true, 15);
        Comment comment = new Comment();
        comment.setId(1L);
        CommentCache.PageData page = new CommentCache.PageData(1L, List.of(comment));
        cache.put(5001L, 6001L, 1, 10, page);

        assertThat(cache.get(5001L, 6001L, 1, 10)).isSameAs(page);

        cache.invalidateDrama(5001L);
        assertThat(cache.get(5001L, 6001L, 1, 10)).isNull();
    }

    /**
     * 关闭开关时不缓存。
     */
    @Test
    void disabledCacheIsNoop() {
        CommentCache cache = new CommentCache(false, 15);
        CommentCache.PageData page = new CommentCache.PageData(0L, List.of());

        cache.put(5001L, null, 1, 10, page);

        assertThat(cache.get(5001L, null, 1, 10)).isNull();
    }
}
