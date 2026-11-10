"""API 请求与响应模型，字段使用 camelCase，便于 Java 直接反序列化。"""

from __future__ import annotations

from pydantic import BaseModel, Field, field_validator


def _clean_labels(values: list[str], *, required: bool) -> list[str]:
    """去空白、去重并校验单标签长度。"""
    cleaned: list[str] = []
    for value in values:
        label = value.strip()
        if not label:
            continue
        if len(label) > 32:
            raise ValueError("标签长度不能超过 32")
        if label not in cleaned:
            cleaned.append(label)
    if required and not cleaned:
        raise ValueError("至少需要一个情感标签")
    return cleaned


class AnalyzeRequest(BaseModel):
    """一次短剧文本分析请求。"""

    requestId: str = Field(min_length=1, max_length=64)
    text: str = Field(min_length=1, max_length=5000)
    sentimentLabels: list[str] = Field(
        default_factory=lambda: ["正向情感", "负向情感"],
        min_length=1,
        max_length=10,
    )
    tagCandidates: list[str] = Field(default_factory=list, max_length=30)

    @field_validator("requestId")
    @classmethod
    def request_id_not_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("requestId 不能为空")
        return value

    @field_validator("text")
    @classmethod
    def text_not_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("text 不能为空")
        return value

    @field_validator("sentimentLabels")
    @classmethod
    def clean_sentiment_labels(cls, values: list[str]) -> list[str]:
        return _clean_labels(values, required=True)

    @field_validator("tagCandidates")
    @classmethod
    def clean_tag_candidates(cls, values: list[str]) -> list[str]:
        return _clean_labels(values, required=False)


class SentimentView(BaseModel):
    """情感聚合结果。"""

    label: str
    rawLabel: str
    highNegative: bool
    support: int
    evidence: list[str]


class TagView(BaseModel):
    """标签建议。"""

    name: str
    support: int
    evidence: list[str]


class AnalyzeResponse(BaseModel):
    """分析结果；降级时 HTTP 仍返回 200，由 Java 侧 fail-open。"""

    status: str
    model: str
    revision: str
    device: str
    chunks: int
    failedChunks: int
    truncated: bool
    sentiment: SentimentView
    tags: list[TagView]
    degraded: bool
    degradedFlags: list[str]
    warnings: list[str]
    elapsedMs: int
