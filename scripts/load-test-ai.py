#!/usr/bin/env python3
"""并发压测 msd-drama 的 AI 单次调用 demo，输出 p50/p95/max。"""

import argparse
import json
import math
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

DEFAULT_URL = "http://127.0.0.1:8102/internal-cache/demo/ai/single"


def percentile(sorted_values, ratio):
    """最近秩法分位。"""
    if not sorted_values:
        return 0
    index = max(0, min(len(sorted_values) - 1,
                       math.ceil(ratio * len(sorted_values)) - 1))
    return sorted_values[index]


def invoke(url, timeout):
    """发起一次请求，返回 (成功, 耗时毫秒, 错误信息)。"""
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            payload = json.loads(body)
        ok = payload.get("code") == 0
        return ok, (time.perf_counter() - start) * 1000, None if ok else body[:160]
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        return False, (time.perf_counter() - start) * 1000, str(exc)[:160]


def main():
    parser = argparse.ArgumentParser(description="压测 AI demo 单次接口")
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--prompt", default="hi")
    parser.add_argument("--calls", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=50)
    parser.add_argument("--timeout", type=float, default=15.0)
    args = parser.parse_args()

    query = urllib.parse.urlencode({"prompt": args.prompt})
    separator = "&" if "?" in args.url else "?"
    url = f"{args.url}{separator}{query}"

    calls = max(1, args.calls)
    concurrency = max(1, min(args.concurrency, calls))
    start = time.perf_counter()

    latencies = []
    failures = []
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(invoke, url, args.timeout) for _ in range(calls)]
        for future in as_completed(futures):
            ok, elapsed_ms, error = future.result()
            latencies.append(elapsed_ms)
            if not ok:
                failures.append(error)

    wall_ms = (time.perf_counter() - start) * 1000
    latencies.sort()
    print(f"url         = {url}")
    print(f"calls       = {calls}, concurrency = {concurrency}")
    print(f"success     = {calls - len(failures)}, failed = {len(failures)}")
    print(f"wall        = {wall_ms:.0f} ms")
    print(f"p50/p95/max = {percentile(latencies, 0.50):.0f}/"
          f"{percentile(latencies, 0.95):.0f}/{latencies[-1]:.0f} ms")
    if failures:
        print(f"first_error = {failures[0]}")


if __name__ == "__main__":
    main()
