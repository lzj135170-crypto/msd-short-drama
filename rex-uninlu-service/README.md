# RexUniNLU sidecar

短剧审核的本地情感/标签建议服务，监听 `127.0.0.1:8108`。

```cmd
scripts\setup-rex-uninlu.cmd
scripts\start-rex-uninlu.cmd
curl http://127.0.0.1:8108/readyz
```

- 模型：`iic/nlp_deberta_rex-uninlu_chinese-base@v1.0.2`（Apache 2.0）
- 输入：短剧标题 + 副标题 + 简介；450 字/片，最多 6 片
- 输出：情感倾向、`highNegative`、标签建议和证据；`support` 是命中片数
- 边界：只作为审核建议，不参与合规 2/3/4 状态，不写 `t_drama_tag_rel`

完整说明见 [docs/rex-uninlu.md](../docs/rex-uninlu.md)。
