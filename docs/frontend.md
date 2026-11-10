# 前端应用

`frontend/` 是 Vue 3 + Vite 单页应用，响应式布局：窄屏保持 480px 移动壳，≥1024px 切换为桌面壳（左侧栏 + 顶部栏）。开发时通过 5173 端口访问，`/api` 代理到网关 8000；生产构建产物由网关静态托管。

## 1. 技术栈与运行

- Vue 3.5、vue-router 4、Pinia 3、Vite 7，纯 JavaScript。
- 不引入 UI 库、axios、TypeScript；图标全部内联 SVG。
- 封面不请求远程图片，播放不加载真实视频。

```bash
cd frontend
npm install
npm run dev      # http://127.0.0.1:5173
npm run build    # 输出 frontend/dist
```

演示账号：`13800000003 / Msd@123456`（用户 1003）。种子基线为余额 250、已解锁 6003/6005；后续人工播放/解锁会改变当前数据。

## 2. 路由

| 路径 | 页面 | 布局 |
|---|---|---|
| `/login` | 登录 | 公开 |
| `/register` | 注册 | 公开 |
| `/` | 首页 | 底部 Tab 首页 |
| `/history` | 追剧记录 | 底部 Tab 追剧 |
| `/search` | 搜索/分类发现 | 子页 |
| `/me` | 个人中心 | 底部 Tab 我的 |
| `/me/favorites` | 我的收藏 | 子页 |
| `/me/comments` | 我的评论 | 子页 |
| `/me/likes` | 点赞的短剧 | 子页 |
| `/recharge` | 充值中心 | 子页 |
| `/me/unlocks` | 已解锁剧集 | 子页 |
| `/creator/dramas` | 创作者中心 | 子页，CREATOR/ADMIN |
| `/creator/dramas/new` | 新建短剧 | 子页，CREATOR/ADMIN |
| `/creator/dramas/:id/edit` | 编辑短剧 | 子页，CREATOR/ADMIN |
| `/admin/reviews` | 审核台 | 子页，ADMIN |
| `/admin/comments` | 评论治理 | 子页，ADMIN |
| `/drama/:dramaId` | 红果式全屏 Feed | 沉浸式 |
| `/watch/:episodeId` | 旧播放入口，解析后渲染同一 Feed | 沉浸式 |
| 其他 | 404 | 公开 |

- 除登录、注册、404 外都要求有效会话；未登录跳转 `/login?redirect=<原路径>`。
- 注册成功同样支持 `redirect` 回跳。
- 任意接口 401 触发全局清理，并行请求只跳转一次。
- `/drama/:dramaId?episode=<id>&start=<毫秒>` 直接定位某集；`start` 从观看历史续播。
- 切集不写 query，避免 `RouterView :key="route.fullPath"` 重挂载。

## 3. 页面与接口

| 页面 | 接口 |
|---|---|
| 登录/注册 | `POST /api/user/auth/login`、`/register` |
| 首页 | `GET /api/drama/internal/home`、`GET /api/content/internal/ai/rag/personalized`、`POST /api/content/internal/ai/rag/preferences`、`POST /api/content/internal/ai/rag/personalized/evict` |
| 推荐归因 | `POST /api/interaction/internal/recommendations/events` |
| 评论课代表 | `GET /api/interaction/internal/ai/comments/summary` |
| 搜索/分类 | `GET /api/drama/internal/categories`、`GET /api/drama/internal/search/dramas` |
| AI 找剧 | `POST /api/content/internal/ai/rag/recommend`、`POST /api/content/internal/ai/rag/recommend/stream`、`GET /api/content/internal/ai/rag/related` |
| 短剧 Feed | `GET /api/drama/internal/dramas/{id}`、`GET /api/interaction/internal/interactions/dramas/{id}/state`、`GET /api/order/internal/orders/dramas/{id}/unlocks`、`GET /orders/points`、`GET /playback/episodes/{id}` |
| 前情提要 | `GET /api/content/internal/ai/companion/recap?dramaId=&episodeNo=` |
| AI 伴看 | `POST /api/content/internal/ai/companion/chat`、`POST /api/content/internal/ai/companion/chat/stream` |
| 播放信息 | `GET /api/order/internal/playback/episodes/{id}` |
| 积分解锁 | `GET /api/order/internal/orders/points`、`POST /api/order/internal/orders/episodes/{id}/unlock` |
| 充值中心 | `GET /recharge/plans`、`POST /recharge/orders`、`POST /recharge/orders/{id}/mock-pay`、`GET /orders/points/ledger` |
| 点赞/收藏 | `POST/DELETE /api/interaction/internal/interactions/likes`、`/favorites` |
| 评论 | `GET/POST /comments`、`GET /comments/{rootId}/replies`、`DELETE /comments/{id}` |
| 观看历史 | `GET/POST /history`、`DELETE /history/{episodeId}`、`DELETE /history` |
| 个人中心 | `GET/PUT /api/user/internal/users/me`、`GET /orders/points`、`GET /interactions/stats` |
| 四个列表 | `/favorites`、`/history`、`/comments/mine`、`/likes/dramas` |

