# 积分充值与流水

充值链路为 Demo 级模拟支付：用户选择档位、创建订单、点击“模拟支付”后积分立即到账，并写入积分流水。不接入真实支付渠道。

## 1. 接口

经网关访问，路径前缀 `/api/order/internal`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/recharge/plans` | 上架档位列表 |
| POST | `/recharge/orders` | 创建/幂等返回订单 |
| GET | `/recharge/orders/{orderId}` | 当前用户订单详情 |
| POST | `/recharge/orders/{orderId}/mock-pay` | 模拟支付并到账 |
| GET | `/orders/points/ledger?pageNo=&pageSize=` | 当前用户积分流水分页 |

创建订单：

```json
{"planId":4001,"clientRequestId":"p3-1792942570"}
```

```json
{
  "orderId": 7008,
  "orderNo": "R2114380505712484353",
  "planId": 4001,
  "planName": "体验包",
  "amountCents": 600,
  "points": 60,
  "status": 0,
  "payChannel": 1,
  "clientRequestId": "p3-1792942570",
  "expireTime": "2026-10-26T00:06:10",
  "payTime": null,
  "createTime": "2026-10-25T23:36:10"
}
```

模拟支付：

```json
{
  "orderId": 7008,
  "orderNo": "R2114380505712484353",
  "status": 1,
  "points": 60,
  "balance": 60,
  "alreadyPaid": false
}
```

## 2. 幂等与事务

- 下单幂等：`UNIQUE(user_id, client_request_id)`；相同 `clientRequestId` 返回原订单。
- 订单号：`R` + MyBatis-Plus 雪花 ID，长度 20，列宽 32。
- 支付：`SELECT ... FOR UPDATE` 锁订单行，条件 `UPDATE status=0 -> 1`；重复支付返回 `alreadyPaid=true`，不再加积分。
- 入账与流水在同一个本地 `@Transactional` 中，不需要 Seata。
- 流水幂等键固定为 `order:{orderId}`，唯一索引兜底。
- 积分账户不存在时自动创建（`INSERT ... ON DUPLICATE KEY UPDATE`），充值后 `GET /orders/points` 返回 `exists=true`。
- 待支付订单超过 30 分钟，模拟支付时标记 `status=3` 并返回 409；不做定时过期任务。

## 3. 状态

| status | 含义 |
|---|---|
| 0 | 待支付 |
| 1 | 已支付 |
| 2 | 已取消（Demo 暂未提供取消接口） |
| 3 | 已过期 |

`pay_channel=1` 表示 MOCK。

## 4. 前端

- `/recharge` 页面：余额卡片、三个档位、模拟支付、积分流水。
- 个人中心积分卡片和菜单增加“充值中心”入口。
- 解锁弹层积分不足或未开通账户时显示“去充值”。
- 支付按钮请求期间禁用，避免双击；成功后刷新余额和流水。

## 5. 手工验证

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1008 --role USER)

curl -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/order/internal/recharge/plans

ORDER=$(curl -s -X POST http://127.0.0.1:8000/api/order/internal/recharge/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"planId":4001,"clientRequestId":"demo-p3-0001"}')

curl -X POST http://127.0.0.1:8000/api/order/internal/recharge/orders/{orderId}/mock-pay \
  -H "Authorization: Bearer $TOKEN"

curl -H "Authorization: Bearer $TOKEN" \
  'http://127.0.0.1:8000/api/order/internal/orders/points/ledger?pageNo=1&pageSize=20'
```

实测（2026-10-25）：

- 1008 充值 4001：余额 `0 -> 60`，流水 `order:7008`。
- 重复支付返回 `alreadyPaid=true`，余额仍为 60。
- 相同 `clientRequestId` 再次下单返回同一订单 7008。
- 1008 再充值 4002：余额 `60 -> 390`。
- 新用户 1014：充值前 `exists=false`，支付后 `balance=60`、`exists=true`。
- 前端 `/recharge` 和“充值中心”入口已通过 `npm run build`，网关静态资源可访问。

## 6. 不在本范围

- 微信/支付宝、回调验签、退款、对账、发票。
- 订单取消、定时过期、真实支付 SDK。
- 充值优惠券、首充礼包、积分过期。
