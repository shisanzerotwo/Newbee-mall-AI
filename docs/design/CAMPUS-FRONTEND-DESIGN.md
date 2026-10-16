# 伊犁师范大学 · 校园二手交易平台 前端开发设计文档

> 版本 v1.0 · 2026-10-15 · 状态：已确认方向（方案 A · 伊师大墨绿）
> 视觉唯一依据：`docs/design/campus-mall-mockups.html`（方案 A）
> 上游约束：所有视觉 token 必须回溯到 mockups 的 `.thA` 变量；后端复用 newbee-mall-ai 现有 Spring Boot 服务，智能客服复用 `POST /api/cs/chat`（SSE）。

---

## 1. 产品定位与范围

- **定位**：面向伊犁师范大学（本部为先，预留多校区）的 C2C 闲置交易平台，参考闲鱼的信息架构，移动端 H5 优先，后续可套壳微信小程序。
- **用户**：本校学生/教职工（学号/校园邮箱认证）；不开放社会用户。
- **核心闭环**：逛（首页/分类/搜索）→ 买（详情/私聊/担保下单）→ 卖（发布/AI 估价）→ 沟通（消息/AI 客服）→ 管理（我的/订单/钱包）。
- **MVP 范围（一期）**：首页、分类、商品详情、发布、消息列表、AI 客服、我的、登录/校园认证、搜索。
- **二期**：担保交易/钱包、求购专区、评价、举报与信用分、多校区切换。

## 2. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 框架 | Vue 3 + Vite + `<script setup>` | 与团队技术栈一致（newbee-mall 前端亦为 Vue 系），生态成熟 |
| 语言 | JavaScript（一期）| 降低上手成本；二期可平滑迁 TS |
| UI 组件 | Vant 4（按需引入） | 移动端组件最全，闲鱼式交互（瀑布流、下拉刷新、图片预览、ActionSheet）开箱即用 |
| 状态管理 | Pinia | 官方推荐，轻量 |
| 路由 | Vue Router 4 | — |
| HTTP | Axios + 统一拦截器（token 注入、401 跳登录、错误 Toast） | — |
| SSE | 原生 `fetch` + ReadableStream（POST 流式） | `/api/cs/chat` 为 POST+SSE，EventSource 不支持 POST |
| 样式 | CSS 变量 token + Less | token 与 mockups 一一对应 |
| 字体 | 霞鹜文楷（CDN：`lxgw-wenkai-webfont`），标题/品牌场景；正文数字等宽回退系统字体 | 已确认 |
| 构建产物 | 静态 H5，部署于 Nginx（复用现有 docker-compose） | 与现有一致 |

## 3. 设计 Token（与 mockups `.thA` 一一对应）

```css
:root {
  /* 品牌 · 伊师大校徽墨绿 */
  --brand:      #0a5c46;  /* 主按钮 / 导航激活 / 强调 */
  --brand-2:    #1a7f63;  /* 辅助绿：头像、渐变 */
  --brand-grad: linear-gradient(120deg,#084d3a,#1a7f63);
  --brand-chip: #e4f0ea;  /* 浅绿底（选中/标签底） */

  /* 强调 · 暖金（价格/促销/未读） */
  --accent:     #c98f1b;

  /* 中性 */
  --text-1: #1f2a26;  --text-2: #54605b;  --text-3: #8a948f;
  --bg-page: #f5f7f6; --bg-card: #ffffff; --border: #e2e8e5;
  --danger: #d1242f;  --success: #1a7f37;

  /* 字体 */
  --font-brand: "LXGW WenKai", -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
  --font-body:  -apple-system, "PingFang SC", "HarmonyOS Sans SC", "Microsoft YaHei", sans-serif;
}
```

