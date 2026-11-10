#!/usr/bin/env python3
"""生成用于本地联调的 HS256 JWT。"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import time

# 与 msd-gateway application.yml 的默认密钥保持一致
DEFAULT_SECRET = "msd-dev-jwt-secret-change-me-32bytes-min"
DEFAULT_ISSUER = "msd"


def b64url(data: bytes) -> str:
    """Base64url 编码并去掉填充。"""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def main():
    parser = argparse.ArgumentParser(description="生成码上短剧测试 JWT")
    parser.add_argument("--user-id", default="1003", help="用户 ID")
    parser.add_argument("--role", default="USER", choices=["USER", "CREATOR", "ADMIN"], help="用户角色")
    parser.add_argument("--ttl", type=int, default=3600, help="有效期（秒），负数用于生成过期 Token")
    parser.add_argument("--issuer", default=DEFAULT_ISSUER, help="签发方")
    parser.add_argument("--secret", default=os.environ.get("MSD_JWT_SECRET", DEFAULT_SECRET),
                        help="HS256 密钥，默认读取 MSD_JWT_SECRET")
    args = parser.parse_args()

    # 1. 计算签发时间
    now = int(time.time())
    # 2. 构造 JWT 头部：声明签名算法和 Token 类型
    header = {"alg": "HS256", "typ": "JWT"}
    # 3. 构造业务声明：用户 ID、角色、签发方、签发时间和过期时间
    payload = {
        "sub": str(args.user_id),
        "role": args.role,
        "iss": args.issuer,
        "iat": now,
        "exp": now + args.ttl,  # ttl 为负数时可生成过期 Token
    }
    # 4. 拼接签名输入：base64url(header) + "." + base64url(payload)
    signing_input = ".".join([
        b64url(json.dumps(header, separators=(",", ":")).encode("utf-8")),
        b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8")),
    ])
    # 5. 使用 HMAC-SHA256 对签名输入计算摘要
    signature = hmac.new(args.secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    # 6. 输出 header.payload.signature 三段式 Token
    print(f"{signing_input}.{b64url(signature)}")


if __name__ == "__main__":
    main()