- 所有业务接口统一返回 `Result`，`api/http.js` 负责解包、附加 Bearer Token 和抛出 `ApiError(code, message, status)`。
- `api/http.js` 的 `streamRequest()` 用 `fetch + ReadableStream` 解析 SSE，支持 `Authorization`、`AbortController` 和总超时；非 `text/event-stream` 响应抛 `ApiError(-3)`，调用方回退普通 JSON。
- 内部超时和调用方主动取消共用 `code=-2`，但 `ApiError.timedOut` 会区分两者；AI 找剧和伴看只在主动取消时静默退出，超时会回退 JSON 或展示重试提示，避免页面停留在空白状态。
- Token 与用户资料存放在 `localStorage` 的 `msd.auth`，不使用 Cookie。
- 分页统一 `pageNo/pageSize`，页面采用“加载更多”，不做页码跳转。
- 列表数据由后端批量补齐；标题或昵称为空时前端降级显示，不阻断列表。

### 3.1 搜索与分类

- 首页头部“搜索短剧、演员、剧情”入口跳转 `/search`。
- 搜索页支持关键词（≤50 字）、分类 chips、最新/最热切换和分页加载；分类接口失败时隐藏 chips，不阻断搜索。
- 关键词只在提交时生效；再次点击已选分类会取消筛选。
- 结果复用 `DramaCard` 卡片和 `usePagedList`，空结果显示 `EmptyState`。
- “AI 找剧”优先走 SSE，回答逐字展示；网关不支持 SSE 或首帧前失败时自动回退普通 JSON。
- AI 结果展示回答、`rewrittenQuery`、最多 3 个来源卡片和降级原因；来源点击可进详情，登录用户可按 `requestId` 反馈。
- 后端只返回已上架短剧；关键词模式仍是 MySQL LIKE，AI 模式走 Milvus + ES + Rerank。

## 4. 封面与视觉

- `GradientCover.vue` 用 `dramaId`/`episodeId` 做 FNV-1a hash，映射为固定色相、角度、光斑和水印字，同一个对象每次渲染颜色一致。
- 首页卡片、Banner、Feed 全屏背景、历史/收藏缩略图、播放器舞台统一使用 `GradientCover`：有 `coverUrl` 时渲染真实封面，为空或加载失败时回退渐变。MinIO 未启动只会退化成渐变封面，不会出现破图。
- `UserAvatar` 优先加载 `avatarUrl`，失败或为空时回退昵称首字渐变头像。
- 视觉采用薄荷奶白浅色主题 + 白色卡片 + 珊瑚橙品牌色；视频舞台、封面和 Banner 保留深色媒体区，保证画面上的文字可读。移动端按 360–480px 设计，桌面端使用侧栏 + 宽内容区，见第 8 节。

## 5. 全屏 Feed 与视频播放器