- **使用规则**：页面标题/Banner/“我的”头部用 `--font-brand`；正文、价格、表单用 `--font-body`（保证可读性与数字对齐）。
- **价格**：`--accent` + 700 字重 + `font-variant-numeric: tabular-nums`。
- **禁用**：蓝色主按钮、紫粉渐变、多彩撞色（反 AI 味，与墨绿体系冲突）。

### 字号/空间/圆角
- 字号阶梯（px）：10 辅助 / 11 说明 / 12 正文基线（移动端）/ 13 区块标题 / 15 卡片标题 / 18 页面标题 / 22 详情页价格。
- 8pt 网格：间距取 4/8 的倍数；卡片内边距 12~14，区块间距 16。
- 圆角：卡片 10~14px；按钮/输入框 99px（胶囊）；头像 full。
- 阴影：单层 `0 4px 12px rgba(10,92,70,.08)`，不堆叠。

## 4. 信息架构与路由

```
/login                  登录（学号+密码 / 短信验证码）
/verify                 校园认证（未认证可浏览，不可交易/发布）
/                       首页（Tab）
/category               分类（Tab）
/publish                发布闲置（Tab，中间 FAB）
/messages               消息（Tab）
/chat/ai                AI 智能客服会话
/chat/:sessionId        买卖私聊
/mine                   我的（Tab）
/goods/:id              商品详情
/search                 搜索（含历史/热搜）
/orders, /wallet, /favorites, /wanted   （二期）
```

TabBar：首页 / 分类 / **发布（中央凸起 FAB，墨绿圆钮）** / 消息（未读角标）/ 我的。

### 页面结构要点（对照 mockups 六屏）

| 页面 | 关键模块 | 数据来源 |
|---|---|---|
| 首页 | 校区定位、搜索框、校训 Banner（品正学实）、6 分类金刚区、推荐瀑布流（双列，`van-list` + `van-pull-refresh`）；卡片含图/标题/价格/原价/成色标签/卖家头像与信用 | `GET /api/goods/recommend`（可复用现有 index-config 逻辑改造） |
| 分类 | 左侧一级分类（教材/数码/生活/服饰/票券/求购），右侧二级网格 | 复用现有 `goods-category` 三级分类 |
| 商品详情 | 图片轮播、价格+原价+担保标、标题、成色/购入时间/浏览量、卖家信用卡（校园认证标识、在售数）、描述区、底部操作条（想要/私聊/问 AI 客服/立即购买） | `GET /api/goods/:id` |
| 发布 | 图片九宫格上传（≤9，压缩 ≤1MB）、AI 生成标题/描述按钮 ✨、分类/成色 chips、价格输入 + AI 估价区间提示、交易方式（校内自提/送上门/邮寄）、发布按钮 | `POST /api/goods`；AI 能力复用后端 LLM 通道新增 `/api/ai/estimate`、`/api/ai/title` |
| 消息 | 会话列表：**AI 智能客服置顶**（🤖 官方标）、买卖私聊（未读 badge）、交易提醒、求购订阅 | `GET /api/im/sessions`（一期可用轮询） |
| AI 客服 | 流式回答（打字机渲染）、**商品卡片嵌入**（assistant 消息内解析 `goods` 结构化块渲染为可点击卡片）、快捷问题 chips、转人工入口、输入栏（表情/发送） | `POST /api/cs/chat`（SSE，现有接口） |
| 我的 | 墨绿渐变头部（头像/昵称/信用分/认证标识）、四宫格统计（发布/买到/想要/求购）、功能列表（钱包/卡券/AI 客服/评价/自提点/设置） | `GET /api/user/info` |

## 5. 核心组件拆分（`src/components`）

