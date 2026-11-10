"""FastAPI 入口：健康检查、就绪检查和分析接口。"""

from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from app.analyzer import Analyzer, degraded_response
from app.auth import verify_internal_token
from app.config import Settings
from app.engine import ModelScopeRexEngine, RexEngine
from app.schemas import AnalyzeRequest, AnalyzeResponse

logger = logging.getLogger("rex-uninlu")


def create_app(
        engine: RexEngine | None = None,
        settings: Settings | None = None,
) -> FastAPI:
    """构造应用；测试传入假引擎，生产使用本地模型引擎。"""
    app_settings = settings or Settings.from_env()
    app_engine = engine or ModelScopeRexEngine(app_settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # 1. 模型加载放到线程池，避免阻塞事件循环；失败只记录，进程继续提供降级响应
        app.state.settings = app_settings
        app.state.engine = app_engine
        app.state.started_at = time.monotonic()
        try:
            await run_in_threadpool(app_engine.load)
        except Exception:  # noqa: BLE001 - 加载失败必须走降级而不是退出
            logger.exception("rex-uninlu model load failed")
        yield

    app = FastAPI(
        title="RexUniNLU Drama Advisory",
        version="1.0.0",
        lifespan=lifespan,
    )
    app.state.settings = app_settings
    app.state.engine = app_engine
    app.state.started_at = time.monotonic()

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
            _request, exc: RequestValidationError
    ) -> JSONResponse:
        # 协议错误统一 400，便于 Java 区分鉴权/参数问题与模型降级
        return JSONResponse(
            status_code=400,
            content={"detail": jsonable_encoder(exc.errors())},
        )

    @app.get("/healthz")
    def healthz() -> dict:
        """总可用性：即使模型未加载也返回 200。"""
        ready = bool(app_engine.ready)
        return {
            "status": "ok" if ready else "degraded",
            "model": app_settings.model_id,
            "revision": app_settings.revision,
            "device": app_engine.device,
            "model_loaded": ready,
            "ready": ready,
            "last_error": app_engine.last_error,
            "uptime_s": int(time.monotonic() - app.state.started_at),
        }

    @app.get("/readyz")
    def readyz() -> JSONResponse:
        """模型就绪才返回 200，供启动脚本和 Java 探活。"""
        if app_engine.ready:
            return JSONResponse(status_code=200, content={
                "status": "ready",
                "model": app_settings.model_id,
                "revision": app_settings.revision,
                "device": app_engine.device,
            })
        return JSONResponse(status_code=503, content={
            "status": "loading",
            "model": app_settings.model_id,
            "revision": app_settings.revision,
            "last_error": app_engine.last_error,
        })

    @app.post("/v1/analyze", response_model=AnalyzeResponse)
    async def analyze(
            request: AnalyzeRequest,
            _: None = Depends(verify_internal_token(app_settings)),
    ) -> AnalyzeResponse:
        """分析短剧文本；模型未就绪返回 200 + degraded，Java 侧 fail-open。"""
        started = time.monotonic()
        if not app_engine.ready:
            return degraded_response(app_settings, app_engine, "model_not_ready", started)
        analyzer = Analyzer(app_engine, app_settings)
        try:
            return await run_in_threadpool(analyzer.analyze, request)
        except Exception as exc:  # noqa: BLE001 - 任何异常都不能让审核链路失败
            logger.exception("rex-uninlu analyze failed: requestId=%s", request.requestId)
            return degraded_response(
                app_settings, app_engine,
                f"inference_error:{type(exc).__name__}",
                started,
            )

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn

    current = app.state.settings
    logging.basicConfig(level=current.log_level)
    uvicorn.run(
        "app.main:app",
        host=current.host,
        port=current.port,
        workers=1,
        log_level=current.log_level.lower(),
    )