- 点开短剧直接进入 `/drama/:dramaId` 全屏 Feed，首集自动播放；顶栏常驻返回、剧名和 `当前集/总集数`。
- 每集一个全屏 slide，原生 `scroll-snap-type: y mandatory` 上下滑动切集；最后一集后是“已看完”结束卡，可返回首页或重看第 1 集。
- 右侧操作栏：点赞、收藏、评论、看点、伴看、分享（复制链接）；底部显示集号/标题、剧名、简介和“选集”入口。
- “看点”打开前情提要：当前集摘要 + 当前集之前的摘要，点击前情可跳到对应剧集；桌面端打开工作区“看点”tab。
- “伴看”打开 AI 伴看面板，仅当前集免费或已解锁时可用；优先 SSE 逐字输出，首帧前失败自动回退 JSON。
- 伴看回答下方可展开“参考 N 条资料”（本集摘要/第 X 集前情），并可复制、追问、反馈；本地最多保留 10 个会话，离开再进入可切换继续。
- 详情信息栏和 Feed 结束卡展示“你可能还喜欢”，复用 `DramaCard` 和相似推荐理由。
- 点赞/收藏为抖音式动态按钮：点击激活时图标回弹、粒子与光环爆发、计数跳动；激活后点赞为红色实心爱心、收藏为金色实心星星，取消恢复白色描边。动画只由点击触发，加载已有互动状态不播放。
- 简介/选集/评论均为底部弹层；评论弹层支持“本集 / 全剧”切换。打开弹层时暂停当前集并上报进度，关闭后恢复原播放状态。
- `/watch/:episodeId` 只做 episode → dramaId 解析，然后渲染同一 Feed；新链接优先使用 `/drama/:dramaId?episode=`。
- 使用原生 `<video>` 播放播放接口返回的 `videoUrl`；未解锁时接口不下发地址，只展示解锁面板。
- 视频默认静音自动播放，控制条提供静音切换；16:9 素材在竖屏舞台内等比显示，背景是同一封面模糊放大，避免拉伸或大面积黑边。
- ≥1024 时全屏 Feed 变为“模糊封面氛围 + 居中 9:16 剧场卡 + 右侧信息栏”；滚动切集、播放器、上报与解锁逻辑不变，移动端仍是全屏上下滑。
- 时长来自播放接口的 `durationSeconds`（毫秒）；使用 `requestAnimationFrame` + 单调时间差推进，页面隐藏自动暂停。
- 每个 slide 独立持有播放器和上报器；失活 slide 立即暂停并 flush，重新激活时恢复播放。
- 未解锁不启动播放，只展示价格、余额和解锁按钮；解锁成功后重新请求播放信息，确认 `unlocked=true` 才播放。
- 新用户没有积分账户时按钮禁用并提示；积分不足显示差额。

### 5.1 观看历史上报

- 只有 `free || unlocked` 且用户开始播放后才启用上报；每个 slide 独立上报器。
- 每累计播放 5 秒上报一次；暂停、拖动结束、切集失活、页面隐藏、路由离开、`pagehide` 时补报。
- 上报串行化，期间的新位置合并为最新快照，避免旧请求后到覆盖新位置。
- 播放到 95% 进入完成态，停止计时并只发送一次 `finished=true`。
- **完成后锁定上报器**：重播只重置本地画面，不再写非完成状态。原因是 `POST /history` 是 upsert，每次都会用请求里的 `finished` 覆盖旧值，完成后再发 `finished=false` 会把记录改回未完成。
- 离开页面时优先等待队列清空（最多约 1.2 秒），`pagehide` 使用 `fetch keepalive`；不使用 `sendBeacon`，因为无法附加 `Authorization`。
- 锁屏状态绝不上报历史。

## 6. 评论

- Feed 右侧评论按钮打开底部弹层，顶部可在“本集 / 全剧”之间切换：本集传 `episodeId`，全剧不传。
- 弹层打开时 v-if + key 挂载 `CommentSection`，切集后重新打开会按新集请求，不会复用旧列表。
- 根评论按时间倒序，每页 10 条；回复首次展开时才请求，按时间正序，每页 10 条。
- 支持发布根评论、回复根评论、回复某条回复；服务端写入 `reply_to_user_id`，前端展示“回复 @昵称”。
- 评论点赞走 `targetType=3`，本地乐观更新；`useInteractionAck` 轮询事件状态，DEAD 时回滚。
- 仅作者可删除；删根评论会级联删除回复，前端确认框会提示。
- 发布/回复成功后本地插入新评论并同步根评论 `replyCount`，不整页刷新；每次提交生成 `clientRequestId`，失败重试复用。
- 评论内容上限 1000 字，输入框实时计数。
- 评论面板顶部展示“AI 课代表（仅供参考）”：摘要、情绪和最多 3 条代表评论；降级时隐藏，不阻断评论列表。
- 管理端评论治理支持按 AI 状态筛选（风险/失败/通过/未审），展示 AI 风险原因和标签，并支持“AI 重审”。
- 点赞/收藏/评论写成功后按 0/300/800/1500ms 轮询 `GET /events/{eventId}`；页面隐藏暂停，SENT 且已有消费组后拉取 `GET /dramas/{id}/stats` 校准计数，DEAD 回滚并提示。

## 7. 个人中心

