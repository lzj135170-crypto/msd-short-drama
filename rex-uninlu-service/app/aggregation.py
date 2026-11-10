"""文本分片、情感聚合和标签聚合。

模型只返回命中的标签；这里把命中结果转换为可解释的 support（命中片数）和证据。
"""

from __future__ import annotations

import bisect
import math
import re
from dataclasses import dataclass
from typing import Sequence

MAX_EVIDENCE_CHARS = 120
MAX_SENTIMENT_EVIDENCE = 3
MAX_TAG_EVIDENCE = 2
_BOUNDARY = re.compile(r"[。！？!?；;\n]")


@dataclass(frozen=True)
class SentimentAggregate:
    """情感聚合结果。"""

    label: str
    raw_label: str
    high_negative: bool
    support: int
    evidence: list[str]


@dataclass(frozen=True)
class TagAggregate:
    """标签聚合结果。"""

    name: str
    support: int
    evidence: list[str]


def chunk_text(text: str, chunk_chars: int = 450, max_chunks: int = 6) -> tuple[list[str], bool]:
    """优先按换行和句末标点切分，超长片段硬切；返回分片和是否截断。"""
    source = text.strip()
    if not source:
        return [], False
    chunk_chars = max(1, chunk_chars)
    max_chunks = max(1, max_chunks)
    boundaries = [match.end() for match in _BOUNDARY.finditer(source)]

    chunks: list[str] = []
    start = 0
    while start < len(source) and len(chunks) < max_chunks:
        end = min(start + chunk_chars, len(source))
        # 片段内最后一个句末边界作为切点；没有边界时硬切
        boundary_index = bisect.bisect_right(boundaries, end)
        if boundary_index > 0:
            boundary = boundaries[boundary_index - 1]
            if start < boundary <= end:
                end = boundary
        chunk = source[start:end].strip()
        if chunk:
            chunks.append(chunk)
        start = end

    truncated = bool(source[start:].strip())
    return chunks, truncated


def aggregate_sentiment(
        chunk_hits: Sequence[Sequence[str]],
        chunk_texts: Sequence[str],
        labels: Sequence[str],
) -> SentimentAggregate:
    """按命中片数判断情感倾向，负向达到 2/3 片时标记 highNegative。"""
    positive = labels[0] if len(labels) > 0 else None
    negative = labels[1] if len(labels) > 1 else None
    total = len(chunk_hits)
    positive_support = sum(1 for hits in chunk_hits if positive in hits)
    negative_support = sum(1 for hits in chunk_hits if negative in hits)

    if positive_support > 0 and negative_support > 0:
        if abs(positive_support - negative_support) <= max(1, total // 3):
            label = "mixed"
        elif positive_support > negative_support:
            label = "positive"
        else:
            label = "negative"
    elif positive_support > 0:
        label = "positive"
    elif negative_support > 0:
        label = "negative"
    else:
        label = "unknown"

    if label == "positive":
        raw_label = positive or ""
        support = positive_support
        target = positive
    elif label == "negative":
        raw_label = negative or ""
        support = negative_support
        target = negative
    elif label == "mixed":
        raw_label = f"{positive}/{negative}"
        support = max(positive_support, negative_support)
        target = negative
    else:
        raw_label = ""
        support = 0
        target = None

    high_negative = (
        label == "negative"
        and total > 0
        and negative_support >= math.ceil(2 * total / 3)
    )
    evidence = _collect_evidence(chunk_hits, chunk_texts, target, MAX_SENTIMENT_EVIDENCE)
    return SentimentAggregate(
        label=label,
        raw_label=raw_label,
        high_negative=high_negative,
        support=support,
        evidence=evidence,
    )


def aggregate_tags(
        chunk_hits: Sequence[Sequence[str]],
        chunk_texts: Sequence[str],
        candidates: Sequence[str],
        max_tags: int = 5,
) -> list[TagAggregate]:
    """按命中片数排序，保留请求候选中的标签，最多 max_tags 个。"""
    order = {name: index for index, name in enumerate(dict.fromkeys(candidates))}
    supports = {name: 0 for name in order}
    evidence: dict[str, list[str]] = {name: [] for name in order}

    for hits, text in zip(chunk_hits, chunk_texts):
        seen_in_chunk: set[str] = set()
        for name in hits:
            if name not in order or name in seen_in_chunk:
                continue
            seen_in_chunk.add(name)
            supports[name] += 1
            snippet = _snippet(text)
            if snippet and snippet not in evidence[name] and len(evidence[name]) < MAX_TAG_EVIDENCE:
                evidence[name].append(snippet)

    ranked = sorted(
        (name for name, support in supports.items() if support > 0),
        key=lambda name: (-supports[name], order[name]),
    )
    return [
        TagAggregate(name=name, support=supports[name], evidence=evidence[name])
        for name in ranked[:max(0, max_tags)]
    ]


def _collect_evidence(
        chunk_hits: Sequence[Sequence[str]],
        chunk_texts: Sequence[str],
        target: str | None,
        limit: int,
) -> list[str]:
    """收集命中目标标签的片段证据，去重并截断。"""
    if target is None:
        return []
    result: list[str] = []
    for hits, text in zip(chunk_hits, chunk_texts):
        if target not in hits:
            continue
        snippet = _snippet(text)
        if snippet and snippet not in result:
            result.append(snippet)
        if len(result) >= limit:
            break
    return result


def _snippet(text: str) -> str:
    """压缩空白并截断证据，避免原始长文本进入审核 JSON。"""
    return " ".join(text.split())[:MAX_EVIDENCE_CHARS]
