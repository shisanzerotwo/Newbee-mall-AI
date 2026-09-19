# 架构（ARCHITECTURE）

> **读这份文档的时机**：想知道「代码长什么样、数据怎么流」。
> 想知道「**当初为什么这么设计**」看 `DESIGN.md`；想「**动手改**」看 `DEVELOPMENT.md`。
> **本文所有数字都是核实过的**（`wc -l` / `grep` 实测，非估计）。

---

## 1. 一句话架构

**一个 Spring Boot 应用里跑两个域**：商城域（原有）+ 客服域（新增）。
客服域**同进程直调**商城的 Service（不经 HTTP），因此拿得到登录态、商品上下文与实时数据。

```
浏览器
  │
  ├─ 商城页面（Thymeleaf + 商城 CSS 变量）
  │    └─ footer.html 注入的浮窗（cs-widget.js）
  │
  └─ /cs 完整页（cs.html）                ← 两者共用 cs-core.js
        │
        │  POST /api/cs/chat   (text/event-stream)
        ▼
   ┌─────────────────────── Spring Boot 单应用 ───────────────────────┐
   │  CsController ──限流(会话+IP)──► CsStreamService（流式编排）        │
   │                                      │                          │
   │        ┌─────────────────────────────┼──────────────────────┐   │
   │        ▼                             ▼                      ▼   │
   │   RagService                  MallToolInvoker          QaReviewer│
   │  （关键词+向量→RRF）           （6 个只读 @Tool）      （audit/gate）│
   │        │                             │                      │   │
   │        ▼                             ▼                      ▼   │
   │  Redis 向量库               GoodsService/OrderService   第二个模型 │
   │  (redis:8 / 16379)          （同进程直调，非 HTTP）                │
   │                                                                  │
   │  CsChatMemoryService ──异步落库──► MySQL cs_chat_memory            │
   │  CsUsageMeter / CsRateLimiter（内存计数）                          │
   └──────────────────────────────────────────────────────────────────┘
```

---

## 2. 代码地图（行数为实测）

### 2.1 客服编排层 `service/agent/`（共 3 030 行）

| 模块 | 行数 | 职责 | 关键约束 |
|---|---:|---|---|
| `CsStreamService` | **742** | 流式编排：RAG → 流式工具循环 → delta 推送 → 质检 | 工具循环上限 6；**每轮记 `lastRoundMs` 用于区分"上游繁忙"与"模型异常"** |
| `CsAgentService` | **447** | 非流式编排（同一套逻辑的另一入口） | 与流式共用 prompt / 工具 / 质检 |
| `KnowledgeBuilder` | **382** | 建库：MySQL 商品 → **语料红线清洗** → 分块 → 嵌入 → Redis | **不得写入价格/库存/上下架** |
| `RagService` | **285** | 混合检索：关键词 2-gram + 向量 → **RRF 融合** | RRF 身份键必须是**完整 chunk 文本**（用前 50 字符会误合并） |
| `MallTools` | **283** | 6 个只读 `@Tool`（搜索/详情/库存/订单/分类/推荐） | 全部只读；limit 白名单按工具分别定 |
| `CsRateLimiter` | **251** | 令牌桶（每桶一把锁）+ in-flight 去重 + **惰性清理** | 桶数有界（`MAX_BUCKETS` + 强制压回） |
| `CsChatMemoryService` | **187** | 会话记忆读写（登录用 userId / 匿名用 conversationId） | **不用 sessionId**；异步落库不阻塞响应 |
| `QaReviewer` | **171** | 质检（只查硬伤：数据准确性/诚实性/合规性/推荐合理性） | **不改表达风格** |
| `MallToolInvoker` | **121** | 工具分发（switch 一处实现，两个编排共用） | 防"两张表漂移" |
| `CsUsageMeter` | **106** | 用量记账（请求/工具调用/两类被拒） | **刻意不采集 token**（流式下不可靠，恒 0 比不显示更误导） |

### 2.2 接入层 `controller/`（共 715 行）

| 模块 | 行数 | 职责 |
|---|---:|---|
| `CsController` | **308** | `POST /api/cs/chat`（SSE）；**双维度限流**（会话 10/min + IP 60/min） |
| `CsHealthController` | **136** | `GET /api/cs/health`；**默认只回健康必需项**（status/db/redis），内部信息走 `CS_HEALTH_DETAIL` 开关（P2 收敛 R9） |
| `CsSseWriter` | **203** | **SSE 事件的唯一出口**：complete 时机、只 complete 一次、发送失败静默丢弃 |
| `CsPageController` | **68** | `GET /cs`（回填 `goodsId` / `orderNo`） |