- 进入页面并行请求 `GET /me`、积分、统计；资料失败时保留本地缓存，积分/统计各自显示重试。
- 四项统计可点击进入对应列表；下方菜单提供同样的四个入口。
- 资料编辑使用底部弹层，只提交昵称/头像 URL/简介；空头像与空简介表示清空。
- 昵称 1–20 字、头像 URL ≤512、简介 ≤255，前后端校验一致。
- 收藏/点赞列表支持取消；观看历史支持删除单条和清空；我的评论支持删除。
- `1003` 的基线统计为 `1/1/2/4`。

## 8. 桌面端适配（≥1024）

同一套路由和组件按视口切换布局；`--app-width: 480px` 继续服务移动壳、Feed、弹层和 Toast，桌面端不再全局放大。

### 8.1 断点

| 视口 | 布局 |
|---|---|
| < 1024 | 现行移动布局：底部 TabBar、底部弹层、480px 内容区 |
| ≥ 1024 | 左侧栏 232px + 顶部栏 64px；隐藏 TabBar；`.app-main` 最宽 1360px |
| ≥ 1280 | 搜索/列表使用更宽网格；播放页剧场卡与右侧信息栏间距加大 |

- `useMediaQuery` 封装 `matchMedia`，JS 与 CSS 统一使用 1024 断点；1280 只服务网格密度。
- 登录、注册、404 不加桌面壳；`/drama`、`/watch` 保留左侧栏、隐藏顶部栏和底部 TabBar，播放区域保持深色剧场卡。

### 8.2 桌面壳

- `App.vue` 在 `isDesktop && !route.meta.public` 时渲染 `DesktopSidebar`；顶部栏额外要求 `!route.meta.immersive`。播放页侧栏隐藏“AI 找剧”入口，避免打开未挂载的全局助手。
- 侧栏分组：主导航（首页/发现/追剧/我的）、我的内容（收藏/点赞/我的评论/已解锁/充值）、工作台（按角色显示创作者中心/审核台/评论治理）和底部用户卡。
- 顶部栏显示页面标题、全局搜索（跳 `/search?keyword=`）和头像；页面内 `PageHeader` 在桌面隐藏标题，只保留副标题和页面操作。
- 侧栏入口只做前端隐藏，后端仍做最终角色校验。

### 8.3 页面

- 首页：Banner 与“继续观看”双列，分类 chips 换行，热播/最新改为自适应网格；「为你推荐」有独立骨架屏，卡片展示推荐理由；冷启动展示“多看几部，推荐会更懂你”和分类引导，降级时保留热播并提示，分类筛选激活时隐藏。
- 发现：顶部是“关键词 / AI 找剧”双模式头部卡片，桌面端左侧文案 + 右侧大搜索框；下方是横向吸顶筛选条（分类 chips + 排序 + 重置）+ 全宽结果网格。结果区显示当前关键词、分类和总数；AI 模式空态提供可点击的示例描述，来源卡片固定 200px 宽。顶部栏和页面搜索框都同步 `keyword` 查询参数。
- Feed：≥1024 为“模糊封面氛围 + 居中 9:16 剧场卡 + 右侧 `clamp(320px, 27vw, 380px)` 信息栏”，不再有第三列。工作区 tab 为简介/选集/看点/评论/AI 伴看，默认简介；复用 `DramaInfoContent`、`EpisodeRail`、`EpisodeRecap`、`CommentSection`、`CompanionChat`。移动端保持全屏上下滑和底部弹层。
- 我的：桌面端 340px 资料栏（渐变头图资料卡 + 钱包式积分卡）+ 内容区；统计卡移动端 2×2、桌面端 4 列；菜单按“我的内容 / 账户与工作台”分组，桌面端每组两列，移动端单列并带图标。
- 收藏/点赞：海报网格；历史/评论/已解锁：≥1280 双列。
- 充值：≥1280 左侧充值 + 右侧积分流水。
- 创作者/审核/评论治理：一行一条的记录布局；审核弹层使用 `size="lg"`。审核台增加 AI 状态筛选、批量审核、AI 详情抽屉、失败重试和重建索引结果弹层；创作者列表和编辑页展示 AI 违规原因、命中标签和建议标签，不展示原始 JSON；编辑页增加“AI 预检”，合规风险和标题/副标题/标签建议在表单内展示，点击建议只填入表单。
- AI 反馈：登录用户在 AI 找剧、伴看和审核详情看到“有用/没用”，按 `requestId` 幂等提交；匿名 AI 搜索不展示。
- 登录/注册：左侧品牌介绍 + 右侧表单。

### 8.4 弹层与实例

