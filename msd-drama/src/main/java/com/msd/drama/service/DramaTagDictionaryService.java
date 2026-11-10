package com.msd.drama.service;

import com.msd.drama.mapper.DramaTagMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 标签字典只读服务。
 *
 * <p>Rex 标签建议只使用启用中的标签名称，不写 t_drama_tag_rel。</p>
 */
@Service
public class DramaTagDictionaryService {

    // 标签 Mapper
    private final DramaTagMapper tagMapper;

    /**
     * 注入标签 Mapper。
     */
    public DramaTagDictionaryService(DramaTagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    /**
     * 返回启用中的标签名称，去空白并去重。
     */
    public List<String> listVisibleNames() {
        List<String> names = tagMapper.selectVisibleNames();
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
    }
}
