package com.msd.content.rag;

/**
 * RAG 文档 metadata 键名，索引、检索和返回来源共用。
 */
public final class RagMetadata {

    /**
     * 工具类禁止实例化。
     */
    private RagMetadata() {
    }

    /** 文档类型，drama 表示短剧文档，episode 表示分集文档，transcript 表示字幕片段。 */
    public static final String DOC_TYPE = "docType";
    /** 短剧文档类型值。 */
    public static final String DOC_TYPE_DRAMA = "drama";
    /** 分集文档类型值。 */
    public static final String DOC_TYPE_EPISODE = "episode";
    /** 字幕片段文档类型值。 */
    public static final String DOC_TYPE_TRANSCRIPT = "transcript";

    /** 短剧 ID。 */
    public static final String DRAMA_ID = "dramaId";
    /** 短剧标题。 */
    public static final String TITLE = "title";
    /** 短剧副标题。 */
    public static final String SUBTITLE = "subtitle";
    /** 封面图地址。 */
    public static final String COVER_URL = "coverUrl";
    /** 分类 ID。 */
    public static final String CATEGORY_ID = "categoryId";
    /** 分类名称。 */
    public static final String CATEGORY_NAME = "categoryName";
    /** 标签列表。 */
    public static final String TAGS = "tags";
    /** 演员列表。 */
    public static final String ACTORS = "actors";
    /** 剧集数。 */
    public static final String EPISODE_COUNT = "episodeCount";
    /** 分集 ID。 */
    public static final String EPISODE_ID = "episodeId";
    /** 分集集数，从 1 开始。 */
    public static final String EPISODE_NO = "episodeNo";
    /** 分集标题。 */
    public static final String EPISODE_TITLE = "episodeTitle";
    /** 分集摘要，只描述本集已发生的内容。 */
    public static final String SUMMARY = "summary";
    /** 解锁所需积分，0 表示免费。 */
    public static final String PRICE_POINTS = "pricePoints";
    /** 字幕片段开始时间（毫秒）。 */
    public static final String START_MS = "startMs";
    /** 字幕片段结束时间（毫秒）。 */
    public static final String END_MS = "endMs";
    /** 字幕片段正文，供引用展示。 */
    public static final String TRANSCRIPT = "transcript";
    /** 短剧标题，分集文档用于展示所属短剧。 */
    public static final String DRAMA_TITLE = "dramaTitle";
    /** 播放量。 */
    public static final String VIEW_COUNT = "viewCount";
    /** 点赞量。 */
    public static final String LIKE_COUNT = "likeCount";
    /** 上架时间。 */
    public static final String PUBLISH_TIME = "publishTime";
    /** 来源卡片展示的短摘要。 */
    public static final String SNIPPET = "snippet";

    /** 单路检索来源标记，取值为 vector 或 bm25。 */
    public static final String RETRIEVAL_SOURCE = "retrievalSource";
    /** 命中过的全部检索来源。 */
    public static final String RETRIEVAL_SOURCES = "retrievalSources";
    /** BM25 原始分数。 */
    public static final String BM25_SCORE = "bm25Score";
    /** 向量相似度分数。 */
    public static final String VECTOR_SCORE = "vectorScore";
    /** RRF 融合分数。 */
    public static final String RRF_SCORE = "rrfScore";
    /** 向量检索排名，从 1 开始。 */
    public static final String VECTOR_RANK = "vectorRank";
    /** BM25 检索排名，从 1 开始。 */
    public static final String BM25_RANK = "bm25Rank";
    /** Rerank 精排分数。 */
    public static final String RERANK_SCORE = "rerankScore";
    /** 跨查询 RRF 分数。 */
    public static final String MULTI_QUERY_SCORE = "multiQueryScore";
    /** 文档首次命中的查询排名，从 1 开始。 */
    public static final String QUERY_RANK = "queryRank";
    /** 命中的查询数量。 */
    public static final String QUERY_HITS = "queryHits";
}