```
AppTabBar.vue            底部 Tab + 中央发布 FAB + 未读角标
GoodsWaterfall.vue       双列瀑布流（IntersectionObserver 懒加载）
GoodsCard.vue            商品卡（图、两行截断标题、价格行、标签、卖家行）
PriceText.vue            价格（accent 色 + tabular-nums + 原价删除线）
ConditionTag.vue         成色/急出/自提标签（brand-chip 底深字）
SellerCard.vue           卖家信用卡（认证标识 ⭐信用分）
PublishForm.vue          发布表单（图片上传 + chips + AI 按钮）
ChatWindow.vue           通用聊天窗（消息流 + 输入栏），AI/私聊复用
ChatGoodsCard.vue        客服消息内的商品卡片（图/名/价/去看看按钮）
QuickQuestions.vue       客服快捷问题 chips
AiStreamText.vue         SSE 流式打字机渲染（Markdown 子集）
CampusBadge.vue          校园认证标识组件
```

## 6. AI 智能客服对接（核心保留功能）

- **接口**：`POST /api/cs/chat`，请求 `{ sessionId, message, goodsId? }`；响应 `text/event-stream`，逐条 `data:` 增量文本，结束帧 `[DONE]`（沿用现有后端契约，前端不感知 RAG 细节）。
- **前端实现要点**：
  1. 用 `fetch` 读取 `response.body` 流，按 `\n\n` 切帧、去 `data: ` 前缀增量追加；`AiStreamText` 打字机渲染，支持中断（AbortController，对应后端取消）。
  2. **上下文携带**：从商品详情点「问 AI 客服」进入时带 `goodsId`（后端已有回填设计，见 `CsPageController`）。
  3. **商品卡片协议**：与后端约定——assistant 文本中出现 ```json fence 且 `type:"goods_card"` 时，前端解析渲染为 `ChatGoodsCard`；解析失败降级为纯文本（安全兜底）。
  4. **限流提示**：后端有会话 10/min + IP 60/min 限流，前端对 429 做 Toast + 倒计时禁用输入。
  5. 快捷问题固定三项：如何发布闲置 / 交易被骗怎么办 / 转人工客服。
- **新增 AI 能力（发布页）**：`POST /api/ai/estimate`（图片+类目 → 估价区间）、`POST /api/ai/title`（图片+关键词 → 标题/描述草稿）。后端走同一 LLM 通道，前端仅调 REST，不做流式。

## 7. 后端接口映射（复用 vs 新增）

| 功能 | 现状 | 处理 |
|---|---|---|
| 登录/注册 | 已有 `/login`（表单+验证码，HttpSession） | **已实现**：`POST /api/user/login`、`/api/user/info`、`/api/user/logout`（JSON，session 鉴权，416 表示未登录） |
| 分类 | 已有三级分类 | **已实现**：`GET /api/goods/categories`（校园分类树 900-906） |
| 商品列表/详情/搜索 | 已有 SSR 页面 | **已实现**：`/api/goods/recommend`、`/api/goods/search`、`/api/goods/byCategory`、`/api/goods/{id}`（VO 见 `CampusGoodsVO`，分→元换算） |
| 发布 | 无 | **已实现**：`POST /api/goods`（标签由成色/交易方式/急出拼装，分类名映射 901-906） |
| 想要（收藏） | 无 | **已实现**：`POST/DELETE /api/goods/{id}/want`、`GET /api/goods/wants/mine`（表 `tb_campus_want`） |
| 购物车 | 已有 | **废弃**——C2C 直买模式，改为「想要（收藏）」 |
| 订单 | 已有 | 一期简化为线下自提确认单；二期接入担保支付 |
| AI 客服 | 已有 `/api/cs/chat` | **直接复用** |
| AI 估价/标题 | 无 | **已实现**：`/api/ai/estimate`、`/api/ai/title` |
| 私聊 IM | 无 | **已实现（一期短轮询）**：`/api/im/sessions`、`/api/im/messages`（GET/POST）、`/api/im/read`；会话 id `c_{小uid}_{大uid}_{goodsId}`（与参数顺序无关）；表 `tb_chat_message`。二期换 WebSocket |
| 商品图 | 无 | **已实现**：无真实图时前端按标题关键词回退 emoji（`utils/goodsImage.js`），避免 404 |

## 8. 状态管理（Pinia stores）

- `useUserStore`：token、用户信息、认证状态；路由守卫消费。
- `useGoodsStore`：瀑布流分页游标、搜索历史（localStorage）。
- `useChatStore`：AI 客服会话（sessionId、消息列表、流式状态）、未读数。

## 9. 非功能要求

### 9.0 模型通道现状（2026-10-15 实测）
- 通道：Agnes AI Hub（`https://api.agnes-ai.cn/v1`），`GET /models` 返回 **10 个模型**：`agnes-2.5-flash / 2.5-pro / 2.5-pro-alpha / 2.5-pro-beta / 3.0-flash / 3.0-pro / agnes-image-2.1-flash / agnes-image-2.5-flash / agnes-video-2.5 / agnes-video-2.5-flash`。
- **不存在「免费模型 / 付费模型」之分**：账号走免费额度，限制是**速率型**——实测 1 秒间隔连发，第 6 次起返回 `429 RateLimit`（提示"已达到免费用户的 API 速率限制"），窗口恢复后同样请求即 200。
- **function calling 验证**（客服依赖工具调用）：`agnes-2.5-flash` 与 `agnes-3.0-flash` 均正确返回 `tool_calls(getGoodsDetail)`；`pro` 系列短 `max_tokens` 下 `content` 为空，不适合流式首字输出。
- **选型结论**：保持 `agnes-2.5-flash`（响应快、首 token 稳、工具调用已验证）；如需更强推理可切 `agnes-3.0-flash`。图像/视频模型与本项目无关。
- **工程含义**：限流窗口内必须依赖既有兜底——后端 4s/8s 线性退避（`cs.agent.max-model-retries`）+ 429 语义；前端 AI 客服有本地演示模式兜底。切勿在前端做高频轮询式调用。

