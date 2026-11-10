"""测试共用的假引擎和配置。"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

# 支持从仓库根目录执行 pytest rex-uninlu-service/tests
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import Settings


class FakeEngine:
    """按标签集合返回可控结果，不加载真实模型。"""

    def __init__(self,
                 sentiment: str | None = "负向情感",
                 tags: tuple[str, ...] = ("悬疑",),
                 sentiment_labels: tuple[str, ...] = ("正向情感", "负向情感"),
                 fail_on: str | None = None,
                 ready: bool = True) -> None:
        self.sentiment = sentiment
        self.tags = tags
        self.sentiment_labels = set(sentiment_labels)
        self.fail_on = fail_on
        self._ready = ready
        self.device = "cpu"
        self.last_error: str | None = None
        self.calls: list[list[str]] = []
        self.modes: list[str] = []
        self.loaded = False

    @property
    def ready(self) -> bool:
        return self._ready

    def load(self) -> None:
        self.loaded = True

    def classify(self, text: str, labels: list[str], mode: str = "tags") -> list[str]:
        self.calls.append(list(labels))
        self.modes.append(mode)
        if self.fail_on is not None and self.fail_on in text:
            raise RuntimeError("fake inference failure")
        if mode == "sentiment":
            return [self.sentiment] if self.sentiment in labels else []
        return [tag for tag in self.tags if tag in labels]


@pytest.fixture
def settings() -> Settings:
    return Settings(
        model_dir=Path("model"),
        internal_token="test-token",
        request_budget_ms=5000,
        chunk_chars=450,
        max_chunks=6,
        tag_batch_size=15,
        max_tags=5,
    )


@pytest.fixture
def fake_engine() -> FakeEngine:
    return FakeEngine()
