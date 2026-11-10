# RexUniNLU 短剧情感审核与标签建议

RexUniNLU 是达摩院开源的 zero-shot 通用自然语言理解模型。项目把它作为本地
sidecar，用于短剧审核时的**情感倾向**和**题材标签建议**，不参与合规通过/驳回判定。

## 1. 边界

| 项目 | 规则 |
|---|---|
| 情感结果 | 仅提示人工复核，绝不自动通过/驳回 |
| 标签建议 | 只写入 `t_drama.ai_review_result.advisory`，不写 `t_drama_tag_rel` |
| 审核状态 | 仍由 DeepSeek 合规结论映射：降级 4、违规 3、未违规 2 |
| 失败策略 | sidecar 不可用、超时、解析失败都只降级 advisory，审核继续 |
| 模型权重 | 不进入 git；由下载脚本落到 `rex-uninlu-service/models/` |

## 2. 模型与运行方式

- ModelScope：`iic/nlp_deberta_rex-uninlu_chinese-base`
- revision：`v1.0.2`
- 许可证：Apache License 2.0
- 权重：`pytorch_model.bin` 393,038,241 字节；DeBERTa-v2 base，最大 512 token
- 运行：本地 Python 3.11 + FastAPI + Uvicorn，单 worker，CPU 推理
- 端口：`127.0.0.1:8108`
- 官方 `ms_wrapper.py` 把模型路径写死为 `damo/...`，本实现改为读取本地目录并复用
  模型仓库内的 `rex` 推理代码。

## 3. 目录与启动

```text
rex-uninlu-service/
  app/          # FastAPI、配置、引擎、分片聚合
  tests/        # 23 个 pytest
  scripts/      # 预留的工具脚本
  requirements.txt
  requirements-dev.txt
  models/       # 权重（gitignore）
```

首次安装：

```cmd
scripts\setup-rex-uninlu.cmd
```

该脚本创建 `rex-uninlu-service/.venv`、安装 CPU torch 和依赖、下载模型。

启动：

```cmd
scripts\start-rex-uninlu.cmd
```

环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `REX_UNINLU_MODEL_DIR` | `rex-uninlu-service/models/iic/nlp_deberta_rex-uninlu-chinese-base` | 本地权重目录 |
| `REX_UNINLU_PORT` | `8108` | 监听端口 |
| `REX_UNINLU_INTERNAL_TOKEN` | 空 | 非空时校验 `X-Internal-Token` |
| `REX_REQUEST_BUDGET_MS` | `7000` | 单次分析预算 |
| `REX_CHUNK_CHARS` | `450` | 分片长度 |
| `REX_MAX_CHUNKS` | `6` | 最大分片数 |

健康检查：

```bash
curl http://127.0.0.1:8108/healthz
curl http://127.0.0.1:8108/readyz
```

`/healthz` 总是返回 200；`/readyz` 只有模型加载完成才返回 200，否则 503。

## 4. API 契约

### POST /v1/analyze

请求：

```json
{
  "requestId": "drama-5003",
  "text": "雾城谜案\n雨夜连环案\n刑警追查十年悬案。",
  "sentimentLabels": ["正向情感", "负向情感"],
  "tagCandidates": ["悬疑", "甜宠", "复仇"]
}
```

响应：

```json
{
  "status": "ok",
  "model": "iic/nlp_deberta_rex-uninlu_chinese-base",
  "revision": "v1.0.2",
  "device": "cpu",
  "chunks": 1,
  "failedChunks": 0,
  "truncated": false,
  "sentiment": {
    "label": "negative",
    "rawLabel": "负向情感",
    "highNegative": true,
    "support": 1,
    "evidence": ["刑警追查十年悬案。"]
  },
  "tags": [
    {"name": "悬疑", "support": 1, "evidence": ["刑警追查十年悬案。"]}
  ],
  "degraded": false,
  "degradedFlags": [],
  "warnings": [],
  "elapsedMs": 218
}
```

语义：

- `status`：`ok` / `partial` / `degraded`。
- `sentiment.label`：`positive` / `negative` / `mixed` / `unknown`。
- `support`：命中片数，不是模型概率；前端不展示虚假百分比。
- `highNegative`：负向支持数达到总片数的 2/3 时置为 true，建议人工复核。
- `tags`：最多 5 个，按支持片数降序、标签候选顺序稳定排序。
- `degraded`：模型未就绪、全部片段失败或预算耗尽；此时 HTTP 仍为 200。
- 协议错误（空文本、超长、非法标签、内部 token 错误）返回 400/401。
- 文本超过 6×450 字时只分析前部，`truncated=true`，`warnings` 含 `input_truncated`。