- **性能**：首屏图片懒加载 + WebP；瀑布流虚拟化可选；包体积目标 gzip ≤ 500KB（Vant 按需）。
- **兼容**：iOS Safari ≥ 14、Android Chrome/微信内置浏览器；安全区适配 `env(safe-area-inset-bottom)`。
- **无障碍**：主按钮白字对墨绿对比度 ≥ 4.5:1（#0a5c46 满足）；价格暖金仅用于 ≥12px 粗体。
- **安全**：XSS 过滤（聊天/描述渲染走 v-text 或消毒后的 v-html，沿用后端 XSS 防护约定）；图片上传类型/大小白名单。
- **埋点**（二期）：浏览/想要/发布/客服提问事件。

## 10. 里程碑

1. **M1 骨架**：工程初始化、token/字体接入、TabBar + 路由 + 登录/认证。
2. **M2 交易主链路**：首页瀑布流、分类、详情、搜索、发布（不含 AI）。
3. **M3 AI 能力**：AI 客服（SSE 复用）、AI 估价/标题。
4. **M4 消息与我的**：会话列表、私聊（轮询）、我的/收藏。
5. **M5 联调验收**：真机适配、性能与 XSS 检查、按 mockups 逐屏截图比对。

---

> 变更记录：v1.0（2026-10-15）初版，基于已确认的「方案 A · 伊师大墨绿」设计稿。
> v1.1（2026-10-15）契约对齐实测修正：① 后端实际端口 **28089**（非 28019）；② 客服 SSE 契约以 `cs-core.js` 为准——请求体 `{question, conversationId, goodsId, orderNo}`，帧 `event: stage|rag|tool|delta|review|done|error` + JSON data，**商品卡片来自 tool 事件的 args.goodsId（非模型文本，防幻觉）**，**review 才是终止事件**（audit 下 done 非终止）；③ 后端登录为 HttpSession + 验证码机制，无 token/JWT，`/api/**` 目前仅 cs 与 ai 为 REST，goods/user REST 接口待新建。
