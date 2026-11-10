"""sidecar 配置，全部来自环境变量，模型目录可显式覆盖。"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

MODEL_ID = "iic/nlp_deberta_rex-uninlu-chinese-base"
MODEL_REVISION = "v1.0.2"
DEFAULT_MODEL_DIR = (
    Path(__file__).resolve().parent.parent / "models" / "iic"
    / "nlp_deberta_rex-uninlu-chinese-base"
)


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """进程级配置，字段名与 Java 侧保持可读对应。"""

    model_id: str = MODEL_ID
    revision: str = MODEL_REVISION
    model_dir: Path = field(default_factory=lambda: DEFAULT_MODEL_DIR)
    host: str = "127.0.0.1"
    port: int = 8108
    device: str = "cpu"
    internal_token: str = ""
    request_budget_ms: int = 7000
    chunk_chars: int = 450
    max_chunks: int = 6
    tag_batch_size: int = 15
    max_tags: int = 5
    log_level: str = "INFO"

    @classmethod
    def from_env(cls) -> "Settings":
        """从环境变量构造配置；缺省值适用于本地开发。"""
        model_dir = os.environ.get("REX_UNINLU_MODEL_DIR", "").strip()
        return cls(
            model_id=os.environ.get("REX_UNINLU_MODEL_ID", MODEL_ID).strip() or MODEL_ID,
            revision=os.environ.get("REX_UNINLU_REVISION", MODEL_REVISION).strip()
            or MODEL_REVISION,
            model_dir=Path(model_dir).expanduser() if model_dir else DEFAULT_MODEL_DIR,
            host=os.environ.get("REX_UNINLU_HOST", "127.0.0.1").strip() or "127.0.0.1",
            port=_env_int("REX_UNINLU_PORT", 8108),
            device=os.environ.get("REX_UNINLU_DEVICE", "cpu").strip() or "cpu",
            internal_token=os.environ.get("REX_UNINLU_INTERNAL_TOKEN", "").strip(),
            request_budget_ms=_env_int("REX_REQUEST_BUDGET_MS", 7000),
            chunk_chars=_env_int("REX_CHUNK_CHARS", 450),
            max_chunks=_env_int("REX_MAX_CHUNKS", 6),
            tag_batch_size=_env_int("REX_TAG_BATCH_SIZE", 15),
            max_tags=_env_int("REX_MAX_TAGS", 5),
            log_level=os.environ.get("REX_LOG_LEVEL", "INFO").strip() or "INFO",
        )
