"""模型原始输出解析测试。"""

from __future__ import annotations

from app.engine import extract_labels


def test_extract_labels_from_output_list() -> None:
    raw = {
        "output": [
            {"type": "分类", "span": "负向情感", "offset": [0, 4]},
        ]
    }

    assert extract_labels(raw, ["正向情感", "负向情感"]) == ["负向情感"]


def test_extract_labels_ignores_unknown_values() -> None:
    raw = {"output": [{"type": "分类", "span": "中性"}]}

    assert extract_labels(raw, ["正向情感", "负向情感"]) == []


def test_extract_labels_handles_plain_string() -> None:
    assert extract_labels("负向情感", ["正向情感", "负向情感"]) == ["负向情感"]


def test_extract_labels_handles_nested_span() -> None:
    raw = {"output": [{"type": "情感分类", "span": ["负向情感"]}]}

    assert extract_labels(raw, ["正向情感", "负向情感"]) == ["负向情感"]
