"""内部调用鉴权。"""

from __future__ import annotations

import hmac
from typing import Annotated, Callable

from fastapi import Header, HTTPException, status

from app.config import Settings


def verify_internal_token(settings: Settings) -> Callable[[str | None], None]:
    """构造 FastAPI 依赖；token 为空时不校验，便于本地调试。"""

    def dependency(
            x_internal_token: Annotated[
                str | None, Header(alias="X-Internal-Token")
            ] = None,
    ) -> None:
        expected = settings.internal_token
        if not expected:
            return
        if not x_internal_token or not hmac.compare_digest(x_internal_token, expected):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid internal token",
            )

    return dependency
