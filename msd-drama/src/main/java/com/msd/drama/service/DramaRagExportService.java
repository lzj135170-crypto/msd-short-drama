package com.msd.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.common.dto.RecapEpisodeDTO;
import com.msd.common.result.PageResult;
import com.msd.drama.dto.DramaEpisodeTitleRow;
import com.msd.drama.dto.DramaTagNameRow;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaCategory;
import com.msd.drama.mapper.DramaCategoryMapper;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.DramaTagMapper;
import com.msd.drama.mapper.EpisodeMapper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 索引目录、相似推荐和前情提要导出。
 *
 * <p>批量查询标签、剧集标题和分类，避免按短剧逐条查询造成 N+1。</p>
 */
@Service
public class DramaRagExportService {

    /** 导出单页最大条数 */
    private static final int MAX_PAGE_SIZE = 500;
    /** 相似推荐最大条数 */
    private static final int MAX_RELATED_SIZE = 12;
    /** 相似推荐候选池倍数 */
    private static final int RELATED_CANDIDATE_FACTOR = 4;

    // 短剧 Mapper
    private final DramaMapper dramaMapper;
    // 分类 Mapper
    private final DramaCategoryMapper categoryMapper;
    // 标签 Mapper
    private final DramaTagMapper tagMapper;
    // 剧集 Mapper
    private final EpisodeMapper episodeMapper;

    /**
     * 注入短剧、分类、标签和剧集 Mapper。
     */
    public DramaRagExportService(DramaMapper dramaMapper,
                                 DramaCategoryMapper categoryMapper,
                                 DramaTagMapper tagMapper,
                                 EpisodeMapper episodeMapper) {
        this.dramaMapper = dramaMapper;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.episodeMapper = episodeMapper;
    }

    /**
     * 分页导出已上架短剧及其标签、演员和在线剧集标题。
     */
    public PageResult<DramaRagExportDTO> export(int page, int size) {
        // 1. 规整分页参数，避免负数或过大的 size 打到数据库
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        // 2. 先查总数，空目录直接返回
        long total = dramaMapper.countRagExport();
        if (total == 0) {
            return PageResult.empty(safePage, safeSize);
        }

        // 3. 按 ID 升序分页查询短剧
        int offset = (safePage - 1) * safeSize;
        List<Drama> dramas = dramaMapper.selectRagExport(offset, safeSize);
        if (dramas.isEmpty()) {
            return PageResult.of(safePage, safeSize, total, List.of());
        }

        // 4. 批量补齐分类、标签和分集明细
        return PageResult.of(safePage, safeSize, total, toDtos(dramas));
    }

    /**
     * 查询单部已上架短剧的导出数据；不存在时返回 null。
     */
    public DramaRagExportDTO exportOne(Long dramaId) {
        // 1. 只允许已上架短剧
        if (dramaId == null || dramaId <= 0) {
            return null;
        }
        Drama drama = dramaMapper.selectPublicById(dramaId);
        if (drama == null) {
            return null;
        }
        // 2. 复用批量转换，保证字段和全量导出一致
        return toDtos(List.of(drama)).get(0);
    }

    /**
     * 相似短剧规则兜底：同分类、共同标签优先，再按热度排序。
     */
    public List<DramaRagExportDTO> relatedFallback(Long dramaId, int limit) {
        // 1. 读取源短剧和它的标签，源短剧不可见时没有兜底意义
        if (dramaId == null || dramaId <= 0) {
            return List.of();
        }
        Drama source = dramaMapper.selectPublicById(dramaId);
        if (source == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, MAX_RELATED_SIZE));
        List<String> sourceTags = loadTagNames(List.of(dramaId))
                .getOrDefault(dramaId, List.of());

        // 2. 取一个更大的热度候选池，再按分类和标签重排
        List<Drama> candidates = dramaMapper.selectRelatedCandidates(
                dramaId, Math.max(safeLimit * RELATED_CANDIDATE_FACTOR, safeLimit));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<DramaRagExportDTO> dtos = toDtos(candidates);
        Map<Long, Integer> scores = new LinkedHashMap<>();
        for (DramaRagExportDTO candidate : dtos) {
            int score = 0;
            if (source.getCategoryId() != null
                    && source.getCategoryId().equals(candidate.categoryId())) {
                score += 2;
            }
            score += commonTagCount(sourceTags, candidate.tags());
            scores.put(candidate.id(), score);
        }

