package com.msd.content.client;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Feign 返回值使用 Jackson 3 反序列化时，嵌套泛型和 record 都能正确还原。
 */
class DramaCatalogPayloadTest {

    /**
     * 解析短剧导出响应，校验分页、标签、演员和剧集字段。
     */
    @Test
    void deserializesNestedPageResult() {
        String json = """
                {
                  "code": 0,
                  "message": "成功",
                  "data": {
                    "pageNo": 1,
                    "pageSize": 200,
                    "total": 1,
                    "records": [
                      {
                        "id": 5005,
                        "title": "长安第一女仵作",
                        "subtitle": "验尸破案，顺便谈个恋爱",
                        "coverUrl": "http://127.0.0.1:9000/msd/covers/5005.jpg",
                        "description": "长安城接连出现离奇命案。",
                        "categoryId": 3,
                        "categoryName": "古装悬疑",
                        "tags": ["悬疑", "破案"],
                        "actors": ["林一", "苏晚"],
                        "episodeCount": 2,
                        "episodeTitles": ["第1集 验尸", "第2集 追凶"],
                        "publishTime": "2026-01-01T10:00:00",
                        "viewCount": 1000,
                        "likeCount": 100,
                        "favoriteCount": 50,
                        "commentCount": 20
                      }
                    ]
                  }
                }
                """;

        Result<PageResult<DramaRagExportDTO>> result = JsonMapper.shared()
                .readValue(json, new TypeReference<Result<PageResult<DramaRagExportDTO>>>() {
                });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getTotal()).isEqualTo(1);
        assertThat(result.getData().getRecords()).hasSize(1);

        DramaRagExportDTO drama = result.getData().getRecords().get(0);
        assertThat(drama.id()).isEqualTo(5005L);
        assertThat(drama.title()).isEqualTo("长安第一女仵作");
        assertThat(drama.tags()).containsExactly("悬疑", "破案");
        assertThat(drama.actors()).containsExactly("林一", "苏晚");
        assertThat(drama.episodeTitles()).containsExactly("第1集 验尸", "第2集 追凶");
        assertThat(drama.publishTime()).isEqualTo("2026-01-01T10:00:00");
    }

    /**
     * 空记录列表也应按空分页返回，而不是 null。
     */
    @Test
    void deserializesEmptyRecords() {
        String json = """
                {"code":0,"message":"成功","data":{"pageNo":1,"pageSize":200,"total":0,"records":[]}}
                """;

        Result<PageResult<DramaRagExportDTO>> result = JsonMapper.shared()
                .readValue(json, new TypeReference<Result<PageResult<DramaRagExportDTO>>>() {
                });

        assertThat(result.getData().getRecords()).isEqualTo(List.of());
    }
}
