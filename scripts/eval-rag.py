#!/usr/bin/env python3
"""离线评测 RAG 找剧和带时间戳的剧情问答。

默认直连 content-service 8107，读取 eval/golden-rag.jsonl，输出：
Recall@3、MRR、NDCG@3、无结果准确率、关键词命中率、引用准确率、
防剧透泄漏率和 P95 延迟。阈值不达标时以非 0 退出码结束。

示例：
    python scripts/eval-rag.py
    python scripts/eval-rag.py --base-url http://127.0.0.1:8000/api/content \
        --token "$MSD_TOKEN" --output eval/report.json
"""

import argparse
import json
import math
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

CITATION_PATTERN = re.compile(r"第\s*(\d+)\s*集")
CJK_PATTERN = re.compile(r"[一-鿿0-9A-Za-z]+")
METRIC_LABELS = {
    "recall_at_3": "recall@3",
    "mrr": "mrr",
    "ndcg_at_3": "ndcg@3",
    "no_match_accuracy": "no_match",
    "keyword_hit_rate": "keyword_hit",
    "lexical_grounding": "grounding",
    "citation_accuracy": "citation",
    "spoiler_leak_rate": "spoiler_leak",
}


def parse_args():
    parser = argparse.ArgumentParser(description="RAG 离线评测")
    parser.add_argument("--base-url", default="http://127.0.0.1:8107")
    parser.add_argument("--golden", default="eval/golden-rag.jsonl")
    parser.add_argument("--user-id", type=int, default=1003)
    parser.add_argument("--token", default="")
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 条，0 表示全部")
    parser.add_argument("--delay", type=float, default=0.1)
    parser.add_argument("--output", default="")
    parser.add_argument("--min-recall-at-3", type=float, default=0.7)
    parser.add_argument("--min-no-match", type=float, default=0.8)
    parser.add_argument("--min-citation", type=float, default=0.9)
    parser.add_argument("--min-keyword-hit", type=float, default=0.5)
    parser.add_argument("--max-spoiler-leak", type=float, default=0.0)
    parser.add_argument("--max-failures", type=int, default=0)
    return parser.parse_args()


def post(base_url, path, payload, user_id, token, timeout):
    """调用 content-service，返回 data 字段。"""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(base_url.rstrip("/") + path, data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    if user_id:
        request.add_header("X-User-Id", str(user_id))
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:160]
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise RuntimeError(str(exc)) from exc
    try:
        parsed = json.loads(raw)
    except ValueError as exc:
        raise RuntimeError("响应不是合法 JSON") from exc
    if parsed.get("code") not in (0, None) or parsed.get("success") is False:
        raise RuntimeError(parsed.get("message") or "接口返回失败")
    return parsed.get("data") or {}


def normalize_title(value):
    """去掉书名号和空白，便于标题匹配。"""
    return re.sub(r"[\s《》【】\[\]()（）]", "", value or "")


def source_titles(sources):
    """提取来源标题。"""
    return [normalize_title(item.get("title")) for item in sources or []]


def relevance_grade(title, expected, acceptable):
    """2=期望，1=可接受，0=不相关。"""
    if title in expected:
        return 2
    if title in acceptable:
        return 1
    return 0


def dcg(grades, k=3):
    """计算 DCG@K。"""
    total = 0.0
    for index, grade in enumerate(grades[:k]):
        total += grade / math.log2(index + 2)
    return total


def ndcg_at_3(ranked, expected, acceptable):
    """计算单条 query 的 NDCG@3。"""
    grades = [relevance_grade(title, expected, acceptable) for title in ranked[:3]]
    ideal = sorted(
        [relevance_grade(title, expected, acceptable)
         for title in list(expected) + list(acceptable)],
        reverse=True,
    )[:3]
    ideal_dcg = dcg(ideal)
    return 0.0 if ideal_dcg == 0 else dcg(grades) / ideal_dcg


def text_tokens(text):
    """中文按字符 bigram，英文数字按整词，用于轻量 grounding。"""
    tokens = set()
    for chunk in CJK_PATTERN.findall(text or ""):
        if len(chunk) <= 2:
            tokens.add(chunk)
        else:
            tokens.update(chunk[index:index + 2] for index in range(len(chunk) - 1))
    return tokens