- `AppModal` 新增 `size` prop：`md` 480px、`lg` 720px；桌面居中显示。
- Feed 的底部弹层只在移动端渲染（`v-if`），桌面使用常驻工作区，保证评论/伴看只有一份实例。
- 桌面切集只滚动 Feed，不关闭工作区；切到未解锁集会从伴看退回选集并提示。
- 桌面工作区不暂停播放；移动端弹层仍会暂停并上报进度。

### 8.5 浅色主题与媒体范围

- 全局 token 在 `frontend/src/styles/base.css`：薄荷奶白画布、白色卡片、珊瑚橙品牌色；`--text` 正文、`--text-soft` 次级正文、`--muted` 弱化信息。
- `--brand` 只用于填充和大色块；浅底文字统一用 `--brand-ink`（旧变量 `--brand-2` 兼容指向同一颜色）；`--brand-2-bright` 只用于渐变和媒体装饰。
- 视频、封面和 Banner 通过 `.feed-slide-content`、`.feed-end`、`.banner-slide` 作用域覆盖 `--text`/`--surface`/`--line` 等 token，内部组件自动使用深色媒体配色；teleport 到 `body` 的弹层仍使用全局浅色 token。
- 播放页深色视频舞台、封面 scrim、播放器进度条和结束卡 hero 是刻意保留的对比区域，不属于遗漏。

## 9. 构建与网关托管

开发：

```bash
cd frontend && npm run dev
```

- `vite.config.js` 把 `/api` 代理到 `http://127.0.0.1:8000`，需要网关和业务服务已启动。

生产：

```bash
cd frontend && npm run build
cd ..
mvn -B -pl msd-gateway -am package -DskipTests
java -jar msd-gateway/target/msd-gateway-1.0.0-SNAPSHOT.jar
```

- 网关在 `process-resources` 阶段把 `frontend/dist` 复制到 `target/classes/static`。
- 修改前端后必须重新构建前端并重新打包网关，仅重启网关不会更新页面。
- `SpaWebFilter` 把非 `/api`、`/actuator`、`/internal`、`/console` 且没有扩展名的 HTML 请求重写到 `index.html`，因此 `/drama/5001`、`/me/favorites` 可以直接刷新。
- 静态资源请求不经过网关 JWT 过滤器（过滤器只作用于命中的 `/api` 路由），页面本身可以在登录前加载。
- 接口 401 仍由网关返回统一 JSON，前端收到后跳登录。

## 10. 已知限制

- 没有真实视频播放、封面图片、视频签名 URL；MinIO 未启动不影响页面。
- Feed 只在本剧剧集间上下滑动，不跨短剧连播；最后一集后进入结束卡。
- 关键词搜索仍使用 MySQL LIKE，数据量上来后需要改 FULLTEXT/ES；AI 模式已接 RAG，但不做搜索历史、热词和拼音纠错。
- 多会话只存在当前浏览器的 `sessionStorage`，按当前登录用户隔离，不跨设备；服务端会话历史留到后续阶段。
- 首页分类 chips 仍只筛选已加载的热播/最新内容；完整分类浏览在 `/search`。
- 充值中心、积分流水、已解锁列表、创作者工作台、审核台和评论治理已接入；没有头像上传、退款；新用户充值支付时自动创建积分账户。
- 没有评论编辑、审核、敏感词、点赞列表。
- Kafka 未启动时短剧/剧集/评论冗余计数会滞后；前端先展示乐观值，再用事件状态轮询和 drama stats 校准，事件 DEAD 时回滚。
- 列表不做 KeepAlive，返回列表会重新请求第一页。
- 不引入 ESLint、单测、E2E 框架；构建通过 + 手工/无头浏览器验收。

## 11. 验收记录（2026-10-24，Feed 改造）

环境：MySQL、Nacos、Seata、msd-user 8101、msd-drama 8102、msd-order 8103、msd-interaction 8104、msd-gateway 8000；Kafka/Redis 已恢复可达；Vite 5173。

