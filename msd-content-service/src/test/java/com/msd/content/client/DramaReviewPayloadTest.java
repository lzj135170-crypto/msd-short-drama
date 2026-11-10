package com.msd.content.client;

import com.msd.common.dto.DramaAiReviewResultRequest;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证短剧审核 Feign 载荷在 Jackson 3 下的嵌套泛型和 record 反序列化。
 */
class DramaReviewPayloadTest {

    /**
     * 解析审核队列响应，校验分页、时间和 AI 结果字段。
     */
    @Test
    void deserializesReviewPage() {
        String json = """
                {
                  "code": 0,
                  "message": "成功",
                  "data": {
                    "pageNo": 1,
                    "pageSize": 20,
                    "total": 1,
                    "records": [
                      {
                        "dramaId": 5003,
                        "title": "雾城谜案",
                        "subtitle": "雨夜连环案",
                        "description": "刑警追查十年悬案。",
                        "coverUrl": "",
                        "actors": "林一,苏晚",
                        "authorId": 1002,
                        "categoryId": 2003,
                        "episodeCount": 3,
                        "reviewStatus": 1,
                        "aiReviewStatus": 2,
                        "aiReviewResult": "{\\"is_violation\\":false}",
                        "submitTime": "2026-10-25T12:00:00",
                        "aiReviewTime": "2026-10-25T12:01:00",
                        "reviewTime": null,
                        "publishTime": null
                      }
                    ]
                  }
                }
                """;

        Result<PageResult<DramaReviewCandidateDTO>> result = JsonMapper.shared()
                .readValue(json, new TypeReference<Result<PageResult<DramaReviewCandidateDTO>>>() {
                });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getTotal()).isEqualTo(1);
        DramaReviewCandidateDTO item = result.getData().getRecords().get(0);
        assertThat(item.dramaId()).isEqualTo(5003L);
        assertThat(item.reviewStatus()).isEqualTo(1);
        assertThat(item.aiReviewStatus()).isEqualTo(2);
        assertThat(item.submitTime()).isEqualTo("2026-10-25T12:00:00");
    }

    /**
     * 解析 AI 结果写回请求。
     */
    @Test
    void deserializesWritebackRequest() {
        String json = """
                {"aiReviewStatus":3,"aiReviewResult":"{\\"is_violation\\":true}"}
                """;

        DramaAiReviewResultRequest request = JsonMapper.shared()
                .readValue(json, DramaAiReviewResultRequest.class);

        assertThat(request.aiReviewStatus()).isEqualTo(3);
        assertThat(request.aiReviewResult()).contains("\"is_violation\":true");
    }
}