## 5. 分片与聚合

1. 每 450 字一片，最多 6 片；优先按换行和句末标点切分，超长片段硬切。
2. 情感：每片用 `[CLASSIFY]正向,负向|文本` 单标签分类，标签作为 schema key。
3. 标签：每片用 `[MULTICLASSIFY]标签1,标签2|文本` 多标签分类；候选按每批 15 个调用。
4. 情感支持数：统计每个标签命中的片数；正负都出现且接近时为 `mixed`。
5. 标签支持数：统计命中片数，保留 support≥1，最多 5 个。
6. 证据：情感最多 3 条，每个标签最多 2 条，每条最多 120 字，去重。

## 6. 兼容性陷阱

这些是实际探测中遇到的问题，修改依赖或升级模型时需要重新确认：

| 问题 | 处理 |
|---|---|
| 官方 wrapper 写死 `damo/nlp_deberta_rex-uninlu_chinese-base` | 本地适配器显式传本地模型目录 |
| `add_special_tokens` 改变四个特殊 token 的顺序 | 逐个 `add_tokens`，并显式设置 `_additional_special_tokens` |
| JDK HttpClient 默认 HTTP/2 升级，uvicorn 返回 400 | 内容服务 Rex 客户端固定 HTTP/1.1 |
| `pyarrow 26` 要求 NumPy 2，与 `numpy<2` 冲突 | 固定 `pyarrow==14.0.2` |
| ModelScope 1.16.0 元数据缺 framework 依赖 | 显式安装 `addict`、`oss2`、`jsonlines`、`accelerate` 等 |
| 官方 wrapper `forward()` 打印输入 | 直接调用 `trainer.prediction_step`，不进入 wrapper 的 print 路径 |

## 7. 内容服务接入

- 配置前缀：`msd.ai.rex`，见 `deploy/nacos/content-config.yaml`。
- `RexAdvisoryService` 与 DeepSeek 合规调用并行；合规结束后最多等待
  `join-timeout-ms=7500` 获取建议。
- 失败后 30 秒退避，避免 sidecar 关闭时反复打点。
- 标签候选来自 drama 只读接口
  `GET /internal-source/reviews/tag-dictionary`，缓存 5 分钟；失败使用 stale cache
  或 Nacos 默认列表。
- 写回 JSON 上限 7800 字；超限依次去掉证据、标签、整个 advisory，审核状态不变。

持久化示例：

```json
{
  "scene": "drama_description",
  "verdict": {"is_violation": false, "violation_reason": "", "tags": []},
  "degraded": false,
  "degradedFlags": [],
  "elapsedMs": 1200,
  "auditedAt": "2026-10-27T12:00:00+08:00",
  "advisory": {
    "model": "iic/nlp_deberta_rex-uninlu_chinese-base",
    "revision": "v1.0.2",
    "sentiment": {
      "label": "negative",
      "rawLabel": "负向情感",
      "highNegative": true,
      "support": 1,
      "evidence": ["刑警追查十年悬案。"]
    },
    "tags": [{"name": "悬疑", "support": 1, "evidence": ["刑警追查十年悬案。"]}],
    "chunks": 1,
    "failedChunks": 0,
    "truncated": false,
    "degraded": false,
    "degradedFlags": [],
    "elapsedMs": 842
  }
}
```

## 8. 性能与降级

- 典型单片段约 0.2s；5000 字、6 片、12 个标签实测约 5.2s，低于 7s 预算。
- 失败演练：停掉 8108 后再次审核 5003，`aiReviewStatus` 仍为 2，advisory
  `degraded=true`、`degradedFlags=["rex_unavailable"]`；内容服务不重启。
- 回滚：Nacos 设 `msd.ai.rex.enabled=false` 并停 sidecar；不写标签关系表，因此不需要
  重建详情缓存或 RAG 索引。

## 9. 测试

```bash
# Python 单元测试（假引擎，不加载模型）
rex-uninlu-service\.venv\Scripts\python.exe -m pytest rex-uninlu-service/tests -q

# 真实模型冒烟
rex-uninlu-service\.venv\Scripts\python.exe scripts\probe-rex-uninlu.py

# Java
mvn -B -pl msd-content-service,msd-drama -am test
```

## 10. 已知限制

- 模型输出没有概率，只有命中片数；不支持展示置信度。
- 情感负向不等于违规，标签建议也不等于题材分类结论。
- 只分析标题、副标题、简介，不做字幕/ASR。
- CPU 单 worker 串行推理；批量审核长文本会明显变慢。
