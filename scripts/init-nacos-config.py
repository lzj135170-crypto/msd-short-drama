#!/usr/bin/env python3
"""初始化 Nacos 命名空间和码上短剧配置。"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / "deploy" / ".env"
TEMPLATE_DIR = ROOT / "deploy" / "nacos"

GROUP = "DEFAULT_GROUP"
# Sentinel 规则单独放一个分组，避免和普通应用配置混在一起
SENTINEL_GROUP = "SENTINEL_GROUP"

# 每个环境的差异：测试/生产用占位密码，AI 温度逐环境收紧
ENVIRONMENTS = {
    "dev": {"password": None, "temperature": "0.7", "sampling": "1.0", "desc": "开发环境"},
    "test": {"password": "change-me", "temperature": "0.5", "sampling": "0.5", "desc": "测试环境"},
    "prod": {"password": "change-me", "temperature": "0.3", "sampling": "0.1", "desc": "生产环境"},
}

# 每个条目：本地模板文件、Nacos dataId、group、内容类型
CONFIGS = [
    ("common.yaml", "common.yaml", GROUP, "yaml"),
    ("agent-config.yaml", "agent-config.yaml", GROUP, "yaml"),
    ("content-config.yaml", "content-config.yaml", GROUP, "yaml"),
    ("msd-drama.yaml", "msd-drama.yaml", GROUP, "yaml"),
    ("msd-interaction.yaml", "msd-interaction.yaml", GROUP, "yaml"),
    ("msd-order.yaml", "msd-order.yaml", GROUP, "yaml"),
    ("sentinel-gateway-flow.json", "sentinel-gateway-flow.json", SENTINEL_GROUP, "json"),
    ("sentinel-agent-degrade.json", "sentinel-agent-degrade.json", SENTINEL_GROUP, "json"),
]

# 本地访问不走系统代理
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def load_env(path):
    """读取简单的 KEY=VALUE 文件。"""
    values = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def request(method, url, data=None, token=None, timeout=15):
    """发送表单请求并返回 JSON。"""
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
    if token:
        req.add_header("accessToken", token)
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} -> HTTP {exc.code}: {text}") from exc


def login(server, username, password):
    """登录控制台并返回 accessToken。"""
    result = request("POST", f"{server}/v3/auth/user/login",
                     {"username": username, "password": password})
    token = result.get("accessToken")
    if not token:
        raise RuntimeError(f"登录失败：{result}")
    return token


def ensure_namespace(server, token, namespace_id, desc):
    """创建命名空间，已存在时跳过。"""
    try:
        result = request("POST", f"{server}/v3/console/core/namespace", {
            "customNamespaceId": namespace_id,
            "namespaceName": namespace_id,
            "namespaceDesc": desc,
        }, token)
    except RuntimeError as exc:
        if "already exist" in str(exc).lower():
            print(f"  namespace {namespace_id}: exists")
            return
        raise
    message = str(result.get("message", ""))
    if result.get("code") not in (0, 200) and "exist" not in message.lower():
        raise RuntimeError(f"创建命名空间 {namespace_id} 失败：{result}")
    print(f"  namespace {namespace_id}: ok")


def publish(server, token, namespace_id, data_id, group, content_type, content):
    """发布配置；console 接口不可用时回退到 admin 接口。"""
    # 1. 组装 Nacos 3 控制台的发布表单，group/type 由配置条目决定
    data = {
        "namespaceId": namespace_id,
        "groupName": group,
        "dataId": data_id,
        "content": content,
        "type": content_type,
    }
    # 2. 优先走 console API，404 时回退到 8848 的 admin API
    try:
        result = request("POST", f"{server}/v3/console/cs/config", data, token)
    except RuntimeError as exc:
        if "404" not in str(exc):
            raise
        admin_server = server.replace(":8080", ":8848")
        result = request("POST", f"{admin_server}/nacos/v3/admin/cs/config", data, token)
    if result.get("code") not in (0, 200):
        raise RuntimeError(f"发布 {namespace_id}/{data_id} 失败：{result}")
    print(f"  {namespace_id}/{data_id}: published")


def verify(server, namespace_id, data_id, group, expected):
    """回读配置并逐字比对，容忍发布后的短暂延迟。"""
    api_server = server.replace(":8080", ":8848")
    query = urllib.parse.urlencode({
        "dataId": data_id,
        "groupName": group,
        "namespaceId": namespace_id,
    })
    url = f"{api_server}/nacos/v3/client/cs/config?{query}"
    result = {}
    for _ in range(5):
        result = request("GET", url)
        data = result.get("data")
        content = data.get("content") if isinstance(data, dict) else data
        if result.get("code") in (0, 200) and content == expected:
            print(f"  {namespace_id}/{data_id}: verified")
            return
        time.sleep(1)
    raise RuntimeError(f"校验 {namespace_id}/{data_id} 失败：{result}")


def render(template, env_name, values):
    """替换模板中的环境变量占位符。"""
    content = template
    content = content.replace("{{ENV}}", env_name)
    content = content.replace("{{MSD_DB_PASSWORD}}", values["MSD_DB_PASSWORD"])
    content = content.replace("{{REDIS_PASSWORD}}", values["REDIS_PASSWORD"])
    content = content.replace("{{XXL_JOB_ACCESS_TOKEN}}", values["XXL_JOB_ACCESS_TOKEN"])
    content = content.replace("{{AI_TEMPERATURE}}", ENVIRONMENTS[env_name]["temperature"])
    content = content.replace("{{TRACING_SAMPLING}}", ENVIRONMENTS[env_name]["sampling"])
    return content


def main():
    parser = argparse.ArgumentParser(description="初始化 Nacos 命名空间与配置")
    parser.add_argument("--server", default=os.environ.get("NACOS_CONSOLE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.environ.get("NACOS_USERNAME", "nacos"))
    parser.add_argument("--password", default=os.environ.get("NACOS_PASSWORD"))
    args = parser.parse_args()

    env_values = load_env(ENV_FILE)
    server = env_values.get("NACOS_CONSOLE_URL", args.server).rstrip("/")
    username = env_values.get("NACOS_USERNAME", args.username)
    password = env_values.get("NACOS_PASSWORD", args.password)
    if not password:
        sys.exit("缺少 NACOS_PASSWORD（deploy/.env 或环境变量）")

    token = login(server, username, password)
    print(f"logged in as {username}")

    for env_name, env_meta in ENVIRONMENTS.items():
        namespace_id = f"mashang-duanju-{env_name}"
        print(f"environment {env_name} -> {namespace_id}")
        ensure_namespace(server, token, namespace_id, env_meta["desc"])

        values = {
            "MSD_DB_PASSWORD": env_values.get("MSD_DB_PASSWORD", "change-me")
            if env_name == "dev" else env_meta["password"],
            "REDIS_PASSWORD": env_values.get("REDIS_PASSWORD", "change-me")
            if env_name == "dev" else env_meta["password"],
            "XXL_JOB_ACCESS_TOKEN": env_values.get("XXL_JOB_ACCESS_TOKEN", "change-me")
            if env_name == "dev" else "change-me",
        }
        for template_name, data_id, group, content_type in CONFIGS:
            template = (TEMPLATE_DIR / template_name).read_text(encoding="utf-8")
            content = render(template, env_name, values)
            publish(server, token, namespace_id, data_id, group, content_type, content)
            verify(server, namespace_id, data_id, group, content)

    print("done")


if __name__ == "__main__":
    main()