### 2.3 配置层 `config/`（共 263 行）

| 模块 | 行数 | 职责 |
|---|---:|---|
| `CsAgentConfig` | **141** | 模型 Bean（`ChatModel` / `StreamingChatModel`）；OkHttp 客户端；**`maxRetries(0)`** |
| `CsRagConfig` | **122** | 嵌入模型（**可切换** minilm 384 / bge-zh 512）+ Redis 向量库 |

### 2.4 前端（共 894 行）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `static/mall/js/cs-core.js` | **422** | **共用核心**：`escapeHtml`（覆盖 `& < > " '`）+ SSE 客户端 + 商品卡片 + 上下文面板；**429 会读响应体抽 message** |
| `static/mall/js/cs-widget.js` | **258** | 浮窗（复用 core，不复制转义/SSE 客户端） |
| `templates/mall/cs.html` | **214** | `/cs` 完整页（三栏：历史 232px + 对话 + 面板 340px） |

---

## 3. 两条产线的数据流

### 3.1 商城（原有，未改语义）

```
浏览器 → Tomcat → Controller(mall/*) → Service → Mapper → MySQL
                                     ↕
                              Redis（首页缓存 / 订单超时 ZSet）
```

### 3.2 客服（新增）

```
① 建立连接
   POST /api/cs/chat
     → CsController：CsUsageMeter.recordRequest()
     → 限流：IP 维度 → 会话维度（两级都过才放行；会话被拒时归还 IP 名额）
     → 建 SseEmitter + CsSseWriter（注册 onTimeout/onError 回调）
     → 交给虚拟线程执行，Servlet 线程立刻返回

② 流式编排（CsStreamService）
   RAG 检索 ──► 发 rag 事件（只带 title/score，不带 chunk 正文）
   工具循环 ──► 每轮：流式调模型
                 ├─ onPartialResponse → 累积文本 → 发 delta
                 ├─ onCompleteResponse → 若有 toolExecutionRequests：
                 │     ├─ 执行工具（MallToolInvoker）→ 发 tool 事件
                 │     └─ 把结果回灌 → 进入下一轮（上限 6）
                 └─ 无工具 → 本轮即最终回答
   质检 ──► audit（默认）：先发 done，review 异步到达
            gate：同步完成，review 必然先于 done

③ 收尾（CsSseWriter 统一把关）
   收到 review → complete（**只 complete 一次**）
   兜底：3s 内质检未返回 → 发 review{qualified:null,reason:"质检超时"} → complete
   客户端断开 / 超时 → markClosed，后续 send 静默丢弃

④ 旁路
   会话记忆：每轮结束**异步**落库（不阻塞）
   用量记账：CsUsageMeter 每 N 个请求打一条汇总
```

### 3.3 ⭐ SSE 事件协议（客户端必须按此实现）

| 事件 | 载荷 | 时机 |
|---|---|---|
| `stage` | `{stage, elapsed}` | 阶段开始（检索知识库 / 生成回答） |
| `rag` | `{sources:[{title, score, goodsId}]}` | RAG 命中（**不含 chunk 正文**） |
| `tool` | `{name, args, ms, ok}` | 每次工具调用 |
| `delta` | `{text}` | 逐字流式文本 |
| `done` | `{totalMs, firstTokenMs}` | 本轮结束 —— ⚠️ **不是终止事件** |
| `review` | `{qualified, reason}` | 质检结论 —— **这才是终止条件** |
| `error` | `{message}` | 失败（可读中文） |

**限流拒绝**走 **HTTP 429**（不是 200），响应体仍是 `event:error` 帧（让按事件流解析的客户端也能读到原因）。

---

## 4. 与设计的偏差（实现期推翻或修正的部分）

> 只列**实际与 `DESIGN.md` 不一致**的地方。设计原文未改，差异在此留痕。

