"""下载 RexUniNLU 模型到本地目录。

用法：
    python scripts/download-rex-uninlu.py
    python scripts/download-rex-uninlu.py --check

模型权重不进入 git；首次下载约 400 MB。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

MODEL_ID = "iic/nlp_deberta_rex-uninlu_chinese-base"
MODEL_REVISION = "v1.0.2"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MODEL_DIR = (
    PROJECT_ROOT / "rex-uninlu-service" / "models" / "iic"
    / "nlp_deberta_rex-uninlu-chinese-base"
)
REQUIRED_FILES = (
    "config.json",
    "pytorch_model.bin",
    "tokenizer_config.json",
    "vocab.txt",
    "ms_wrapper.py",
    "configuration.json",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="下载 RexUniNLU 模型")
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL_DIR,
                        help="本地模型目录")
    parser.add_argument("--check", action="store_true",
                        help="只检查已有文件，不下载")
    return parser.parse_args()


def missing_files(model_dir: Path) -> list[str]:
    """返回缺失的必需文件。"""
    return [name for name in REQUIRED_FILES if not (model_dir / name).is_file()]


def check_model(model_dir: Path) -> bool:
    """检查模型文件是否齐全，并校验主权重大小。"""
    missing = missing_files(model_dir)
    if missing:
        print(f"模型文件不完整：{', '.join(missing)}")
        return False
    weight = model_dir / "pytorch_model.bin"
    size = weight.stat().st_size
    if size < 300_000_000:
        print(f"模型权重大小异常：{size} bytes")
        return False
    print(f"模型文件齐全：{model_dir}（pytorch_model.bin={size} bytes）")
    return True


def download(model_dir: Path) -> None:
    """从 ModelScope 下载指定 revision 到本地目录。"""
    from modelscope.hub.snapshot_download import snapshot_download

    model_dir.mkdir(parents=True, exist_ok=True)
    print(f"开始下载 {MODEL_ID}@{MODEL_REVISION} -> {model_dir}")
    snapshot_download(
        MODEL_ID,
        revision=MODEL_REVISION,
        local_dir=str(model_dir),
    )


def main() -> int:
    args = parse_args()
    model_dir = args.model_dir.resolve()
    if args.check:
        return 0 if check_model(model_dir) else 1
    if check_model(model_dir):
        print("模型已存在，跳过下载。")
        return 0
    download(model_dir)
    return 0 if check_model(model_dir) else 1


if __name__ == "__main__":
    sys.exit(main())
