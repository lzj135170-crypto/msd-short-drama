"""探测 RexUniNLU 本地加载和分类输入格式。

用法：
    python scripts/probe-rex-uninlu.py

只用于开发期确定适配格式；不参与线上请求。
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "rex-uninlu-service"))

from app.config import DEFAULT_MODEL_DIR  # noqa: E402
from app.rex_model import load_classifier  # noqa: E402

TEXT = "雾城谜案。雨夜连环案。刑警追查十年悬案，越查越觉得背后有人操控。"
POSITIVE_TEXT = "很满意，音质很好，发货速度快，值得购买"
NER_TEXT = "1944年毕业于北大的名古屋铁道会长谷口清太郎等人在日本积极筹资。"
LABELS = ["正向情感", "负向情感"]
TAG_LABELS = ["悬疑", "甜宠", "复仇"]
LABEL_SCHEMA = {label: None for label in LABELS}
TAG_SCHEMA = {label: None for label in TAG_LABELS}
CASES = [
    ("ner_baseline", NER_TEXT, {"人物": None, "地理位置": None, "组织机构": None}),
    ("positive_classify_label_schema",
     f"[CLASSIFY]{','.join(LABELS)}|{POSITIVE_TEXT}", LABEL_SCHEMA),
    ("negative_classify_label_schema",
     f"[CLASSIFY]{','.join(LABELS)}|{TEXT}", LABEL_SCHEMA),
    ("negative_multi_label_schema",
     f"[MULTICLASSIFY]{','.join(LABELS)}|{TEXT}", LABEL_SCHEMA),
    ("negative_multi_task_schema",
     f"[MULTICLASSIFY]{','.join(LABELS)}|{TEXT}", {"情感分类": None}),
    ("negative_classify_task_schema",
     f"[CLASSIFY]{','.join(LABELS)}|{TEXT}", {"情感分类": None}),
    ("label_schema_no_prefix", TEXT, LABEL_SCHEMA),
    ("tags_multi_label_schema",
     f"[MULTICLASSIFY]{','.join(TAG_LABELS)}|{TEXT}", TAG_SCHEMA),
    ("tags_label_schema_no_prefix", TEXT, TAG_SCHEMA),
    ("attribute_sentiment_schema",
     POSITIVE_TEXT, {"属性词": {"正向情感(情感词)": None, "负向情感(情感词)": None}}),
]


def main() -> int:
    started = time.monotonic()
    print(f"loading model from {DEFAULT_MODEL_DIR}")
    classifier = load_classifier(DEFAULT_MODEL_DIR)
    print(f"loaded in {time.monotonic() - started:.1f}s")

    results = []
    for name, prompt, schema in CASES:
        call_started = time.monotonic()
        item = {
            "name": name,
            "prompt": prompt,
            "schema": schema,
        }
        try:
            result = classifier.predict_raw(prompt, schema)
            item["elapsed_s"] = round(time.monotonic() - call_started, 3)
            item["result"] = result
            print(f"{name}: {item['elapsed_s']}s")
        except Exception as exc:  # noqa: BLE001
            item["error"] = f"{type(exc).__name__}: {exc}"
            print(f"{name}: ERROR {type(exc).__name__}")
        results.append(item)

    output = PROJECT_ROOT / "work" / "rex-probe-output.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(results, ensure_ascii=False, indent=2, default=str),
                      encoding="utf-8")
    print(f"results written to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
