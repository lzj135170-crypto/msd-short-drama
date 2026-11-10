#!/usr/bin/env python3
"""并发压测互动写接口，输出 p50/p95/p99、成功率和错误摘要。"""

import argparse
import base64
import hashlib
import hmac
import json
import math
import os
import random
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

DEFAULT_SECRET = "msd-dev-jwt-secret-change-me-32bytes-min"
DRAMAS = [5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009]
EPISODES = [
    (5001, 6001), (5001, 6002), (5001, 6003),
    (5002, 6101), (5002, 6102),
    (5004, 6301), (5004, 6302),
    (5005, 6401), (5005, 6402),
]


def b64url(data):
    """Base64url 编码并去掉填充。"""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def make_token(user_id, secret, ttl=7200):
    """生成与 gen-jwt.py 一致的 HS256 Token。"""
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": str(user_id), "role": "USER", "iss": "msd",
        "iat": now, "exp": now + ttl,
    }
    signing_input = ".".join([
        b64url(json.dumps(header, separators=(",", ":")).encode()),
        b64url(json.dumps(payload, separators=(",", ":")).encode()),
    ])
    signature = hmac.new(secret.encode(), signing_input.encode(),
                         hashlib.sha256).digest()
    return f"{signing_input}.{b64url(signature)}"


def percentile(sorted_values, ratio):
    """最近秩法分位。"""
    if not sorted_values:
        return 0
    index = max(0, min(len(sorted_values) - 1,
                       math.ceil(ratio * len(sorted_values)) - 1))
    return sorted_values[index]


def invoke(args, token):
    """执行一次随机互动写请求。"""
    operation = random.choices(
        ["like", "comment", "history"], weights=[70, 20, 10], k=1)[0]
    if operation == "like":
        drama_id = random.choice(DRAMAS)
        if random.random() < 0.6:
            method, path, body = "POST", "/likes", {"targetType": 1, "targetId": drama_id}
        else:
            method, path, body = "DELETE", f"/likes/1/{drama_id}", None
    elif operation == "comment":
        drama_id, episode_id = random.choice(EPISODES)
        method, path = "POST", "/comments"
        body = {
            "dramaId": drama_id,
            "episodeId": episode_id,
            "content": f"[load-test] {uuid.uuid4().hex[:12]}",
            "clientRequestId": f"load-{uuid.uuid4()}",
        }
    else:
        drama_id, episode_id = random.choice(EPISODES)
        method, path = "POST", "/history"
        body = {
            "dramaId": drama_id,
            "episodeId": episode_id,
            "positionMs": random.randint(1000, 90000),
            "durationMs": 120000,
            "finished": random.random() < 0.3,
        }

    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "X-User-Id": str(args.user_id),
    }
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(args.base_url + path, data=data,
                                     method=method, headers=headers)
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            raw = response.read().decode("utf-8")
            payload = json.loads(raw)
        elapsed = (time.perf_counter() - start) * 1000
        ok = payload.get("code") == 0
        data = payload.get("data") or {}
        comment_id = data.get("commentId") if operation == "comment" and ok else None
        return ok, elapsed, operation, None if ok else raw[:160], comment_id
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        return False, (time.perf_counter() - start) * 1000, operation, str(exc)[:160], None


def cleanup_likes(args, token):
    """取消压测用户对测试短剧的点赞；只应对专用压测用户开启。"""
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "X-User-Id": str(args.user_id),
    }
    unliked = 0
    failed = 0
    for drama_id in DRAMAS:
        request = urllib.request.Request(
            f"{args.base_url}/likes/1/{drama_id}", method="DELETE", headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if payload.get("code") == 0:
                unliked += 1
            else:
                failed += 1
        except (urllib.error.URLError, TimeoutError, ValueError):
            failed += 1
    return unliked, failed


def cleanup_comments(args, token, comment_ids):
    """通过正常删除接口清理压测评论，让计数走 COMMENT_DELETED 事件。"""
    if not comment_ids:
        return 0, 0
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "X-User-Id": str(args.user_id),
    }
    deleted = 0
    failed = 0

    def delete_one(comment_id):
        request = urllib.request.Request(
            f"{args.base_url}/comments/{comment_id}", method="DELETE", headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
            return payload.get("code") == 0
        except (urllib.error.URLError, TimeoutError, ValueError):
            return False

    with ThreadPoolExecutor(max_workers=min(20, len(comment_ids))) as executor:
        for ok in executor.map(delete_one, comment_ids):
            if ok:
                deleted += 1
            else:
                failed += 1
    return deleted, failed


def main():
    parser = argparse.ArgumentParser(description="互动写接口并发压测")
    parser.add_argument("--target", choices=["direct", "gateway"], default="direct")
    parser.add_argument("--calls", type=int, default=200)
    parser.add_argument("--concurrency", type=int, default=50)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--user-id", type=int, default=1003)
    parser.add_argument("--secret", default=os.environ.get("MSD_JWT_SECRET", DEFAULT_SECRET))
    parser.add_argument("--base-url", default=None)
    parser.add_argument("--cleanup", action=argparse.BooleanOptionalAction, default=True,
                        help="压测后通过删除接口清理创建的评论，默认开启")
    parser.add_argument("--cleanup-likes", action="store_true",
                        help="压测后取消该用户对测试短剧的点赞；只对专用压测用户开启")
    args = parser.parse_args()
    args.base_url = args.base_url or (
        "http://127.0.0.1:8000/api/interaction/internal/interactions"
        if args.target == "gateway"
        else "http://127.0.0.1:8104/internal/interactions"
    )
    token = make_token(args.user_id, args.secret)

    calls = max(1, args.calls)
    concurrency = max(1, min(args.concurrency, calls))
    latencies = []
    failures = []
    comment_ids = []
    operations = {"like": 0, "comment": 0, "history": 0}
    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(invoke, args, token) for _ in range(calls)]
        for future in as_completed(futures):
            ok, elapsed, operation, error, comment_id = future.result()
            latencies.append(elapsed)
            operations[operation] += 1
            if comment_id is not None:
                comment_ids.append(comment_id)
            if not ok:
                failures.append((operation, error))
    wall_ms = (time.perf_counter() - start) * 1000
    latencies.sort()
    print(f"target      = {args.target} ({args.base_url})")
    print(f"calls       = {calls}, concurrency = {concurrency}")
    print(f"operations  = {operations}")
    print(f"success     = {calls - len(failures)}, failed = {len(failures)}")
    print(f"wall        = {wall_ms:.0f} ms")
    print(f"p50/p95/p99 = {percentile(latencies, 0.50):.0f}/"
          f"{percentile(latencies, 0.95):.0f}/{percentile(latencies, 0.99):.0f} ms")
    if failures:
        print(f"first_error = {failures[0][0]}: {failures[0][1]}")
    if args.cleanup:
        deleted, cleanup_failed = cleanup_comments(args, token, comment_ids)
        print(f"cleanup     = deleted={deleted}, failed={cleanup_failed}")
    if args.cleanup_likes:
        unliked, unlike_failed = cleanup_likes(args, token)
        print(f"cleanupLike = unliked={unliked}, failed={unlike_failed}")


if __name__ == "__main__":
    main()