| 用例 | 结果 |
|---|---|
| `npm run build` | 通过，118 个模块，产物在 `frontend/dist` |
| `/drama/5001` | 7 个 slide（6 集 + 结束卡），顶栏 1/6，首集自动播放 |
| 首屏请求 | 只请求当前集 playback，未请求其他集 |
| 滚轮 / 触摸上滑 | 1/6 → 2/6；旧集暂停，新集播放；`inert` 不阻断滚动 |
| 页面滚动锁 | Feed 内 `body.overflow=hidden`；离开后恢复为空，底部 Tab 恢复 |
| 锁定集 6006 | 显示价格 50、余额 100、解锁按钮；无播放器 |
| 临时用户解锁 6006 | 余额 100→50，`unlocked=true`，播放器出现并自动播放 |
| 历史上报 | 6401 首次 5983ms；滑到 6402 后 6401 flush 到 9500ms；离开后 6402 为 7850ms |
| 简介弹层 | 显示封面、标签、演员、简介和计数 |
| 选集弹层 | 6 集，免费/已解锁/积分角标正确；选择后定位到对应集 |
| 评论弹层 | 5004 全剧显示 8314；本集/全剧切换正常；打开时播放暂停，关闭恢复 |
| 点赞/收藏动态按钮 | 点击后图标回弹、10 个粒子 + 光环爆发、计数跳动；取消恢复白色描边；加载已有状态不触发动画 |
| 结束卡 | 第 6 集后显示“已看完本剧”，重看/返回首页可用 |
| 旧入口 `/watch/6001` | 解析后进入同一 Feed，定位第 1 集 |
| 深链 `/drama/5001?episode=6002` | 直接定位第 2 集，顶栏 2/6 |
| 桌面 1440×900 | Feed 480px 居中，无横向滚动 |
| 控制台 | 无运行时异常、无接口错误日志 |

验收产生的临时用户、积分账户、解锁、历史已清理，测试用户对剧集播放/浏览计数的增量已回退。1003 的当前余额/解锁/评论以数据库为准（验收期间可能有人工操作，未强制回滚）。

## 12. 验收记录（2026-10-26，桌面端适配）

环境：Vite 5173、网关 8000 及各业务服务已启动；用无头 Chrome 按视口截图并读取 `document.documentElement.scrollWidth`。

| 用例 | 结果 |
|---|---|
| `npm run build` | 通过，产物在 `frontend/dist` |
| 1440 / 1280 / 1024 / 768 / 390 | `scrollWidth === innerWidth`，无横向溢出 |
| 桌面壳 | 1440 显示侧栏 + 顶部栏，TabBar 隐藏；`/me`、`/search` 的 PageHeader 不渲染 |
| 移动回归 390 / 768 | 底部 TabBar、480px 内容区、页面标题和底部弹层保持原样 |
| 首页 1440 | Banner 与继续观看双列；热播 6 列网格；分类 chips 换行 |
| 首页 1024 | 主内容 792px，Banner 与继续观看仍为双列 |
| 搜索 1440 | 左侧筛选栏 + 5 列结果网格；`keyword=盛夏` 命中 5101 |
| Feed 1440 | `grid-template-columns = 280px 700px 340px`，stage 480px，信息栏和工作区均渲染 |
| Feed 1280 | `grid-template-columns = 280px 660px 340px` |
| Feed 1024 | `grid-template-columns = 684px 340px`，信息栏隐藏 |
| Feed 390 | `grid-template-columns = none`，单列全屏，无工作区 |
| 工作区切集 | 点击第 3 集后 `scrollTop=1800`（900×2），active slide index=2，工作区不关闭 |
| 伴看锁定回退 | 伴看 tab 下滚到第 4 集（锁定）→ 自动回到选集并提示“解锁本集后可使用伴看” |
| 桌面转移动 | 1440 打开评论后缩到 390：工作区卸载、无残留弹层，`body.overflow=hidden` |
| 移动评论 | 390 点击评论按钮打开弹层，页面内只有 1 个 `CommentSection` |
| 个人中心 1440 | 左侧 340px 资料栏 + 右侧统计和两列菜单 |
| 收藏 1440 | 5 列海报网格；历史/评论 ≥1280 双列 |
| 充值 1440 | 双列，右侧积分流水 |
| 创作者 1440 | 一行一条；编辑页 `app-main` 宽 1000，字段两列、剧集五列、操作条吸底 |
| 审核台 / 评论治理 1440 | 一行一条记录，操作列右对齐；审核弹层 `size="lg"` |
| 登录 1440 | 左侧品牌卡 + 右侧表单分栏，品牌卖点和页脚显示 |
| 角色入口 | 1003 无工作台；1002 仅创作者中心；1001 显示创作者中心、审核台、评论治理 |

## 13. 验收记录（2026-10-26，真实封面与视频）

