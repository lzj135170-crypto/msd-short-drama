#!/usr/bin/env python3
"""把单集视频转写为带时间戳的字幕并回传 drama 服务。

依赖：ffmpeg、faster-whisper。示例：
  pip install -r scripts/requirements-asr.txt
  python scripts/transcribe-episode.py --video D:/media/6005.mp4 --episode-id 6005

脚本只负责离线转写，不进入在线请求链路；生成结果仍需人工抽检。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

MAX_SEGMENTS = 2000


def transcribe(video: str, model_name: str, device: str, compute_type: str,
               language: str) -> list[dict]:
    """使用 faster-whisper 转写，返回毫秒时间轴片段。"""
    from faster_whisper import WhisperModel

    model = WhisperModel(model_name, device=device, compute_type=compute_type)
    segments, info = model.transcribe(video, language=language, vad_filter=True)
    result: list[dict] = []
    for segment in segments:
        text = (segment.text or "").strip()
        if not text:
            continue
        start_ms = max(0, int(segment.start * 1000))
        end_ms = max(start_ms, int(segment.end * 1000))
        if end_ms <= start_ms:
            continue
        result.append({"startMs": start_ms, "endMs": end_ms, "text": text})
        if len(result) >= MAX_SEGMENTS:
            break
    print(f"transcribed {len(result)} segments, language={info.language}", file=sys.stderr)
    return result


def upload(api: str, episode_id: int, language: str, segments: list[dict]) -> dict:
    """回传字幕片段到 drama 内部接口。"""
    url = f"{api.rstrip('/')}/internal-source/episodes/{episode_id}/transcript"
    payload = json.dumps({"language": language, "segments": segments},
                         ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url, data=payload, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = response.read().decode("utf-8")
    data = json.loads(body)
    if not data.get("success"):
        raise RuntimeError(f"upload failed: {body}")
    return data.get("data") or {}


def main() -> int:
    parser = argparse.ArgumentParser(description="转写单集视频并回传字幕")
    parser.add_argument("--video", required=True, help="本地视频路径")
    parser.add_argument("--episode-id", required=True, type=int, help="剧集 ID")
    parser.add_argument("--api", default="http://127.0.0.1:8102",
                        help="drama 服务地址")
    parser.add_argument("--language", default="zh", help="语言代码")
    parser.add_argument("--model", default="small", help="faster-whisper 模型")
    parser.add_argument("--device", default="auto", help="auto/cpu/cuda")
    parser.add_argument("--compute-type", default="int8", help="int8/float16")
    args = parser.parse_args()

    segments = transcribe(args.video, args.model, args.device,
                          args.compute_type, args.language)
    if not segments:
        print("no speech segments found", file=sys.stderr)
        return 1
    result = upload(args.api, args.episode_id, args.language, segments)
    print(json.dumps(result, ensure_ascii=False))
    print("字幕已入库；content 会通过 TRANSCRIPT_CHANGED 重建检索索引。", file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (urllib.error.URLError, RuntimeError) as ex:
        print(f"ASR upload failed: {ex}", file=sys.stderr)
        raise SystemExit(1)
