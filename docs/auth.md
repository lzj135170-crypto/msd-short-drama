# 登录与注册

`msd-user` 提供手机号 + 密码认证：密码用 BCrypt 校验 `t_user.password_hash`，登录成功后签发 HS256 JWT。网关负责校验 Token，并向下游透传 `X-User-Id` / `X-User-Role`。

## 1. 接口

### 1.1 注册

`POST /api/user/auth/register`（网关白名单，不需要 Token）

请求：

```json
{
  "phone": "13900000000",
  "password": "Msd@123456",
  "nickname": "验收用户"
}
```

| 字段 | 必填 | 规则 |
|---|---|---|
| `phone` | 是 | 11 位，`1[3-9]` 开头，全局唯一 |
| `password` | 是 | 8–32 位，至少包含一个字母和一个数字 |
| `nickname` | 否 | 最多 20 字符；为空时使用「剧迷 + 手机号后四位」 |

成功响应（200）：

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresInSeconds": 86400,
    "user": {
      "id": 1014,
      "phone": "13900000000",
      "nickname": "验收用户",
      "avatarUrl": "",
      "bio": "",
      "role": "USER",
      "createTime": "2026-10-23T23:56:44.4807015"
    }
  },
  "success": true
}
```

新用户固定 `role=0`（USER）、`status=1`（正常），本切片不创建积分账户。

### 1.2 登录

`POST /api/user/auth/login`（白名单）

请求：

```json
{
  "phone": "13800000003",
  "password": "Msd@123456"
}
```

成功响应与注册相同。登录成功会更新 `t_user.last_login_time` 和 `t_user.last_login_ip`。

### 1.3 当前用户

`GET /api/user/internal/users/me`（需要 `Authorization: Bearer <token>`）

返回当前用户的 `UserProfile`，不包含密码哈希。网关在 Token 缺失或非法时直接返回 401，请求不会进入用户服务。

## 2. 错误码

| HTTP | code | 场景 |
|---|---|---|
| 400 | 40000 | 手机号格式、密码长度/字符、昵称长度、请求体格式错误 |
| 401 | 40100 | 手机号或密码错误；Token 缺失/无效/过期；用户不存在或禁用 |
| 403 | 40300 | 账号已禁用（密码正确时） |
| 409 | 40900 | 手机号已注册 |
| 429 | 42900 | 网关限流 |
| 500 | 50000 | 未预期异常 |

登录时「用户不存在」和「密码错误」返回同一提示，避免暴露账号是否存在。

## 3. JWT 约定

- 算法 HS256，密钥 `msd.auth.jwt.secret`（环境变量 `MSD_JWT_SECRET`），至少 32 字节。
- 签发方 `msd.auth.jwt.issuer`（`MSD_JWT_ISSUER`），默认 `msd`。
- 有效期 `msd.auth.jwt.ttl-seconds`，默认 86400 秒。
- 声明：`sub=userId`、`role=USER|CREATOR|ADMIN`、`iss`、`iat`、`exp`。
- `msd-user` 和 `msd-gateway` 必须使用同一组 secret/issuer，否则网关会拒绝登录接口签发的 Token。

## 4. 实现位置

- `msd-user/src/main/java/com/msd/user/controller/AuthController.java`
- `msd-user/src/main/java/com/msd/user/service/AuthService.java`
- `msd-user/src/main/java/com/msd/user/auth/JwtTokenIssuer.java`
- `msd-user/src/main/java/com/msd/user/config/PasswordConfig.java`
- `msd-user/src/main/java/com/msd/user/web/GlobalExceptionHandler.java`
- `msd-gateway/src/main/java/com/msd/gateway/filter/JwtAuthenticationFilter.java`（白名单与身份头透传）

## 5. 验收记录（2026-10-23）

环境：MySQL 3306、Nacos 8848、msd-user 8101、msd-gateway 8000。

| 用例 | 结果 |
|---|---|
| `13800000003 / Msd@123456` 登录 | 200，role=USER，expiresInSeconds=86400 |
| 带 Token 查询 `/me` | 200，返回「追剧用户」，无密码哈希 |
| 密码错误 / 手机号不存在 | 401 `手机号或密码错误` |
| 禁用账号 `13800000009` | 403 `账号已禁用` |
| 登录手机号格式错误 | 400 `手机号格式不正确` |
| 重复注册 `13800000003` | 409 `手机号已注册` |
| 密码太短 / 无数字 | 400，对应字段提示 |
| 无 Token / 非法 Token 访问 `/me` | 401 `未登录` / `Token 无效或已过期` |
| 新手机号注册 | 200，id=1014，role=USER，Token 可用 |
| 中文昵称注册 | 200，昵称正确保存 |
| 请求体非法 JSON | 400 `请求体格式不正确`（统一 Result） |
| `t_user` 核对 | 新用户哈希 `$2a$`、60 字符；1003 登录时间/IP 已更新 |
| 网关回归 | `/internal/ping`、`/internal/users/1003` 正常，无 Token 401 |

验收测试用户已清理（`13900000000`、`13900000001`）。

## 6. 不在本切片

- 退出登录、刷新 Token、改密、找回密码、登录失败锁定。
- 注册赠送积分或创建积分账户。
- 登录专用限流；当前只有网关按路由的 10 QPS 流控。
- Token 有效期 24 小时，角色变更需重新登录后才体现在 Token 中。
- 直连 8101 会绕过网关鉴权，生产只暴露 8000。