| 用例 | 结果 |
|---|---|
| MinIO 封面 | 9 张，`msd/covers/ai/5101-5109.jpg`，匿名 GET 返回 200 `image/jpeg` |
| MinIO 视频 | 54 段，`msd/videos/ai/5101-01.mp4 ... 5109-06.mp4`，匿名 GET 返回 200 `video/mp4` |
| 首页 1440 | 14 个 `.cover-image` 全部加载成功（Banner、继续观看、热播 6、最新 6） |
| 搜索 390 | 命中结果封面加载成功 |
| Feed 1440 | 信息栏封面加载成功；`<video>` 自动播放，`currentTime` 持续增长 |
| 切换剧集 | 第 2 集 `video.currentSrc` 变为 `5101-02.mp4`，继续播放 |
| 静音切换 | 点击控制条静音按钮后 `video.muted=false`，播放不中断 |
| 播放结束 | 第 1 集 23 秒结束，`ended=true`，显示“本集完”面板 |
| 锁定剧集 | 第 3 集只显示解锁面板，不创建 `<video>` |
| 观看历史 | 播放位置按 5 秒节奏上报，切集时补报 |
| 封面/视频缺失 | 封面回退渐变，视频回退封面占位并保留控制条，不出现破图 |

## 14. P4 联调（2026-10-27）

| 用例 | 结果 |
|---|---|
| 生产构建 | `npm --prefix frontend run build` 通过，157 modules |
| 首页「为你推荐」网关接口 | 登录用户返回 `personalized=true` 卡片；未登录 401 |
| 冷启动用户 | 返回 `personalized=false, coldStart=true` 和带理由的热播卡片，前端显示引导与分类 chips |
| 互动 ack 接口 | 点赞/评论事件返回 `SENT`，`consumedGroups` 同时包含 drama 和 interaction 消费组 |
| 网关内部接口隔离 | `/api/interaction/internal-source/**` 返回 404 |

## 15. P1-1/P1-2/P1-3（2026-11-06）

| 功能 | 实现 |
|---|---|
| AI 找剧流式 | `SearchView` 优先 `streamRecommendDramas`，首帧前失败回退 `recommendDramas`；回答逐字展示，来源和反馈保留 |
| 伴看流式 | `CompanionChat` 优先 `streamCompanionChat`；支持停止、超时提示、引用展开和 JSON 回退 |
| 本地多会话 | `msd.companion.sessions.u{userId}.{dramaId}` 保存最多 10 个会话；按用户隔离，旧共享键不再读取 |
| 前情提要 | Feed 右侧「看点」、桌面工作区「看点」tab、移动端底部弹层；点击前情跳转剧集 |
| 相似短剧 | 详情信息栏和 Feed 结束卡展示“你可能还喜欢”，使用相似推荐理由 |
| 验证 | `mvn -B -ntp -T 1C test` BUILD SUCCESS（content 164、drama 75）；`npm --prefix frontend run build` 通过 |

未做真实浏览器 SSE 联调；网关缓冲、断线重连和首 token 指标需启动完整环境后验证。

## 16. P1-4/P1-5（2026-11-06）

| 功能 | 实现 |
|---|---|
| 个性化 v2 | 卡片带 `requestId`；点击/播放/完播/不感兴趣上报 `recommendations/events`；卡片右上角“不感兴趣” |
| 冷启动引导 | 冷启动分类 chips 调 `POST /rag/preferences`，保存后立即刷新推荐 |
| 曝光归因 | content 侧自动写 `t_recommendation_exposure`；前端点击写 `t_recommendation_event` |
| 评论课代表 | 评论面板顶部展示摘要、情绪和代表评论，标注“仅供参考” |
| 评论 AI 预审 | 管理端评论治理增加 AI 状态筛选、风险原因/标签和“AI 重审” |
| 验证 | `mvn -B -ntp -T 1C test` BUILD SUCCESS（content 168、interaction 33、drama 75、agent 42、user 9）；`npm --prefix frontend run build` 通过 |

未做真实浏览器联调；推荐 CTR、完播率和评论预审异步链路需启动完整环境后用数据库和日志验证。

## 17. P1-6/P1-7（2026-11-06）

| 功能 | 实现 |
|---|---|
| 创作者 AI 预检 | 编辑页新增“AI 预检”卡片；展示合规风险、命中标签、标题/副标题/标签/整改建议；点击标题或副标题建议只填入表单 |
| 预检降级 | `degraded` 提示“以人工审核为准”；`suggestionDegraded` 只隐藏建议，不影响合规结论；429 显示每日额度提示 |
| 增量索引 | 前端无新增入口；审核通过/下架后后端自动更新 RAG 索引 |
| 验证 | `mvn -B -ntp -pl msd-content-service,msd-drama -am test` BUILD SUCCESS（content 189、drama 79）；`npm --prefix frontend run build` 通过 |