        // 3. 得分降序，得分相同时保持候选池的热度顺序
        List<DramaRagExportDTO> ordered = new ArrayList<>(dtos);
        ordered.sort(Comparator
                .comparingInt((DramaRagExportDTO item) -> scores.getOrDefault(item.id(), 0))
                .reversed());
        return ordered.stream().limit(safeLimit).toList();
    }

    /**
     * 查询当前集及之前的在线分集摘要，供前情提要使用。
     */
    public List<RecapEpisodeDTO> recap(Long dramaId, Integer episodeNo) {
        // 1. 参数非法时没有可返回的分集
        if (dramaId == null || dramaId <= 0 || episodeNo == null || episodeNo <= 0) {
            return List.of();
        }
        // 2. 只返回已上架短剧下、在线且不晚于当前集的分集
        return episodeMapper.selectRecapEpisodes(dramaId, episodeNo).stream()
                .map(row -> new RecapEpisodeDTO(
                        row.getEpisodeId(),
                        row.getEpisodeNo(),
                        row.getTitle(),
                        row.getSummary(),
                        row.getPricePoints(),
                        row.getSummaryStatus()))
                .toList();
    }

    /**
     * 把短剧实体批量转换为导出 DTO，统一补齐分类、标签和分集。
     */
    private List<DramaRagExportDTO> toDtos(List<Drama> dramas) {
        if (dramas == null || dramas.isEmpty()) {
            return List.of();
        }
        // 1. 一次查询分类名，按分类 ID 建映射
        Map<Long, String> categoryNames = categoryMapper.selectList(
                        new LambdaQueryWrapper<DramaCategory>())
                .stream()
                .collect(Collectors.toMap(DramaCategory::getId, DramaCategory::getName));

        // 2. 批量查询标签和在线剧集明细，按短剧 ID 分组
        List<Long> dramaIds = dramas.stream().map(Drama::getId).toList();
        Map<Long, List<String>> tagNames = loadTagNames(dramaIds);
        Map<Long, List<EpisodeRagExportDTO>> episodes = loadEpisodes(dramaIds);

        // 3. 由分集明细生成兼容字段“第N集 标题”
        Map<Long, List<String>> episodeTitles = formatEpisodeTitles(episodes);

        // 4. 组装导出 DTO，保持传入顺序
        return dramas.stream()
                .map(drama -> new DramaRagExportDTO(
                        drama.getId(),
                        drama.getTitle(),
                        drama.getSubtitle(),
                        drama.getCoverUrl(),
                        drama.getDescription(),
                        drama.getCategoryId(),
                        categoryNames.get(drama.getCategoryId()),
                        tagNames.getOrDefault(drama.getId(), List.of()),
                        splitActors(drama.getActors()),
                        drama.getEpisodeCount(),
                        episodeTitles.getOrDefault(drama.getId(), List.of()),
                        drama.getPublishTime() == null
                                ? null
                                : drama.getPublishTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        drama.getViewCount(),
                        drama.getLikeCount(),
                        drama.getFavoriteCount(),
                        drama.getCommentCount(),
                        episodes.getOrDefault(drama.getId(), List.of())))
                .toList();
    }

    /**
     * 批量查询可见标签并按短剧 ID 分组。
     */
    private Map<Long, List<String>> loadTagNames(List<Long> dramaIds) {
        // 1. 一次查询关系表，避免每部短剧单独查标签
        List<DramaTagNameRow> rows = tagMapper.selectVisibleTagNamesByDramaIds(dramaIds);
        // 2. 分组时保留标签 ID 升序
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        for (DramaTagNameRow row : rows) {
            grouped.computeIfAbsent(row.getDramaId(), key -> new ArrayList<>()).add(row.getName());
        }
        return grouped;
    }

    /**
     * 批量查询在线剧集标题和摘要，并按短剧 ID 分组。
     */
    private Map<Long, List<EpisodeRagExportDTO>> loadEpisodes(List<Long> dramaIds) {
        // 1. 一次查询剧集表，同时拿到 ID、标题和摘要，避免每部短剧单独查剧集
        List<DramaEpisodeTitleRow> rows = episodeMapper.selectOnlineEpisodeTitlesByDramaIds(dramaIds);
        // 2. 按短剧分组，保持集数升序
        Map<Long, List<EpisodeRagExportDTO>> grouped = new LinkedHashMap<>();
        for (DramaEpisodeTitleRow row : rows) {
            grouped.computeIfAbsent(row.getDramaId(), key -> new ArrayList<>())
                    .add(new EpisodeRagExportDTO(
                            row.getEpisodeId(),
                            row.getEpisodeNo(),
                            row.getTitle() == null ? "" : row.getTitle().trim(),
                            row.getSummary() == null ? "" : row.getSummary(),
                            row.getPricePoints() == null ? 0 : row.getPricePoints()));
        }
        return grouped;
    }

    /**
     * 把分集明细格式化为“第N集 标题”，保持集数升序。
     *
     * <p>标题已带“第N集”前缀时不再重复拼接，避免污染 embedding 文本。</p>
     */
    private Map<Long, List<String>> formatEpisodeTitles(Map<Long, List<EpisodeRagExportDTO>> episodes) {
        // 1. 逐部短剧拼接标题，空标题只保留集数
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        episodes.forEach((dramaId, items) -> {
            List<String> titles = new ArrayList<>(items.size());
            for (EpisodeRagExportDTO item : items) {
                String prefix = "第" + item.episodeNo() + "集";
                String title = item.title() == null ? "" : item.title().trim();
                if (title.isEmpty()) {
                    titles.add(prefix);
                } else if (title.startsWith(prefix)) {
                    titles.add(title);
                } else {
                    titles.add(prefix + " " + title);
                }
            }
            grouped.put(dramaId, titles);
        });
        return grouped;
    }

    /**
     * 计算两个标签列表的交集数量。
     */
    private int commonTagCount(List<String> left, List<String> right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            return 0;
        }
        Set<String> rightSet = new HashSet<>(right);
        return (int) left.stream()
                .filter(tag -> tag != null && rightSet.contains(tag))
                .count();
    }

    /**
     * 拆分逗号分隔的演员列表，兼容中英文逗号和顿号。
     */
    private List<String> splitActors(String actors) {
        // 1. 空值直接返回空列表
        if (actors == null || actors.isBlank()) {
            return List.of();
        }
        // 2. 去空白并过滤空项
        return Arrays.stream(actors.split("[,，、]"))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }
}