def lexical_grounding(answer, sources):
    """回答词元被来源覆盖的比例，作为忠实度的轻量近似。"""
    answer_tokens = text_tokens(answer)
    if not answer_tokens:
        return None
    source_tokens = set()
    for source in sources or []:
        source_tokens.update(text_tokens(source.get("snippet") or source.get("summary")
                                         or source.get("text") or ""))
    return len(answer_tokens & source_tokens) / len(answer_tokens)


def p95(values):
    """P95 延迟。"""
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(0.95 * len(ordered)) - 1)
    return ordered[index]


def evaluate_search(row, data):
    """评测一条找剧 query。"""
    sources = data.get("sources") or []
    ranked = source_titles(sources)
    expected = {normalize_title(item) for item in row.get("expectedTitles") or []}
    acceptable = {normalize_title(item) for item in row.get("acceptableTitles") or []}
    relevant = expected | acceptable
    recall = len(set(ranked[:3]) & expected) / len(expected) if expected else 0.0
    mrr = 0.0
    for index, title in enumerate(ranked[:5], 1):
        if title in relevant:
            mrr = 1.0 / index
            break
    return {
        "recall": recall,
        "mrr": mrr,
        "ndcg": ndcg_at_3(ranked, expected, acceptable),
        "grounding": lexical_grounding(data.get("answer"), sources),
        "degraded": bool(data.get("degraded")),
    }


def evaluate_no_result(data):
    """无结果用例：来源为空即视为正确。"""
    return 1.0 if not data.get("sources") else 0.0


def evaluate_companion(row, data):
    """评测剧情问答：关键词、引用和防剧透。"""
    sources = data.get("sources") or []
    answer = data.get("reply") or ""
    max_episode = int(row.get("maxEpisodeNo") or 0)
    max_position = int(row.get("maxPositionMs") or 0)
    expected_keywords = row.get("expectedKeywords") or []
    haystack = answer + " " + " ".join(
        str(item.get("summary") or item.get("text") or "") for item in sources)
    keyword_hit = None
    if expected_keywords:
        keyword_hit = 1.0 if any(keyword in haystack for keyword in expected_keywords) else 0.0

    # 1. 来源集数不能超过当前集；当前集字幕不能超过播放位置
    leak = False
    citation_total = 0
    citation_valid = 0
    for source in sources:
        episode_no = int(source.get("episodeNo") or 0)
        if episode_no > max_episode:
            leak = True
        if source.get("type") != "transcript":
            continue
        citation_total += 1
        start_ms = int(source.get("startMs") or 0)
        end_ms = int(source.get("endMs") or 0)
        valid = end_ms >= start_ms and episode_no <= max_episode
        if episode_no == max_episode and end_ms > max_position:
            valid = False
            leak = True
        if valid:
            citation_valid += 1

    # 2. 回答正文中的集数引用也不能超过当前集
    for match in CITATION_PATTERN.finditer(answer):
        if int(match.group(1)) > max_episode:
            leak = True
    return {
        "keyword_hit": keyword_hit,
        "grounding": lexical_grounding(answer, sources),
        "citation_total": citation_total,
        "citation_valid": citation_valid,
        "leak": leak,
    }


def average(values):
    """忽略 None 的平均值。"""
    present = [value for value in values if value is not None]
    return sum(present) / len(present) if present else None