| # | 设计原文 | 实际实现 | 原因 |
|---|---|---|---|
| 1 | 质检默认 `gate`（对齐 Python 基准） | **默认 `audit`** | gate 同步阻塞拉长响应；audit 更适配 SSE |
| 2 | 工具循环 `max-iterations = 6` | 6（已对齐） | — |
| 3 | 「LangChain4j 无官方 Boot starter」为错误断言（v1.1 已订正） | 未引入 starter（仍手写编排） | starter 只有 beta；core/open-ai 是 GA |
| 4 | （未提及）模型重试策略 | **自实现线性退避 4s×n** + `maxRetries(0)` 关闭内置 | 内置指数退避不适配上游 3s 重置窗口；且会与自实现叠加 |
| 5 | （未提及）HTTP 客户端 | **必须 OkHttp** | 默认 JDK HttpClient 调 OmniRoute 报 `header parser received no bytes` |
| 6 | （未提及）限流维度 | **双维度**（会话 + IP） | 单维度可被"轮换 conversationId"绕过（实测修复前 70/70 全通过） |
| 7 | （未提及）引用来源 | SSE 新增 **`rag` 事件**（只带 title/score） | 上下文面板需要；**不带 chunk 正文**避免语料泄漏到前端 |
| 8 | 嵌入模型 | 默认 **bge-small-zh-v15**（512 维，可切换 minilm） | 语义查询命中率 20% → 40%（`PERF-M3.md`） |
| 9 | 质检的 token 用量 | **不采集** | 流式下拿不到可靠 usage；对外暴露恒 0 比不显示更误导 |
| 10 | 虚拟线程 | 已启用，但**实测无收益** | 并行度仅 7~9，瓶颈不在线程（`PERF-M3.md`） |

---

## 5. 扩展点（加东西该动哪）

| 要加什么 | 动哪 | 别忘了 |
|---|---|---|
| **新工具** | `MallTools` 加方法 → `MallToolInvoker` 的 switch 登记 | 两张表必须同步（否则 `ok` 判定漂） |
| **新 SSE 事件** | `CsSseWriter` 加发送方法 → `cs-core.js` 解析 → `DESIGN §4.2` 协议表 | 加端到端测试断言事件顺序 |
| **换嵌入模型** | `CS_RAG_EMBEDDING_MODEL` + **重建索引**（维度会变） | 判据看 `hash_indexing_failures=0` |
| **调限流** | `cs.limit.*`（会话/IP/在途/正文长度） | 验证要**并发快发**（串行会因令牌补充而看不出效果） |
| **加会话维度** | `CsChatMemoryService` | **不要用 sessionId** |
| **改质检口径** | `QaReviewer` | 只查硬伤，不挑风格 |

---

## 6. 关键不变量（破坏了会出事）

1. **SSE 的 `complete()` 只能由 `CsSseWriter` 调，且至多一次** —— 绕开它直接 `emitter.send/complete` 会踩 `done` 后 review 到达的坑
2. **`done` 不是终止事件**（audit 模式下 review 后到）
3. **价格/库存/订单状态只来自工具结果** —— 语料红线从结构上保证（`KnowledgeBuilder` 不写这几个字段）
4. **模型文本一律经 `escapeHtml`**（覆盖 `& < > " '` 五个字符，单引号也要转）
5. **商品卡片数据来自 `tool` 事件**，不从 LLM 文本解析
6. **限流键不得只用 conversationId**（客户端可控 → 可绕过）
7. **全局异常处理器必须对 SSE 短路**（否则它按 `application/json` 返回 `Result`，但响应头已是 `event-stream` → 写不出去 → 二次异常）

---

## 7. 部署形态（实测确认）

| 容器 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| `newbee-mall-mysql` | `mysql:9.7` | 仅内网 | **不映射宿主**（避免与本机 3306 冲突） |
| `newbee-mall-redis` | **`redis:8`** | 宿主 **16379** | **必须 8**（向量检索要 `FT.CREATE ... VECTOR`）；映射 16379 避开本机 3.0.504 的 6379 |
| `newbee-mall-app` | 本地构建 | 宿主 `${APP_PORT:-28089}` | 多阶段构建 + fontconfig（验证码要 AWT 字体） |

**本机开发时的两种跑法**：
- 容器：`docker compose up -d`（三个 healthy）
- 本地进程：`bash ops/mvn.sh spring-boot:run`（改代码用这个，热重启快）

> 应用**必须能同时拿到 MySQL 与 redis:8**；本机 6379 的 Redis 3.0.504 **不支持向量**，连错会在建库时直接报 `unknown command 'FT.CREATE'`。