未做真实浏览器和完整栈联调；预检额度、缓存和 Kafka 增量索引需启动 Redis/Kafka/Milvus/ES 后验证。

## 18. P2-1/P2-2（2026-11-06）

| 功能 | 实现 |
|---|---|
| 字幕导入 | 创作者编辑页“字幕导入”区：选择已保存剧集 + SRT/VTT 文件，提交后覆盖同语言字幕 |
| 字幕引用 | 伴看回答的 `sources` 增加 `type=transcript`，展示“第 N 集字幕 · mm:ss”和“跳到 mm:ss”按钮 |
| 播放位置 | `FeedSlide` 每秒上报当前集 `positionMs`；`CompanionChat` 请求携带 `positionMs` 做当前集防剧透 |
| 字幕跳转 | 点击字幕引用后切到目标集并 seek；Feed 通过 `seekRequest` 把位置传给目标 slide |
| 全局助手 | 桌面侧栏“AI 找剧”+ 移动端悬浮按钮；弹层内多轮会话、快捷问题、可点击短剧卡片 |
| 会话持久化 | 全局助手使用按 `userId` 隔离的 `sessionStorage` 保存 `sessionId` 和最近 30 条消息 |
| 验证 | `mvn -B -ntp -pl msd-content-service,msd-drama,msd-agent-service -am test` BUILD SUCCESS；`npm --prefix frontend run build` 通过 |

未做真实 ASR 转写和完整栈联调；字幕时间轴 seek、Supervisor 工具调用和 DRAMA_CARD 保留需启动完整环境验证。

## 19. P2-3/P2-4（2026-11-06）

| 功能 | 实现 |
|---|---|
| 创作者 Copilot | 编辑页新增“AI 创作助手”：选择风格和 2/3 个版本，一次生成多套标题/副标题/标签 |
| 版本对比 | 每个方案展示角度、标题、副标题、标签和推荐理由；“采用整套/用标题/用副标题”只回填表单 |
| 单版本预检 | 每个方案可单独“合规检查”，复用 AI 预检接口，结果内联显示，不写审核状态 |
| 采纳反馈 | 每个生成结果首次采纳时上报 `CREATOR_COPY / ADOPT` 反馈；失败静默，不阻断编辑 |
| 灰度与降级 | 前端不感知 RAG 分桶；AI 找剧关闭或预算耗尽时展示后端固定文案和中文降级提示 |
| 验证 | `mvn -B -ntp -pl msd-content-service,msd-interaction -am test` BUILD SUCCESS；`npm --prefix frontend run build` 通过 |

未做真实浏览器联调；Copilot 模型输出、采纳反馈和 RAG 灰度分桶需要启动完整环境验证。

## 20. 浅色主题与 PC 剧场改版（2026-11-06）

| 用例 | 结果 |
|---|---|
| `npm --prefix frontend run build` | 通过，产物由 gateway 重新打包托管 |
| 主题 | 全局切换为薄荷奶白画布 + 白色卡片 + 珊瑚橙；`body` 使用低饱和薄荷/珊瑚径向渐变 |
| 1440×900 首页 | 侧栏 + 顶部栏常驻，Banner/继续观看双列，无横向溢出 |
| 1440×900 播放页 | 侧栏常驻、顶部栏隐藏；9:16 剧场卡 484×860，右侧信息栏 380×860，白底、5 个 tab，默认“简介” |
| 1024×600 播放页 | 侧栏 232 + 剧场卡 315×560 + 信息栏 320×560，无横向溢出 |
| 390×844 播放页 | 全屏 Feed、无工作区/氛围层，底部弹层保持浅色，无横向溢出 |
| 工作区切 tab | 简介/选集/看点/AI 伴看切换正常，面板高度 860，切集不关闭工作区 |
| 移动弹层 | 评论/简介弹层白底深字，输入框白底，`scrollWidth === innerWidth` |
| 其他页面 | 搜索、个人中心、历史、收藏、充值、创作者中心/编辑、审核台、评论治理、登录、404 均为浅色表面 |
| 控制台 | 各页面 `window.__errors` 为空 |
| 遗留 | `FeedInfoPanel.vue` 保留但不再引用；`isWide` 和第三列布局已移除 |

播放器 `<video>` 在无头 Chrome 中未产生首帧，沿用 2026-10-26 的真实视频验收结论；本次只改视觉与布局，未改播放逻辑。
