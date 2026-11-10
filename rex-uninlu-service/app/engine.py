"""模型引擎抽象和原始输出解析。

真实模型只在 load() 时导入 torch/transformers，单元测试可以使用假引擎。
"""

from __future__ import annotations

import threading
from typing import Any, Iterator, Protocol

from app.config import Settings

# 只有这些字段可能承载模型标签；跳过 input/text，避免把原文误判为标签。
_LABEL_KEYS = {
    "span", "label", "type", "value", "output", "result", "prediction", "分类",
}


class RexEngine(Protocol):
    """分析器依赖的最小引擎接口。"""

    device: str
    last_error: str | None

    @property
    def ready(self) -> bool:
        """模型是否完成加载。"""

    def load(self) -> None:
        """加载模型；失败时保留 last_error，由 API 降级。"""

    def classify(self, text: str, labels: list[str], mode: str = "tags") -> list[str]:
        """返回文本命中的标签；mode 为 sentiment 或 tags。"""


class ModelScopeRexEngine:
    """基于本地 RexUniNLU 权重的 CPU 推理引擎。"""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.device = "cpu"
        self.last_error: str | None = None
        self._ready = False
        self._pipeline: Any = None
        self._lock = threading.Lock()

    @property
    def ready(self) -> bool:
        return self._ready

    def load(self) -> None:
        """加载本地模型；异常不外抛，保持进程可用于健康检查。"""
        try:
            from app.rex_model import load_classifier

            self._pipeline = load_classifier(self.settings.model_dir, self.device)
            self._ready = True
            self.last_error = None
        except Exception as exc:  # noqa: BLE001 - 降级路径必须吞掉所有模型异常
            self._ready = False
            self.last_error = f"{type(exc).__name__}: {exc}"

    def classify(self, text: str, labels: list[str], mode: str = "tags") -> list[str]:
        """单次分类；模型调用串行化，避免共享 state dict 的并发问题。"""
        if not self._ready or self._pipeline is None:
            raise RuntimeError("model_not_ready")
        with self._lock:
            raw = self._pipeline(text, labels, mode)
        return extract_labels(raw, labels)


def extract_labels(raw: Any, candidates: list[str]) -> list[str]:
    """从模型原始输出中提取命中标签，按候选顺序返回并去重。"""
    values = list(_iter_label_values(raw))
    found: list[str] = []
    for candidate in candidates:
        if candidate in found:
            continue
        if any(candidate in value for value in values):
            found.append(candidate)
    return found


def _iter_label_values(value: Any, key: str | None = None) -> Iterator[str]:
    """递归遍历输出，只保留标签字段或顶层字符串。"""
    if isinstance(value, str):
        if key is None or key in _LABEL_KEYS:
            yield value
        return
    if isinstance(value, dict):
        for child_key, child in value.items():
            yield from _iter_label_values(child, child_key)
        return
    if isinstance(value, (list, tuple, set)):
        for child in value:
            yield from _iter_label_values(child, key)
