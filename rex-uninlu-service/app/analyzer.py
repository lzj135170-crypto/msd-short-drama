"""分析编排：分片、情感分类、标签分类、预算控制和降级聚合。"""

from __future__ import annotations

import time
from typing import Sequence

from app.aggregation import aggregate_sentiment, aggregate_tags, chunk_text
from app.config import Settings
from app.engine import RexEngine
from app.schemas import AnalyzeRequest, AnalyzeResponse, SentimentView, TagView


def _expired(deadline: float) -> bool:
    return time.monotonic() >= deadline


def _filter_hits(hits: Sequence[str], allowed: Sequence[str]) -> list[str]:
    """只保留请求候选并去重，防止模型输出越界标签。"""
    allowed_set = set(allowed)
    return [label for label in dict.fromkeys(hits) if label in allowed_set]


class Analyzer:
    """把一次请求拆成片和标签批次，并在超预算时返回可用部分。"""

    def __init__(self, engine: RexEngine, settings: Settings) -> None:
        self.engine = engine
        self.settings = settings

    def analyze(self, request: AnalyzeRequest) -> AnalyzeResponse:
        started = time.monotonic()
        deadline = started + max(1, self.settings.request_budget_ms) / 1000
        sentiment_labels = list(dict.fromkeys(request.sentimentLabels))
        tag_candidates = list(dict.fromkeys(request.tagCandidates))
        chunks, truncated = chunk_text(
            request.text,
            chunk_chars=self.settings.chunk_chars,
            max_chunks=self.settings.max_chunks,
        )
        tag_batches = [
            tag_candidates[index:index + max(1, self.settings.tag_batch_size)]
            for index in range(0, len(tag_candidates), max(1, self.settings.tag_batch_size))
        ]

        chunk_texts: list[str] = []
        sentiment_hits: list[list[str]] = []
        tag_hits: list[list[str]] = []
        failed_chunks = 0
        timed_out = False

        for index, chunk in enumerate(chunks):
            # 1. 预算耗尽：当前及剩余片段全部按失败处理
            if _expired(deadline):
                remaining = len(chunks) - index
                failed_chunks += remaining
                timed_out = True
                chunk_texts.extend(chunks[index:])
                sentiment_hits.extend([] for _ in range(remaining))
                tag_hits.extend([] for _ in range(remaining))
                break

            chunk_texts.append(chunk)
            chunk_failed = False
            detected_sentiment: list[str] = []
            detected_tags: list[str] = []

            # 2. 情感分类：每片一次
            try:
                detected_sentiment = list(
                    self.engine.classify(chunk, sentiment_labels, "sentiment"))
            except Exception:  # noqa: BLE001 - 单片段失败不阻断其余片段
                chunk_failed = True

            # 3. 标签分类：按批次调用，控制单次 prompt 长度
            for batch in tag_batches:
                if _expired(deadline):
                    timed_out = True
                    chunk_failed = True
                    break
                try:
                    detected_tags.extend(self.engine.classify(chunk, batch, "tags"))
                except Exception:  # noqa: BLE001
                    chunk_failed = True

            sentiment_hits.append(_filter_hits(detected_sentiment, sentiment_labels))
            tag_hits.append(_filter_hits(detected_tags, tag_candidates))
            if chunk_failed:
                failed_chunks += 1

            # 4. 当前片段中途超时：剩余片段补空位后结束
            if timed_out:
                remaining = len(chunks) - index - 1
                failed_chunks += remaining
                chunk_texts.extend(chunks[index + 1:])
                sentiment_hits.extend([] for _ in range(remaining))
                tag_hits.extend([] for _ in range(remaining))
                break

        total_chunks = len(chunks)
        all_failed = total_chunks > 0 and failed_chunks >= total_chunks
        degraded = timed_out or all_failed

        # 5. 状态：全部成功 ok；部分成功 partial；全部失败或超预算 degraded
        if total_chunks == 0 or all_failed:
            status = "degraded"
        elif timed_out or failed_chunks > 0:
            status = "partial"
        else:
            status = "ok"

        flags: list[str] = []
        if timed_out:
            flags.append("timeout")
        if failed_chunks > 0 and not timed_out:
            flags.append("inference_error")
        warnings: list[str] = []
        if truncated:
            warnings.append("input_truncated")
        if timed_out:
            warnings.append("request_budget_exceeded")

        sentiment = aggregate_sentiment(sentiment_hits, chunk_texts, sentiment_labels)
        tags = aggregate_tags(
            tag_hits,
            chunk_texts,
            tag_candidates,
            max_tags=self.settings.max_tags,
        )
        elapsed_ms = int((time.monotonic() - started) * 1000)
        return AnalyzeResponse(
            status=status,
            model=self.settings.model_id,
            revision=self.settings.revision,
            device=self.engine.device,
            chunks=total_chunks,
            failedChunks=min(failed_chunks, total_chunks),
            truncated=truncated,
            sentiment=SentimentView(
                label=sentiment.label,
                rawLabel=sentiment.raw_label,
                highNegative=sentiment.high_negative,
                support=sentiment.support,
                evidence=sentiment.evidence,
            ),
            tags=[
                TagView(name=tag.name, support=tag.support, evidence=tag.evidence)
                for tag in tags
            ],
            degraded=degraded,
            degradedFlags=flags,
            warnings=warnings,
            elapsedMs=max(0, elapsed_ms),
        )


def degraded_response(
        settings: Settings,
        engine: RexEngine | None,
        flag: str,
        started: float,
) -> AnalyzeResponse:
    """模型未就绪或调用失败时的统一响应。"""
    return AnalyzeResponse(
        status="degraded",
        model=settings.model_id,
        revision=settings.revision,
        device=getattr(engine, "device", "cpu"),
        chunks=0,
        failedChunks=0,
        truncated=False,
        sentiment=SentimentView(
            label="unknown",
            rawLabel="",
            highNegative=False,
            support=0,
            evidence=[],
        ),
        tags=[],
        degraded=True,
        degradedFlags=[flag],
        warnings=[],
        elapsedMs=max(0, int((time.monotonic() - started) * 1000)),
    )
