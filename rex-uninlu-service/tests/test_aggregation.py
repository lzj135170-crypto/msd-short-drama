"""分片、情感聚合和标签聚合的行为测试。"""

from __future__ import annotations

from app.aggregation import (
    aggregate_sentiment,
    aggregate_tags,
    chunk_text,
)


def test_chunk_text_splits_on_punctuation_and_respects_limit() -> None:
    chunks, truncated = chunk_text("第一段。第二段。第三段。", chunk_chars=5, max_chunks=2)

    assert chunks == ["第一段。", "第二段。"]
    assert truncated is True
    assert all(len(chunk) <= 5 for chunk in chunks)


def test_chunk_text_hard_splits_long_segment() -> None:
    chunks, truncated = chunk_text("甲" * 12, chunk_chars=5, max_chunks=3)

    assert chunks == ["甲" * 5, "甲" * 5, "甲" * 2]
    assert truncated is False


def test_chunk_text_marks_truncation_when_limit_reached() -> None:
    chunks, truncated = chunk_text("甲" * 12, chunk_chars=5, max_chunks=2)

    assert chunks == ["甲" * 5, "甲" * 5]
    assert truncated is True


def test_sentiment_negative_marks_high_negative() -> None:
    hits = [["负向情感"], ["负向情感"], []]
    texts = ["雨夜连环案", "刑警追查十年悬案", "雾城"]

    result = aggregate_sentiment(hits, texts, ["正向情感", "负向情感"])

    assert result.label == "negative"
    assert result.raw_label == "负向情感"
    assert result.support == 2
    assert result.high_negative is True
    assert result.evidence == ["雨夜连环案", "刑警追查十年悬案"]


def test_sentiment_balanced_returns_mixed() -> None:
    hits = [["正向情感"], ["负向情感"]]

    result = aggregate_sentiment(hits, ["甜宠", "复仇"], ["正向情感", "负向情感"])

    assert result.label == "mixed"
    assert result.raw_label == "正向情感/负向情感"
    assert result.support == 1
    assert result.high_negative is False


def test_sentiment_without_hits_returns_unknown() -> None:
    result = aggregate_sentiment([[], []], ["雾城", "谜案"], ["正向情感", "负向情感"])

    assert result.label == "unknown"
    assert result.raw_label == ""
    assert result.support == 0
    assert result.high_negative is False
    assert result.evidence == []


def test_sentiment_evidence_is_trimmed_and_deduplicated() -> None:
    long_text = "负" * 200
    result = aggregate_sentiment(
        [["负向情感"], ["负向情感"]],
        [long_text, long_text],
        ["正向情感", "负向情感"],
    )

    assert len(result.evidence) == 1
    assert len(result.evidence[0]) <= 120


def test_tags_rank_by_support_then_candidate_order() -> None:
    hits = [
        ["悬疑", "复仇"],
        ["悬疑"],
        [],
        ["逆袭", "悬疑"],
    ]
    texts = ["雨夜", "连环案", "雾城", "逆袭"]

    result = aggregate_tags(
        hits,
        texts,
        ["甜宠", "悬疑", "复仇", "逆袭"],
        max_tags=3,
    )

    assert [tag.name for tag in result] == ["悬疑", "复仇", "逆袭"]
    assert [tag.support for tag in result] == [3, 1, 1]
    assert result[0].evidence == ["雨夜", "连环案"]


def test_tags_ignore_unknown_and_respect_max_tags() -> None:
    hits = [["未知", "甜宠", "悬疑", "复仇"]]

    result = aggregate_tags(
        hits,
        ["雾城"],
        ["甜宠", "悬疑", "复仇"],
        max_tags=2,
    )

    assert [tag.name for tag in result] == ["甜宠", "悬疑"]
