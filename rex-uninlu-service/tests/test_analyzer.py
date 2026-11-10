"""分析编排的分片、批次、部分失败和截断测试。"""

from __future__ import annotations

from pathlib import Path

from app.analyzer import Analyzer
from app.config import Settings
from app.schemas import AnalyzeRequest

from conftest import FakeEngine


def make_settings(**overrides) -> Settings:
    values = {
        "model_dir": Path("model"),
        "internal_token": "",
        "request_budget_ms": 5000,
        "chunk_chars": 5,
        "max_chunks": 6,
        "tag_batch_size": 15,
        "max_tags": 5,
    }
    values.update(overrides)
    return Settings(**values)


def test_tag_support_counts_chunks() -> None:
    engine = FakeEngine(tags=("悬疑",))
    analyzer = Analyzer(engine, make_settings())
    request = AnalyzeRequest(
        requestId="drama-1",
        text="第一段。第二段。第三段。",
        tagCandidates=["悬疑"],
    )

    response = analyzer.analyze(request)

    assert response.status == "ok"
    assert response.chunks == 3
    assert response.tags[0].name == "悬疑"
    assert response.tags[0].support == 3


def test_partial_when_one_chunk_fails() -> None:
    engine = FakeEngine(tags=("悬疑",), fail_on="第二段")
    analyzer = Analyzer(engine, make_settings())
    request = AnalyzeRequest(
        requestId="drama-2",
        text="第一段。第二段。第三段。",
        tagCandidates=["悬疑"],
    )

    response = analyzer.analyze(request)

    assert response.status == "partial"
    assert response.degraded is False
    assert response.failedChunks == 1
    assert "inference_error" in response.degradedFlags


def test_degraded_when_all_chunks_fail() -> None:
    engine = FakeEngine(fail_on="段")
    analyzer = Analyzer(engine, make_settings())
    request = AnalyzeRequest(requestId="drama-3", text="第一段。第二段。第三段。")

    response = analyzer.analyze(request)

    assert response.status == "degraded"
    assert response.degraded is True
    assert response.failedChunks == 3
    assert "inference_error" in response.degradedFlags


def test_tag_candidates_are_batched() -> None:
    engine = FakeEngine(tags=())
    analyzer = Analyzer(engine, make_settings())
    candidates = [f"标签{i}" for i in range(20)]
    request = AnalyzeRequest(
        requestId="drama-4",
        text="雾城谜案",
        tagCandidates=candidates,
    )

    analyzer.analyze(request)

    tag_calls = [call for call in engine.calls if call != ["正向情感", "负向情感"]]
    assert len(tag_calls) == 2
    assert all(len(call) <= 15 for call in tag_calls)
    assert engine.modes.count("sentiment") == 1
    assert engine.modes.count("tags") == 2


def test_long_text_marks_truncated() -> None:
    engine = FakeEngine()
    analyzer = Analyzer(engine, make_settings(chunk_chars=5, max_chunks=2))
    request = AnalyzeRequest(requestId="drama-5", text="甲" * 20)

    response = analyzer.analyze(request)

    assert response.truncated is True
    assert "input_truncated" in response.warnings
    assert response.chunks == 2
