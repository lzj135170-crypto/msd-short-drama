package com.msd.drama.service;

import com.msd.drama.mapper.DramaTagMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 标签字典只读服务测试。
 */
class DramaTagDictionaryServiceTest {

    private final DramaTagMapper tagMapper = mock(DramaTagMapper.class);
    private final DramaTagDictionaryService service = new DramaTagDictionaryService(tagMapper);

    /**
     * 过滤空标签、去空白并去重。
     */
    @Test
    void returnsCleanVisibleNames() {
        when(tagMapper.selectVisibleNames())
                .thenReturn(Arrays.asList(" 悬疑 ", "", null, "甜宠", "悬疑"));

        assertThat(service.listVisibleNames()).containsExactly("悬疑", "甜宠");
    }

    /**
     * Mapper 返回 null 时返回空列表。
     */
    @Test
    void nullRowsReturnEmptyList() {
        when(tagMapper.selectVisibleNames()).thenReturn(null);

        assertThat(service.listVisibleNames()).isEmpty();
    }
}
