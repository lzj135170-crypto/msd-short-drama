"""FastAPI 契约、内部鉴权和降级测试。"""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

from conftest import FakeEngine


def make_settings(**overrides) -> Settings:
    values = {
        "model_dir": Path("model"),
        "internal_token": "test-token",
        "request_budget_ms": 5000,
        "chunk_chars": 450,
        "max_chunks": 6,
        "tag_batch_size": 15,
        "max_tags": 5,
    }
    values.update(overrides)
    return Settings(**values)


def test_health_and_ready() -> None:
    with TestClient(create_app(FakeEngine(), make_settings())) as client:
        health = client.get("/healthz")
        ready = client.get("/readyz")

    assert health.status_code == 200
    assert health.json()["ready"] is True
    assert health.json()["device"] == "cpu"
    assert ready.status_code == 200


def test_analyze_returns_sentiment_and_tags() -> None:
    app = create_app(FakeEngine(tags=("悬疑",)), make_settings())
    with TestClient(app) as client:
        response = client.post(
            "/v1/analyze",
            headers={"X-Internal-Token": "test-token"},
            json={
                "requestId": "drama-5003",
                "text": "雾城谜案\n雨夜连环案\n刑警追查十年悬案。",
                "sentimentLabels": ["正向情感", "负向情感"],
                "tagCandidates": ["悬疑", "甜宠"],
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["sentiment"]["label"] == "negative"
    assert body["sentiment"]["highNegative"] is True
    assert body["sentiment"]["support"] == 1
    assert body["tags"][0]["name"] == "悬疑"
    assert body["tags"][0]["support"] == 1
    assert body["tags"][0]["evidence"][0].startswith("雾城谜案")
    assert body["failedChunks"] == 0
    assert body["elapsedMs"] >= 0


def test_analyze_requires_token() -> None:
    app = create_app(FakeEngine(), make_settings())
    with TestClient(app) as client:
        response = client.post(
            "/v1/analyze",
            json={"requestId": "drama-1", "text": "雾城谜案"},
        )

    assert response.status_code == 401


def test_validation_error_returns_400() -> None:
    app = create_app(FakeEngine(), make_settings())
    with TestClient(app) as client:
        response = client.post(
            "/v1/analyze",
            headers={"X-Internal-Token": "test-token"},
            json={"requestId": "drama-1", "text": ""},
        )

    assert response.status_code == 400


def test_not_ready_returns_degraded_200() -> None:
    app = create_app(FakeEngine(ready=False), make_settings())
    with TestClient(app) as client:
        ready = client.get("/readyz")
        response = client.post(
            "/v1/analyze",
            headers={"X-Internal-Token": "test-token"},
            json={"requestId": "drama-1", "text": "雾城谜案"},
        )

    assert ready.status_code == 503
    assert response.status_code == 200
    assert response.json()["status"] == "degraded"
    assert response.json()["degraded"] is True
    assert response.json()["degradedFlags"] == ["model_not_ready"]