def main():
    args = parse_args()
    golden_path = Path(args.golden)
    if not golden_path.exists():
        raise SystemExit(f"golden set not found: {golden_path}")
    rows = []
    with golden_path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    if args.limit > 0:
        rows = rows[:args.limit]
    if not rows:
        raise SystemExit("golden set is empty")

    latencies = []
    failures = []
    search_metrics = []
    no_result_metrics = []
    companion_metrics = []
    leaks = 0
    spoiler_cases = 0
    citation_total = 0
    citation_valid = 0

    for index, row in enumerate(rows, 1):
        case_id = row.get("id", str(index))
        started = time.perf_counter()
        try:
            if row["type"] == "search":
                data = post(args.base_url, "/internal/ai/rag/recommend",
                            {"query": row["query"], "topK": 3},
                            args.user_id, args.token, args.timeout)
                search_metrics.append(evaluate_search(row, data))
            elif row["type"] == "no_result":
                data = post(args.base_url, "/internal/ai/rag/recommend",
                            {"query": row["query"], "topK": 3},
                            args.user_id, args.token, args.timeout)
                no_result_metrics.append(evaluate_no_result(data))
            elif row["type"] in ("companion", "spoiler"):
                data = post(args.base_url, "/internal/ai/companion/chat", {
                    "episodeId": row["episodeId"],
                    "message": row["query"],
                    "sessionId": "eval-" + case_id[:40],
                    "positionMs": row.get("positionMs", 0),
                }, args.user_id, args.token, args.timeout)
                metrics = evaluate_companion(row, data)
                companion_metrics.append(metrics)
                spoiler_cases += 1
                leaks += 1 if metrics["leak"] else 0
                citation_total += metrics["citation_total"]
                citation_valid += metrics["citation_valid"]
            else:
                raise RuntimeError(f"unknown type: {row['type']}")
        except Exception as exc:  # noqa: BLE001 - 单条失败不应中断整轮评测
            failures.append({"id": case_id, "error": str(exc)[:200]})
        latencies.append((time.perf_counter() - started) * 1000)
        if args.delay > 0:
            time.sleep(args.delay)
        if index % 10 == 0:
            print(f"progress {index}/{len(rows)}", flush=True)

    recall = average([item["recall"] for item in search_metrics]) or 0.0
    mrr = average([item["mrr"] for item in search_metrics]) or 0.0
    ndcg = average([item["ndcg"] for item in search_metrics]) or 0.0
    search_grounding = average([item["grounding"] for item in search_metrics])
    no_match = average(no_result_metrics) or 0.0
    keyword_hit = average([item["keyword_hit"] for item in companion_metrics])
    citation_accuracy = (citation_valid / citation_total) if citation_total else None
    spoiler_leak_rate = (leaks / spoiler_cases) if spoiler_cases else 0.0
    companion_grounding_value = average(
        [item["grounding"] for item in companion_metrics])

    checks = {
        "recall_at_3": recall >= args.min_recall_at_3,
        "no_match_accuracy": no_match >= args.min_no_match,
        "spoiler_leak_rate": spoiler_leak_rate <= args.max_spoiler_leak,
        "failures": len(failures) <= args.max_failures,
    }
    if keyword_hit is not None:
        checks["keyword_hit_rate"] = keyword_hit >= args.min_keyword_hit
    if citation_accuracy is not None:
        checks["citation_accuracy"] = citation_accuracy >= args.min_citation
    passed = all(checks.values())

    report = {
        "golden": str(golden_path),
        "cases": len(rows),
        "search_cases": len(search_metrics),
        "no_result_cases": len(no_result_metrics),
        "companion_cases": len(companion_metrics),
        "recall_at_3": round(recall, 4),
        "mrr": round(mrr, 4),
        "ndcg_at_3": round(ndcg, 4),
        "search_lexical_grounding": None if search_grounding is None
        else round(search_grounding, 4),
        "no_match_accuracy": round(no_match, 4),
        "keyword_hit_rate": None if keyword_hit is None else round(keyword_hit, 4),
        "companion_lexical_grounding": None if companion_grounding_value is None
        else round(companion_grounding_value, 4),
        "citation_accuracy": None if citation_accuracy is None
        else round(citation_accuracy, 4),
        "citation_samples": citation_total,
        "spoiler_leak_rate": round(spoiler_leak_rate, 4),
        "spoiler_cases": spoiler_cases,
        "degraded_rate": round(
            sum(1 for item in search_metrics if item.get("degraded")) /
            max(len(search_metrics), 1), 4),
        "p95_latency_ms": round(p95(latencies), 1),
        "failures": failures,
        "checks": checks,
        "passed": passed,
    }

    print("\nRAG golden evaluation")
    print(f"cases       = {report['cases']} "
          f"(search={report['search_cases']}, no_result={report['no_result_cases']}, "
          f"companion={report['companion_cases']})")
    print(f"recall@3    = {report['recall_at_3']:.2%}")
    print(f"mrr         = {report['mrr']:.4f}")
    print(f"ndcg@3      = {report['ndcg_at_3']:.4f}")
    print(f"no_match    = {report['no_match_accuracy']:.2%}")
    print(f"keyword_hit = {report['keyword_hit_rate']}")
    print(f"grounding   = search={report['search_lexical_grounding']} "
          f"companion={report['companion_lexical_grounding']}")
    print(f"citation    = {report['citation_accuracy']} "
          f"({report['citation_samples']} samples)")
    print(f"spoiler     = {report['spoiler_leak_rate']:.2%} "
          f"({report['spoiler_cases']} cases)")
    print(f"p95 latency = {report['p95_latency_ms']:.0f} ms")
    print(f"failures    = {len(failures)}")
    print("result      = " + ("PASS" if passed else "FAIL"))

    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"report      = {args.output}")

    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
