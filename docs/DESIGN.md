# newbee-mall-ai 设计规格（DESIGN.md）

> 版本：**v1.1** · 日期：2026-09-17（v1.0 同日，经技术评审后修订）
> 主题：把 **newbee-mall（Java 电商）** 与 **ai开发 的多 Agent 客服（Python）** 融合为**一个新项目**
> 状态：**已 review，v1.1 已并入 10 条评审意见**，可转入实现计划（`DESIGN` → `PLAN`）
> 目标落点：`D:\GitHub\xiangmu\newbee-mall-ai`（本文档届时移入该项目 `docs/`）
> 修订记录、被推翻的断言与证据来源：见 **§14**

---

## 0. 一句话目标

把「**Java 电商**」与「**Python 智能客服**」从两个割裂的仓库/服务，重构为**一个 Spring Boot 应用**：
客服不再是 iframe 里的外部网站，而是商城内的**原生能力**；模型编排、RAG、工具调用全部用 Java 重写，与商城**同进程、同栈、同前端**。

---

## 1. 现状（实测事实，非推断）

### 1.1 项目 A：`D:/GitHub/xiangmu/newbee-mall`

| 项 | 实测值 |
|---|---|
| 框架 | Spring Boot **2.7.5**，`java.version=**1.8**` |
| 依赖 | **8 个直接依赖**：`starter-thymeleaf` / `starter-web` / `spring-session-core` / `starter-data-redis` / `mybatis-spring-boot-starter **2.2.2**` / `hutool-captcha **5.8.7**` / `mysql:mysql-connector-java`(runtime) / `starter-test`(test)；**无 Lombok、无 Spring Security、无 JPA、无 PageHelper**（分页为自研 `PageQueryUtil`） |
| 构建插件 | **显式声明 `maven-compiler-plugin 3.11.0` 且硬写 `source/target=1.8`**（`pom.xml:76-85`）→ 升级时必须处理，见 §6.1 |
| 会话实现 | `spring-session-core` 是**死依赖**：全仓零 `org.springframework.session` 引用、无 `@EnableRedisHttpSession`，session 实为 Tomcat 内存态 |
| 端口 | 28089 |
| git | 当前分支 `测试`；`origin`=shisanzerotwo/newbee-mall；`upstream`=newbee-ltd/newbee-mall |
| 已提交的整合 | `AgentApiController`（`/api/agent` 三个只读接口 + `X-Agent-Key` 常量时间比较 + limit 白名单） |
| **未提交** | `AgentCsModelAdvice.java`、`cs-widget.js`、`cs-widget.css`、`themes.css`（当日新增）；另 9 个文件为真实内容改动（`header.html`/`footer.html`/`application.properties`/`common.css`/`header.css`/`cart.html`/`login.html`/`register.html`/`DESIGN.md`） |
| 工作区噪声 | 另有 **310 个文件被标 M 但属纯行尾差异**（`LICENSE` 674/674 全行变化）→ **不是真实改动** |
| 容器化 | **无** Dockerfile / docker-compose |
| 客服入口（已做） | header「智能客服」链接、footer「在线咨询」、右下角浮窗（FAB「蜂」→ iframe 打开 `http://127.0.0.1:8001/?embed=1&goods=<商品名>`） |

### 1.2 项目 B：`D:/GitHub/xiangmu/ai开发`

| 项 | 实测值 |
|---|---|
| 客服位置 | `12_agent_cs/`（FastAPI :8001，轮询取答 + 10min 缓存 + 质检复核） |
| RAG | ChromaDB（ONNX MiniLM 384 维）+ 关键词 2-gram + RRF 融合 |
| 知识库规模 | **575 商品 / 609 片段**（`data/cs_kb.json` 408K，`cs_chroma/` 3.2M） |
| git | **无 remote**，本地 15 commit |
| 容器化 | `Dockerfile` 仅打包 `06/08/09`，**不含 `12_agent_cs`** |
| 安全隐患 | MySQL root 明文密码硬编码于 `db_tools.py` / `kb_build.py` / `hard_tests.py` |
| **模型通道** | `.env` 实测指向 **Agnes AI Hub**（`apihub.agnes-ai.com/v1`）+ `agnes-2.0-flash`；**全仓（排除 venv）零 OmniRoute 引用**；`.env.example` 仍写 DeepSeek 官方端点 —— 三处不一致 |
| **质检模式** | `QA_MODE` 默认 **`gate`**，且质检 LLM 调用**同步阻塞**在回答返回之前（`cs_agent.py:162,216-223`）；`audit` 仅表示"不打回重答"，**并非旁路执行** |
| **语料实况** | 609 片段中 **575 段正文写死「价格：X 元 / 库存：N 件」**（模板 `kb_build.py:70-76`），注入 prompt 时截断 200 字符恰好覆盖这两行；快照建于 8/13 |
| **⚠️ Python 版存在上下架判断反转的 bug（M2-2 发现）** | `db_tools.py:103/121` 写 `"在售" if goods_sell_status == 1`，但商城权威定义是 **`Constants.SELL_STATUS_UP = 0`**（注释：“搜索和详情页面都只展示 SELL_STATUS_UP 的商品”；购买链路 `where ... and goods_sell_status = 0`）。数据库实际分布：**`0` → 573（在售）；`1` → 2（下架）**。即 Python 版把 573 个在售标成“已下架”、2 个下架标成“在售”，其**商品状态类回答系统性错误**。<br>**据此确立基准原则：凡冲突，以商城源码为准，不以 Python 版为行为基准。** Python 版仅作为“实现思路参照”，不作为“正确性参照” |
| 客服侧记忆 | **无**：`cs_agent.py:185` 每次提问重建 messages；`10_memory_rag/memory.py` 的 `(user_id,key)` KV 表从未被客服使用 |

### 1.3 已发现的关键缺口

1. **上下文断链（最严重）**：Java 侧已传 `?embed=1&goods=<商品名>`，但 **Python 客服页与 `cs_server.py` 完全没有处理这两个参数**（grep 零命中）→ 嵌入模式从未生效，用户在商品页问"这个多少钱"客服不知道"这个"是谁。
2. **前端割裂的真正根因**：两边**配色其实已同源**（同一套 `--brand-*` token），真正的问题是**结构错配**——客服页按 1280px 三栏整页设计（含**自己的顶部导航头**），却被塞进 **380×560 的 iframe**，形成"双导航头 + 三栏挤爆"。
3. **跨栈契约只覆盖商品**：订单/分类工具仍是 Python **直连 MySQL**（绕过 Java）。
4. **无统一启动**：Java 无容器化，Python compose 不含客服 → 无法一键起。

---

## 2. 设计决策总表（41 项）

| # | 决策项 | 结论 |
|---|---|---|
| 1 | 项目形态 | 新建统一仓库 `D:\GitHub\xiangmu\newbee-mall-ai`（旧仓库原样保留） |
| 2 | 客服重构 | **Java 重写**，与商城**同应用融合**（单进程） |
| 3 | 语言 / 框架 | **Java 21** + Spring Boot **3.5.x** |
| 4 | AI 编排 | **LangChain4j + 官方 `langchain4j-spring-boot-starter`**（Boot 3.5+ / Java 17，`@AiService` 为官方能力） |
| 5 | 向量库 | **Redis 8**（内置 Query Engine / HNSW / JSON）+ `langchain4j-community-redis` |
| 6 | 嵌入模型 | 阶段1 内置 ONNX `all-minilm-l6-v2` → 阶段2 `bge-small-zh-v1.5`（出对比报告） |
| 7 | 前端 | Thymeleaf 原生双形态（浮窗 + `/cs`）+ SSE 流式，**无 iframe** |
| 8 | 部署 | Docker Compose 3 容器（mysql / **redis:8** / mall-backend） |
| 9 | 虚拟线程 | 容器内（Java 21）压测后**再决定是否开启**；本机 JDK 25 的数据不适用于该结论 |
| 10 | JDK | 本机 JDK 25 编译 `--release 21`；容器运行 `temurin:21-jre` |
| 11 | Hutool | 升级到 **≥ 5.8.40** + 验证码回归验证（**含容器内字体**，见 §6.3） |
| 12 | 能力边界 | 阶段1 只读；阶段2 打通登录态查「我的订单」（含越权校验） |
| 13 | RAG 生命周期 | 应用启动**异步**构建 + 每日 03:00 **全量重建**（实现方式见 §7.1；不做"增量"） |
| 14 | 性能目标 | 首字 <1.5s、完整回答 <8s、并发 10；产出 Java vs Python 对比表 |
| 15 | 商城功能 | **全保留**（前台全部 + 后台全部） |
| 16 | 会话记忆 | 落库 MySQL（LangChain4j ChatMemory + JDBC） |
| 17 | 质检 | 默认 **`gate`**（对齐 Python 基准：同步把关、不合格重答一次）；`cs.qa.mode=audit` 可切为旁路记录 |
| 18 | `/api/agent` | **删除**（需要时从 git 历史取回），内部工具改直调 Service |
| 19 | 前端细节 | 浮窗 380×560 + 3 条 chips；`/cs` 保留三栏与上下文面板六区块 |
| 20 | 初始化数据 | 从现有库 `mysqldump` → `ops/init.sql`（含 575 商品） |
| 21 | 模型通道 | **以 M2 spike 实测结果为准**（候选：当前在用的 Agnes AI Hub 直连 / OmniRoute 网关）；**function calling 实测通过**才开写编排层；保留多 provider 降级 |
| 22 | 验收标准 | 演示清单 + 单测 + Testcontainers 集成测 + CI + 密钥环境变量化 |
| 23 | 里程碑 | M1 升级跑通 → M2 客服核心 → M3 前端融合 + 工程化 |
| 24 | Python 旧版 | `ai开发/12_agent_cs` 原地不动，作为行为对照基准 |
| 25 | 越权防护 | 阶段2 订单查询必须校验「订单归属 == 当前登录用户」 |
| 26 | 容器运行时 | `eclipse-temurin:21-jre` + **`fontconfig`/字体**（验证码需要 AWT），**压测在容器内做** |
| 27 | 升级基线 | Boot 3.5 升级在**新仓库**进行，旧仓库不动 |
| 28 | 升级参考 | `upstream/spring-boot-3.x` 仅供参考，**不 merge**（差异 128 文件 / -13526 行） |
| 29 | 迁移面 | `javax` → `jakarta` 共 **44 处**（20 `HttpServletRequest` + 13 `@Resource` + 7 `HttpSession` + 4 `HttpServletResponse`）；`javax.imageio.ImageIO` 1 处**不动**。注：最初估 46 处，Task 3 删除 `AgentApiController` 连带移除 2 处 import（已实测核实） |
| 30 | 配置迁移 | `spring.redis.*` → `spring.data.redis.*` |
| 31 | 质检时序 | 默认 `gate` 为**同步**（`review` 先于 `done`）；仅 `audit` 模式异步，此时 **`done` 不是终止事件**，时序约定见 §4.2 |
| 32 | SSE 传输方式 | `POST` + `fetch` + `ReadableStream`（非 `EventSource`，以支持 POST 传参与上下文） |
| 33 | 工具层 | `MallTools` 的 6 个 `@Tool` 方法**直调 Service/Mapper**（无 HTTP、无 JDBC 直连） |
| 34 | 混合检索 | 向量 + 关键词 2-gram → RRF 融合（对齐 Python 版，便于对照） |
| 35 | 增强 UI | 拖拽 / 手机端底部抽屉 / 消息重生成 → **M3 末尾可选**，不阻塞主体交付 |
| 36 | 数据脱敏 | `ops/init.sql` 保留：**全部表结构** + **管理员**（`tb_newbee_mall_admin_user`）+ 商品/分类/轮播/首页配置数据。**用户 / 订单 / 购物车表仅留结构**。⚠️ **不含样例订单**：订单行含收货人姓名/电话/地址，属隐私，不随仓库分发；因此容器内后台可登录，但“我的订单”类演示需自行注册下单 |
| 37 | 行尾策略 | 新仓库统一 **LF** + `.gitattributes`（避免重演旧仓库 310 文件行尾噪声） |
| 38 | 客服后台 | 本次**不做** `/admin/cs/**`（记为 M4 候选） |
| 39 | **语料红线** | 建库 chunk 文本**不含价格 / 库存 / 上下架状态**（此类数据一律由工具提供），见 §7.1 |
| 40 | **编排滥用防护** | 工具循环 `max-iterations=6`；单会话同时 1 个在途请求；SSE 单连接上限 120s；按 session 限流（见 §7.2） |
| 41 | **前端输出编码** | LLM 流式文本与工具返回一律经转义函数渲染，**禁止裸 `innerHTML` 拼接模型输出**（见 §8.4） |

---

## 3. 目标架构

### 3.1 逻辑架构（应用内双域）

```
                    ┌──────────────────────────────────────────┐
                    │      newbee-mall-ai（单进程/单部署）      │
                    │                                          │
   浏览器 ──────────┤  ┌─ 商城域 ──────────────────────────┐   │
   （同一站点）      │  │ controller/mall  · service · dao  │   │
                    │  │ Thymeleaf 模板 · 静态资源          │   │
                    │  └───────────┬──────────────────────┘   │
                    │              │ 直调 Service/Mapper       │
                    │  ┌─ 客服域 ──▼──────────────────────┐   │
                    │  │ controller/agent                  │   │
                    │  │   CsPageController   GET /cs      │   │
                    │  │   CsSseController    POST /api/cs/chat
                    │  │ service/agent                     │   │
                    │  │   CsAgentService  客服角色（工具循环）│  │
                    │  │   QaReviewer      质检角色（异步）   │  │
                    │  │   MallTools       6 个 @Tool       │   │
                    │  │   RagService      混合检索          │   │
                    │  └───────────┬──────────────────────┘   │
                    └──────────────┼───────────────────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                    ▼
        MySQL 9.7            Redis Stack           LLM 网关
   （商城数据 + 记忆表）  （缓存/ZSet/向量索引）  （Agnes/OmniRoute/备用 key）
```

### 3.2 代码架构（目录）

```
D:\GitHub\xiangmu\newbee-mall-ai\
├── README.md                       # 一图看懂 + 一键启动 + 演示脚本
├── docker-compose.yml              # mysql + redis:8 + mall-backend
├── .env.example                    # 配置模板（真实 .env 不入库）
├── .gitattributes                  # 统一 LF（决策 #37）
├── ops/
│   ├── init.sql                    # mysqldump 导出（脱敏）
│   └── smoke.md                    # 冒烟回归清单
├── docs/
│   ├── DESIGN.md                   # 本设计
│   ├── PLAN.md                     # 实现计划
│   ├── ARCHITECTURE.md             # 架构说明（对外）
│   ├── UPGRADE-BOOT3.md            # Java 8→21 / Boot 2.7→3.5 实战记录
│   ├── RAG-EVAL.md                 # 英文嵌入 vs 中文嵌入实测对比
│   └── DEMO.md                     # 3 分钟演示脚本
└── mall-backend/
    ├── pom.xml
    └── src/main/
        ├── java/ltd/newbee/mall/
        │   ├── controller/
        │   │   ├── mall/           # 商城原有
        │   │   ├── admin/          # 后台原有
        │   │   └── agent/          # ★ 客服入口
        │   │       ├── CsPageController.java    # GET /cs
        │   │       └── CsSseController.java     # POST /api/cs/chat（SSE）
        │   ├── service/
        │   │   ├── ...             # 商城原有（客服工具直调这些）
        │   │   └── agent/
        │   │       ├── CsAgentService.java      # 客服编排
        │   │       ├── QaReviewer.java          # 质检
        │   │       ├── MallTools.java           # 6 个 @Tool
        │   │       ├── RagService.java          # 混合检索
        │   │       └── KnowledgeBuilder.java    # 启动/定时建库
        │   ├── config/
        │   │   └── CsAgentConfig.java           # LangChain4j 装配（官方 starter 之外的定制 Bean）
        │   └── dao/ · entity/ · util/           # 原有
        └── resources/
            ├── application.yml                 # 配置（Boot 3 格式）
            ├── db/migration/                   # 记忆表 DDL
            ├── templates/
            │   ├── mall/                       # 商城原有
            │   ├── mall/cs.html                # ★ /cs 完整页
            │   └── fragments/cs-widget.html    # ★ 浮窗（全站引入）
            └── static/mall/
                ├── styles/cs-widget.css        # 改写（不再 iframe）
                └── js/cs-widget.js             # 改写（fetch + SSE 渲染）
```

### 3.3 模块职责与边界

| 模块 | 职责 | 依赖 | 可独立测试 |
|---|---|---|---|
| `CsSseController` | HTTP 入口：参数校验、SSE 推流、异常兜底 | `CsAgentService` | ✅ MockMvc |
| `CsAgentService` | 编排：RAG → 工具循环 → 回答流 → 触发异步质检 | `RagService`、`MallTools`、`ChatModel` | ✅ Mock LLM |
| `QaReviewer` | 质检：数字核对 / 诚实性 / 合规，输出合格与否 | `ChatModel` | ✅ Mock LLM |
| `MallTools` | 6 个只读工具，直调商城 Service | `GoodsService`、`OrderService` | ✅ 纯方法测试 |
| `RagService` | 混合检索（向量 + 关键词 → RRF） | `EmbeddingStore`、`EmbeddingModel` | ✅ 已知向量断言 |
| `KnowledgeBuilder` | 建库/重建（启动钩子 + `@Scheduled`） | `GoodsService`、`RedisEmbeddingStore` | ✅ 计数断言 |
| 前端浮窗 / `/cs` | 呈现与交互，不含业务判断 | SSE 接口 | 手工 + 探针截图 |

---

## 4. 客服问答数据流

### 4.1 主链路

```
用户（浮窗 / /cs 页）
  │  POST /api/cs/chat  {question, goodsId?, orderNo?}   ← fetch + ReadableStream
  ▼
CsSseController
  ▼
CsAgentService
  ├─ ① RagService.retrieve(question)
  │     ├─ 关键词：2-gram 命中率
  │     ├─ 向量：Redis HNSW topN
  │     └─ RRF 融合 → Top3 片段（作为语义参考，不作数据依据）
  ├─ ② 工具循环（LLM 决定调哪个 @Tool）
  │     MallTools.searchGoods / getGoodsDetail / checkStock /
  │             queryOrder / searchByCategory / recommendGoods
  │       └→ 直调 GoodsService / OrderService（同进程）
  ├─ ③ 组织回答 → TokenStream → SSE delta 逐字推送
  └─ ④ 质检（**默认 gate：同步把关**，不合格追加反馈后重答一次）
        QaReviewer 复核数字/诚实/合规 → SSE review 事件
        （cs.qa.mode=audit 时改为异步旁路，此时 review 在 done 之后到达，见 §4.2）

【数据铁律】价格 / 库存 / 订单状态一律以工具结果为准；RAG 仅作语义参考。
          结构性保障是 §7.1 的**语料红线**（chunk 文本不含价格/库存），不是靠 prompt 单点兜底。
```

### 4.2 SSE 事件协议

```
event: stage    data: {"stage":"检索知识库","elapsed":0.31}
event: tool     data: {"name":"check_stock","args":{"goodsId":10003},"ms":42,"ok":true}
event: delta    data: {"text":"「无印良品高保湿化妆水」"}
event: review   data: {"qualified":true,"reason":"价格与工具结果一致"}   ← gate：在 done 之前；audit：在 done 之后
event: done     data: {"totalMs":4210,"firstTokenMs":1180}
event: error    data: {"message":"模型调用失败，请稍后再试"}
```

**时序约定（必须实现，否则必踩坑）**

- `gate` 模式（默认）：质检在服务端同步完成，**`review` 必然先于 `done`**。
- `audit` 模式：`done` 先发，`review` 后到 —— 因此 **`done` 不是终止事件**，服务端**不得**在发完 `done` 后立即 `SseEmitter.complete()`，否则异步 `review` 到达时 `send()` 会抛 `IllegalStateException`。
- 终止条件：收到 `review` 即结束；**兜底超时 3s**（质检未按时返回时发一条 `review{qualified:null,reason:"质检超时"}` 并 complete）。客户端只在 `review` 或连接关闭后停止读取。

### 4.3 上下文带入（修掉当前断链）

| 入口 | URL | 效果 |
|---|---|---|
| 商品详情页「问客服」 | `/cs?goodsId=10003` | 首屏提示「正在咨询：无印良品高保湿化妆水」，提问"这个多少钱"能正确解析 |
| 订单详情页「问客服」 | `/cs?orderNo=2024…` | 自动带入订单号（阶段1）；阶段2 改为按登录用户自动识别 |
| 全站浮窗 | `data-goods-id` 传入 | 浮窗头部显示当前咨询商品 |

---

## 5. 技术栈与版本矩阵

| 层 | 选型 | 关键约束（已核实） |
|---|---|---|
| 运行时 | **Java 21** | 本机用 JDK 25 编译 `--release 21`；容器 `eclipse-temurin:21-jre` |
| 框架 | **Spring Boot 3.5.x** | 官方支持 Java **17~25** |
| AI 编排 | **LangChain4j** + 官方 **`langchain4j-spring-boot-starter`** | 最低 JDK 17；starter 需 **Boot 3.5+**；`@AiService` 为官方能力。⚠️ starter 版本线是 `1.x.y-betaN`（与核心库版本线不同步）；**`1.19.1` / `1.19.1-beta29` 是误发布版本（实际含 1.20 之后的提交），不可用**，取 `1.19.2-beta29` 或 `1.20.0-beta30`。<br>⚠️ **M2-1 实际决定（2026-09-18）：暂不引入 starter** —— 因为 `langchain4j` / `langchain4j-open-ai` 已有 GA（1.20.0）而 starter / embeddings / community-redis 只有 beta（1.20.0-beta30）。M2-1 改用**稳定坐标 + 自写 `CsAgentConfig`**，避免 beta 自动配置的黑盒。后续若 starter 进入 GA（或需要 `@AiService` 的声明式能力），改用它的成本 = 删自写配置 + 补 starter 依赖（很小）。RAG 相关 beta 组件（embeddings / community-redis）在 **M2-3** 再评估 |
| 嵌入（阶段1） | `langchain4j-embeddings-all-minilm-l6-v2` | **内置 ONNX，零外部文件** |
| 嵌入（阶段2） | `bge-small-zh-v1.5` | 需自备 `model.onnx` + `tokenizer.json` |
| 向量库 | `langchain4j-community-redis` + **Redis 8** | `langchain4j-redis` 自 1.0.0-beta1 迁至 community（旧坐标停在 0.36.2）；需 Query Engine（`FT.*`）。底层客户端是 **Jedis**，与 Boot BOM 存在版本冲突风险 → 见 §6.4 |
| 数据库 | MySQL 9.7（沿用） | 新增 `cs_chat_memory` 表 |
| 缓存/队列 | Redis（**容器内 `redis:8`**） | 本机 Redis 3.0.504 无 Query Engine，必须走容器。**注意**：Redis Stack 6.2/7.2/7.4 已于 2025-12 停更，Redis 8 已内置 Query Engine / JSON；`REDIS_ARGS` 在 `redis:8` 上不生效（改用 `command`），RedisInsight 已是独立镜像（5540） |
| 前端 | Thymeleaf（商城原生）+ 原生 JS | 无 iframe、无独立 SPA |
| 测试 | JUnit5 + Mockito + **Testcontainers** | Mock LLM 测编排；容器测集成 |
| 部署 | Docker Compose | 需先安装 Docker Desktop（**本机当前未安装**） |

**不采用**：Spring AI、pgvector、Chroma、MySQL VECTOR（社区版无向量索引，仅 HeatWave 支持）。

---

## 6. 升级方案（M1）

### 6.1 迁移清单（基于实测）

| 项 | 现值 | 目标 | 改动量 |
|---|---|---|---|
| `spring-boot-starter-parent` | 2.7.5 | 3.5.x | 1 行 |
| `java.version` | `1.8` | `21` | 1 行 |
| `javax.servlet.http.HttpServletRequest` | 21 处 | `jakarta.…` | 机械替换 |
| `javax.annotation.Resource` | 14 处 | `jakarta.…` | 机械替换 |
| `javax.servlet.http.HttpSession` | 7 处 | `jakarta.…` | 机械替换 |
| `javax.servlet.http.HttpServletResponse` | 4 处 | `jakarta.…` | 机械替换 |
| `javax.imageio.ImageIO` | 1 处 | **不动**（`java.desktop`） | 0 |
| `mybatis-spring-boot-starter` | 2.2.2 | 3.0.x | 版本号 |
| `hutool-captcha` | 5.8.7 | **≥ 5.8.40** | 版本号 |
| `mysql-connector-java` | Boot 管理 | `com.mysql:mysql-connector-j` | 坐标改名 |
| **`maven-compiler-plugin` 的显式配置** | `source/target=1.8`（`pom.xml:76-85`） | **删除该 `<configuration>` 块**，改由父 POM 的 `maven.compiler.release=21` 决定 | 删 6 行 |
| **`spring-session-core`** | 死依赖（零引用） | **删除**。⚠️ 不要替换为 `spring-session-data-redis`：一旦上 classpath，Boot 的会话自动装配会接管存储，session 从 Tomcat 内存搬到 Redis（属**行为变更**，且每次请求的购物车回写会变成一次 Redis 写） | 删 1 条依赖 |
| `spring.data.redis.*` 之外的会话持久化 | 无 | 保持无。若确实需要匿名会话跨重启保留，见 §7.4 的载体决策 | 0 |
| **尾斜杠匹配** | Spring 5 默认模糊匹配 | Spring 6 **默认关闭**（`setUseTrailingSlashMatch` 默认 false） | 见 §6.3 |
| `spring.redis.*` | 3 个键 | `spring.data.redis.*` | 键改名 |
| 3 个拦截器 + `WebMvcConfigurer` | javax | jakarta | 随 import 解决 |
| 10 个 MyBatis XML | — | **不改** | 0 |

### 6.2 步骤（每步独立提交，可回退）

| 步 | 动作 | 验证 |
|---|---|---|
| 1 | 新建仓库 + 拷入源码（排除 `target/`、`.idea/`、`docs/obsidian/`）+ **基线提交** | 目录/文件数比对 |
| 2 | pom 升级（Boot 3.5.x / java 21 / 依赖改名 / **删 `maven-compiler-plugin` 显式 source-target**） | `mvn compile` + `javap -v` 确认 class 版本 **65**（Java 21），不是 52 |
| 3 | `javax` → `jakarta`（44 处）+ 配置键迁移 | `mvn compile` |
| 4 | **冒烟回归**（前台 12 页 + 后台 8 页 + 缓存 + 订单超时） | `ops/smoke.md` 逐条 |
| 5 | `.gitattributes`（LF）+ 清理行尾噪声 | `git status` 干净 |
| 6 | 写 `docs/UPGRADE-BOOT3.md` | 文档产出 |

> **步 1 的拷贝源**：`newbee-mall` 工作区里 `AgentCsModelAdvice.java`、`cs-widget.js/css`、`themes.css` 及 9 个内容改动**均未提交**（见 §1.1）。若从 `git clone` 取源码，这些文件会全部丢失且 header 的客服入口直接渲染失败 —— 必须从**工作目录**拷贝，或先在旧仓库提交一次。
>
> 步 2/3 之后另有一道 **M2 前置：依赖预检**（`mvn dependency:tree`，专查 Jedis / OkHttp / Jackson），见 **§6.4**。

### 6.3 已知坑与缓解

| 坑 | 现象 | 缓解 |
|---|---|---|
| **Hutool + JDK 17/21** | issue #3985（`JSONUtil` 不支持 `record`）报告于 JDK 21 + Hutool **5.8.33**，**已于 5.8.40 修复**；5.8 最新为 5.8.47 | 升到 **≥ 5.8.40**（顺带拿到 5.8.41 的 `ReflectUtil` 反射缓存泄漏修复）；5.8.40 之前不要用 `JSONUtil` 处理 `record` |
| **WSL → Windows 环境变量不传递（M1 实测新增）** | WSL 的环境变量**不会**自动传给 Windows 子进程。实测 `DB_PASSWORD=x powershell.exe -Command '$env:DB_PASSWORD'` → `[]`（空）；加 `WSLENV=DB_PASSWORD/w` 后才得到 `[test123]`。症状极具迷惑性：应用**能启动、页面返回 200**，但连不上库、缓存永不写入（异常被吞掉） | 需要跨边界传的环境变量一律写进 `WSLENV`（`/w` = 仅 Windows）；已固化在 `ops/mvn.sh`。**不要**靠命令行插值传密钥（既有注入面，又可能被 shell 二次解析） |
| **验证码在容器里无字体（新增，必现）** | Hutool `ShearCaptcha` 走 AWT / `BufferedImage` / `Font`（`CommonController.java:46-76`）。`eclipse-temurin:21-jre` 常因缺 `fontconfig` 与字体文件报 `Fontconfig head is null` 或 `Could not initialize class sun.font.SunFontManager` | 镜像内装 `fontconfig` + `fonts-dejavu-core` 并 `fc-cache`；**`-Djava.awt.headless=true` 不能替代**；冒烟清单加一条「容器内取一张验证码图，肉眼确认可辨认」 |
| **虚拟线程 pinning** | Java 21 上 `synchronized` 会 pin 载体线程；**JEP 491 在 Java 24 才修**（修的是 `synchronized` 这一类，native frame / 类初始化引起的 pinning 仍在）。已知实例：**MySQL Connector/J 到 9.0.0 才把 `synchronized` 换成 `ReentrantLock`**（bug #109346/#110512）；**HikariCP issue #2293**：Java 21 下池初始化被 pin 死锁 | 压测决定是否开启，**容器内测**（JDK 21 可用 `-Djdk.tracePinnedThreads=short`，该属性在 JDK 24 被移除；JFR `jdk.VirtualThreadPinned` 跨版本可用）。注意**连接池（`maximum-pool-size=15`）才是并发真瓶颈**，别把 pinning 当成唯一结论 |
| **Boot 跨 3 个大版本** | MyBatis/Thymeleaf/Session 配置与 API 变化；**Spring 6 尾斜杠匹配默认关闭**（`setUseTrailingSlashMatch` 默认 false） | 步 2/3/4 分离；`upstream/spring-boot-3.x` 作参考。尾斜杠：实测确有依赖模糊匹配的 `{var}` 路由（`/goods/detail/{goodsId}`、`/orders/{orderNo}`、`/shop-cart/{id}`、admin 若干），带尾斜杠的旧链接会 404；**`spring.mvc.pathmatch.matching-strategy` 不是这个开关**（那是 PathPattern/Ant 选择器），正确的开关是 `configurePathMatch(...setUseTrailingSlashMatch(true))`（已弃用）或显式声明双路由 |
| **Thymeleaf 版本** | Boot 3 带 `thymeleaf-spring6`（Thymeleaf **3.1**） | 实测 34 个模板中 `#request`/`#response`/`#session`/`#servletContext` **零命中**，此项风险不存在（`${session.xxx}` 是普通变量，仍可用）。但 3.1 另有一条：**表达式中禁止对 `java.*`/`javax.*`/`jakarta.*` 核心类做静态引用或构造**，`th:include` 亦已弃用 → 新增模板时留意 |
| **本机无 JDK 21** | 只有 corretto-23 / openjdk-25 | 用 25 编译 `--release 21` + `maven.compiler.release=21`（前提：删掉 pom 里显式的 source/target=1.8） |

### 6.4 M2 前置：依赖预检（别等装配失败）

LangChain4j 的 Redis 模块底层用 **Jedis**，而父 POM 的 `<dependencyManagement>` 会**强制覆盖传递依赖版本**（已实测 Boot 2.7.5 的 BOM 确实管理 `redis.clients:jedis`，`jedis.version=3.8.0`）。**Boot 3.5.16 管理的 Jedis 是 `6.0.0`**（实测 `spring-boot-dependencies-3.5.16.pom`：`<jedis.version>6.0.0</jedis.version>`），而 `langchain4j-community-redis` 依赖 Jedis **7.x** —— 一旦被降级，`RedisEmbeddingStore` 会在首次建索引时抛 `NoSuchMethodError`，**报错点离根因很远**。这是 R3「装配冲突」最可能的真实触发原因。

> **Boot 3.5.16 实测管理的版本**（2026-09-17 由 `spring-boot-dependencies-3.5.16.pom` 核实，供 Task 7/Task 9 引用）：
> `jedis 6.0.0`｜`lettuce 6.6.0.RELEASE`｜`mysql 9.7.0`｜`thymeleaf 3.1.5.RELEASE`｜`spring-framework 6.2.19`｜`maven-compiler-plugin 3.14.1`
> 注：本机 Redis 服务端是 3.0.504（不支持 RESP3/HELLO），但**客户端 Lettuce 6.6 已实测可用**（RESP3 协商失败自动降级，连接与 SET/GET 均正常）。

```bash
mvn dependency:tree -Dincludes=redis.clients:jedis
mvn dependency:tree -Dincludes=com.squareup.okhttp3     # LangChain4j 有已知 classpath 破坏记录
mvn dependency:tree -Dincludes=com.fasterxml.jackson.core
```

> **M2-1 实测结果**（2026-09-18，引入 LangChain4j 后）：
> - `dev.langchain4j:langchain4j` / `-core` / `-open-ai` / `-http-client` 均解析为 **1.20.0（稳定）**；`-http-client-jdk` 为 runtime
> - 传递依赖含 `langchain4j-reactive-streaming:1.20.0-beta30` → **不排除**：它本就只有 beta、无 GA 可对齐；全树 `omitted for conflict/duplicate` = **0**；6 个类 vs core 560 个类，无 classpath 遮蔽；M2-5 做 SSE 时正好要用
> - `redis.clients:jedis`：**未引入**（要等 M2-3 引入 `langchain4j-community-redis` 才出现 —— 那才是 R3 的真正触发点）
> - `com.squareup.okhttp3` / `jackson`：**未引入冲突**

- 处置：优先引入 `langchain4j-bom` / `langchain4j-community-bom` 统一锁版本；仍冲突则在 `<properties>` 显式覆盖 `jedis.version`。
- 同时确认：Spring Data Redis 用 **Lettuce**、LangChain4j 用 **Jedis**，应用里会有**两个 Redis 客户端与两个连接池**，需分别配置并计入压测观察项。
- **此项不过，不要开写编排层。**

---

## 7. 客服模块详细设计

### 7.1 RAG

- **建库**：启动时**异步**检查 Redis 索引，缺失则从 MySQL 读 575 商品 → 清洗 HTML → **剔除价格/库存/上下架字段** → 分块（size 400 / overlap 80）→ 嵌入 → 写入 `RedisEmbeddingStore`。**不得阻塞启动**；索引就绪前 `/api/cs/chat` 走"仅工具、无 RAG"的降级路径。
- **语料红线（v1.1 新增，与 Python 版的有意偏离）**：chunk 文本**只含稳定语义**（名称 / 分类 / 标签 / 简介 / 详情），**不含价格、库存、上下架状态**。
  - 依据：Python 版实测 609 片段中 **575 段**正文写死「价格：X 元 / 库存：N 件」（`kb_build.py:70-76`），注入时截断 200 字符**恰好覆盖这两行**；快照停在 8/13。照搬“对齐 Python 版分块”会让**静态旧价**进入 prompt，与工具结果并列冲突 —— 这正是对「数据铁律」的反例。
  - 验收：语料构建后断言 **0 个 chunk 命中价格/库存字样**；并对同一问题（如"这个多少钱"）在"语料含价格 / 不含价格"两种情况下各跑一次，比较是否引用旧值。
- **重建**：`@Scheduled(cron="0 0 3 * * ?")`，**全量重建**。LangChain4j 层**没有别名 API**（`RedisEmbeddingStore` 只暴露 `indexName`，"双写 + 原子切换"无法照做），实现方式二选一：
  - (a) `FT.DROPINDEX <idx> DD` + 重建 —— 609 片段重建很快，接受秒级窗口；
  - (b) 自管别名：自己用 Jedis 发 `FT.ALIASADD` / `FT.ALIASUPDATE`，让 langchain4j 以**别名**作为 `indexName`（需绕开它"索引不存在则自动创建"的逻辑）。
- **阶段切换代价**：换嵌入模型（`all-MiniLM` 384 维 → `bge-small-zh` 512 维）时**向量维度写在索引 schema 里，必须整索引重建**，没有平滑路径。
- **检索**：关键词 2-gram 命中率 + 向量 HNSW topN → **RRF（k=60）融合** → Top3
- **落地约束**：检索结果只作**语义参考**；价格/库存/订单状态**一律以工具结果为准** —— 注意这条约束的**结构性保障是上面的语料红线**，不是靠 prompt 单点兜底
- **阶段2**：换 `bge-small-zh-v1.5`，用同一组查询（专名 / 同义 / 模糊）产出 `docs/RAG-EVAL.md`

### 7.2 工具层（`MallTools`，6 个 `@Tool`）

| 工具 | 参数 | 数据来源（**已按 M2-2 实现校准**） |
|---|---|---|
| `searchGoods` | keyword, limit | `NewBeeMallGoodsMapper.findNewBeeMallGoodsListBySearch`（复用商城查询，其 SQL **无 ORDER BY** —— 见 `MallTools` javadoc） |
| `getGoodsDetail` | goodsId | `NewBeeMallGoodsMapper.selectByPrimaryKey`（**不含分类名** —— 实体未映射，与原表不符，已按实际校准） |
| `checkStock` | goodsId | 同上（库存/上下架） |
| `queryOrder` | orderNo（阶段2：当前用户归属校验） | `NewBeeMallOrderMapper`（返回 `userAddress`，**比 Python 版更敏感** —— 见下方阶段2 待办） |
| `searchByCategory` | categoryName, limit | **新增** `NewBeeMallGoodsMapper.selectByCategoryNameLike`（商品侧 JOIN 分类名，功能等价于 `GoodsCategoryMapper` 但更直接） |
| `recommendGoods` | keyword, sort, limit | **新增** `NewBeeMallGoodsMapper.selectForRecommend`（在售优先 + `<choose>` 白名单排序；`sort` 仅识别 `price_asc`/`price_desc`，其它值落到 `goods_id asc`） |

> **与实现校准说明**：原表写的 `GoodsService.*` / `GoodsCategoryMapper` 与实现有两处不同（实现直接走 Mapper、并新增了 2 条只读 SQL）。已按实际校准。
>
> ⚠️ **阶段2 待办（P4）**：`queryOrder` 目前返回完整 `userAddress`（收货人 + 电话 + 地址），**比 Python 版（仅 `user_name`）更敏感**。阶段2 除「订单归属校验」外，还需**复核 `userAddress` 是否应脱敏**。

> **行为对齐**：Python 版 `search_goods` 返回的 VO **不含库存与上下架状态**，并在工具描述里要求模型继续调 `check_stock` / `get_goods_detail`（`api_tools.py:64-67`）——那是有意的"双跳"。Java 版若把库存直接塞进 `searchGoods` 返回，模型会少调一次工具、行为契约随之改变，需在 Java vs Python 对照表中显式说明。

**安全与滥用防护（v1.1 扩充）**

- **数据面**：全部只读；`limit` 白名单**按工具分别定**（`searchGoods` / `searchByCategory` 为 1~10，`recommendGoods` 为 1~5 —— 对齐 Python 版实测行为）；阶段2 引入登录态后**必须校验订单归属 == 当前登录用户**（决策 #25）。
- **编排面**：工具循环 **`max-iterations = 6`**（Python 版实测 `while response.tool_calls:` **无任何上限**，模型不收敛会无限烧调用）；单会话同时只允许 1 个在途请求。
- **接入面**：`/api/cs/chat` 是**匿名可达**的（原 `/api/agent` 的 `X-Agent-Key` 随决策 #18 一并移除），必须补：按 session 限流（如 10 次/分钟）、SSE 单连接上限 120s、单次请求 token 上限，并记录用量以估算成本。
- **失败面**：任一工具异常一律转为**文本结果**回灌给模型（如"查询失败，请稍后再试"），不中断整轮对话。
- **显式记录的行为差异**：Java 版取消"Java API 不可达 → 回退直连 MySQL"（Python 版 `api_tools.py:42-53` 的容错契约）。同进程后不再需要，但要写进对照文档，避免 Java vs Python 对比时被误读为回归。

### 7.3 编排与质检

- `CsAgentService`：LangChain4j `@Tool` + `ChatMemory`；客服人设沿用 Python 版 prompt（`CS_PROMPT` 实测 922 字符 / 5 段：人设 · 人味 · 推荐能力 · 模糊分级 · 数据铁律）。**注意**：Python 版对客服回答**没有任何输出格式硬约束**（不禁止 markdown/HTML）——若 Java 版要加，属新增约束，需在对照表中说明。
  <br>⚠️ **M2-1 决定（2026-09-18）：不引入 `@AiService` / `langchain4j-spring-boot-starter`**（starter 只有 beta），改为**手写编排**（直接调 `ChatModel` + 工具循环）。若 starter 进入 GA，可切回声明式接口。
- ⚠️ **M2-2 注入 `ChatModel` 时必须用 `@Qualifier("csChatModel")`** —— M2-4 将新增**质检用的第二个模型**，届时按类型注入会产生 Bean 歧义。（写进设计文档而非只留任务卡，避免“说好的后续处理”漂走）
- `QaReviewer`：独立第二角色，只查**硬伤**（数据准确性 / 诚实性 / 合规性 / 推荐合理性），不挑表达风格
- **默认 `gate`**（对齐 Python 基准实测：`QA_MODE` 默认 `gate` 且质检**同步阻塞**在回答返回之前）：同步把关，不合格追加质检反馈后**重答一次**（不循环、不再复核）
- **`audit` 模式**：回答先流式返回，质检异步跑并通过 SSE `review` 事件推送结果 —— 此时 `done` **不是**终止事件，时序见 §4.2
- 质检判定沿用 Python 版口径（"合格"字样且无"不合格"），但**建议改为结构化输出**（`合格|不合格` + 理由字段），避免中文子串匹配带来的误判

### 7.4 会话记忆

- 表：`cs_chat_memory(conversation_id, user_id, role, content, created_at)`，`conversation_id` 建索引
- 维度：**登录用户优先用 `userId`；未登录用前端持久化的 `conversationId`（localStorage 生成，随请求携带）**。
  - ⚠️ **不要用 `sessionId`**：本方案**不启用 Spring Session**（见 §6.1），session 是 Tomcat 内存态，重启即变 —— 那样 DoD「会话记忆跨刷新/重启保留」在匿名场景下永远不可能满足。用前端自持的 `conversationId` 才能同时满足"跨刷新"与"跨重启"。
- 保留策略：**单会话上限 10 轮（取最近 20 条消息注入）**；超过 30 天的会话定期归档删除（Python 版实测**无任何记忆实现**，`memory.py` 的 KV 表从未被客服使用 —— 本节是全新设计，不是对齐）
- 写入时机：每轮对话结束后异步落库，不阻塞流式响应

---

## 8. 前端设计

### 8.1 双形态（同一套模板与 CSS 来源）

| | 浮窗 | `/cs` 完整页 |
|---|---|---|
| 尺寸 | 380×560，右下角固定 | 全页 |
| 结构 | 轻量对话（**无自己的导航头**） | 商城 header/footer + 三栏 |
| 功能 | 气泡、流式、3 条 chips、「展开完整页」 | 会话历史（232px）+ 对话 + 上下文面板（340px） |
| 上下文 | 支持 `goodsId` | 支持 `goodsId` / `orderNo` |

### 8.2 上下文面板（可观测性，六区块）

识别意图 · 工具调用（含耗时）· 命中商品 · 关联订单 · 质检复核 · 引用来源（含融合分）

### 8.3 增强项（M3 末尾，可选）

浮窗可拖拽移动 · 手机端底部抽屉 · 消息复制/重生成

### 8.4 输出编码（强制，决策 #41）

- LLM 流式文本与工具返回的商品名，渲染前一律走转义函数；**禁止裸 `innerHTML` 拼接模型输出**。
- 对齐 Python 版做法（`web/index.html:219` 的 `esc()`，覆盖 `& < > "`），但**必须补上单引号 `'`** —— 现有实现在属性语境下留有缺口。
- 商品卡片的数据**从工具结果解析，不从 LLM 文本解析**（Python 版 `web/index.html:332-337` 的正则取数即此范式），避免模型幻觉污染结构化展示。
- DoD 加一条 grep 核查：`innerHTML` 出现处不得直接拼接未经转义的模型输出。

---

## 9. 部署与运维

### 9.1 Docker Compose（3 容器）

```
mysql:9.7        + ops/init.sql 初始化（575 商品）
redis:8          （一栈三用：首页缓存 + ZSet 订单超时 + 向量索引）
mall-backend     （eclipse-temurin:21-jre，依赖前两者健康后启动）
```

**镜像注意**

- `redis:8` 已内置 Query Engine / JSON（`FT.*`、`JSON.*` 可用）；**不再使用 `redis/redis-stack`**（6.2/7.2/7.4 已于 2025-12 停止维护）。`REDIS_ARGS` 在 `redis:8` 上不生效，改用 `command` 传参；RedisInsight 需要时用独立的 `redis/redisinsight` 镜像。
- `mall-backend` 镜像**必须装 `fontconfig` + 字体**，否则 Hutool 图形验证码在容器内直接失败、前后台登录不可用（详见 §6.3）。
- 三者共用同一 Redis 实例，**须开 AOF/RDB 持久化并分 DB 隔离**（缓存 / ZSet 队列 / 向量索引），避免一次 FLUSH 同时打掉订单超时队列与向量索引。

### 9.2 配置与密钥（决策 #22）

- 全部敏感项走环境变量：`DB_PASSWORD`、`CS_MODEL_API_KEY`、`CS_MODEL_BASE_URL`
- **彻底移除硬编码**（现有 Python 侧的明文密码不进入新仓库）
- `.env` 入 `.gitignore`，仓库只留 `.env.example`
- ⚠️ **容器访问宿主机网关**：LLM 网关若跑在 Windows 宿主进程（OmniRoute 或任何本地代理），容器内 `localhost` 指向容器自身，**必须用 `host.docker.internal`**（Docker Desktop for Windows 支持；已实测 WSL 侧亦连不通宿主 `20128`）。
- 冒烟清单加一条前置项：**容器内 `curl` 网关健康端点通过**，否则 DoD「3 容器 healthy + 浮窗能答」会在集成阶段才暴露失败。

### 9.3 前期阻塞

- **Docker Desktop 未安装** → 用户自行安装（M1 前，需 WSL2 后端）
- **模型通道待定** → 现状是 Agnes AI Hub 直连（`.env` 实测），OmniRoute 是候选而非既成通道；M2 开写编排层之前，先在目标通道上跑 **function calling spike**（与 §6.4 的依赖预检并列，同为 M2 前置；风险见 R1）

---

## 10. 测试与验收

### 10.1 完成定义（DoD）

**功能**
- [ ] `docker compose up -d` → 3 容器全部 healthy
- [ ] 商城前台 12 页 + 后台 8 页全部正常
- [ ] Redis 首页缓存命中、ZSet 订单超时自动关闭仍生效
- [ ] 浮窗问「化妆水有货吗」→ 库存数字与数据库一致
- [ ] 商品详情页开客服 → 自动带入该商品，问「这个多少钱」能正确回答
- [ ] `/cs` 展示工具轨迹 + 质检结果 + 引用来源 + 阶段耗时
- [ ] 回答为**流式逐字**输出（首字 ≠ 整段）
- [ ] 会话记忆跨刷新/重启保留（**匿名场景**用前端 `conversationId`，见 §7.4）
- [ ] **语料红线**：构建后 0 个 chunk 含价格/库存字样；"这个多少钱"不引用语料中的旧值（§7.1）
- [ ] **容器内验证码可辨**（前后台登录页截图，验证 AWT 字体就绪，§6.3）
- [ ] **限流与上限生效**：连续快速提问触发限流；工具循环不超过 6 次；SSE 连接 120s 自动收尾（§7.2）
- [ ] `audit` 模式下 `review` 在 `done` 之后到达且不抛异常（§4.2）

**质量**
- [ ] 工具层/编排层单元测试（Mock LLM）
- [ ] Testcontainers 集成测试（MySQL + Redis）
- [ ] GitHub Actions CI 通过
- [ ] 无任何硬编码密钥（grep 核查）
- [ ] **依赖预检留痕**：`mvn dependency:tree` 的 Jedis / OkHttp / Jackson 结果记录在 `UPGRADE-BOOT3.md`（§6.4）
- [ ] **XSS 核查**：`innerHTML` 处无未转义的模型输出；12 条 XSS payload 在浮窗与 `/cs` 各跑一遍（§8.4）
- [ ] **答案质量不劣化**：沿用 Python 版 `12_agent_cs/test_questions.py` 的 10 问作为固定回归集，通过率不低于当前基线

**性能**
- [ ] 首字延迟 < 1.5s、完整回答 < 8s、并发 10 不阻塞
- [ ] 产出 Java vs Python 同题对比表
- [ ] 虚拟线程开关前后压测对比（**容器内**）

**文档**
- [ ] `README.md`（一图看懂 + 一键启动 + 演示路径）
- [ ] `ARCHITECTURE.md` / `UPGRADE-BOOT3.md` / `RAG-EVAL.md` / `DEMO.md`

### 10.2 测试策略

| 层次 | 范围 | 工具 |
|---|---|---|
| 单元 | 工具方法、RRF 融合、事件序列化 | JUnit5 + Mockito |
| 编排 | 工具循环、质检分支（**Mock LLM**） | Mock `ChatModel` |
| 集成 | 建库、检索、SSE 端到端 | Testcontainers（MySQL/Redis） |
| 冒烟 | 商城全页面 + 缓存 + 订单超时 | 手工清单 + 探针截图 |

---

## 11. 里程碑与工作量

| 阶段 | 内容 | 估算 |
|---|---|---|
| **M1** | 新仓库 + Boot 3.5/Java 21 升级 + 冒烟回归 + 升级文档 | 1~2 天 |
| **M2** | 依赖预检 + 模型通道 spike + LangChain4j 装配 + RAG 建库 + 工具层 + 编排 + 质检 + SSE + 记忆落库 + 单测 | 3~5 天 |
| **M3** | 浮窗 + `/cs` + 上下文带入 + 可观测面板 + compose + 虚拟线程压测 + 中文嵌入对比 + Testcontainers/CI + 文档 | 3~4 天 |
| M3+ | 增强 UI（拖拽/抽屉/重生成）· 可选 | +1~2 天 |

**合计约 7~11 个工作日**（主要变量：M2 前置的依赖预检与模型通道 function calling 稳定性）。

---

## 12. 风险登记册

| # | 风险 | 概率 | 影响 | 缓解 | 触发条件 |
|---|---|---|---|---|---|
| R1 | 模型通道不支持/不稳定支持 function calling（候选：Agnes 直连 / OmniRoute） | 中 | 高 | 先跑 spike；失败则换官方 key 直连；再失败降级为「关键词触发工具」 | spike 连续 5 次有 ≥2 次无 `tool_calls` |
| R2 | Boot 2.7.5 → 3.5 隐藏坑导致升级受阻 | 中 | 中 | 分步提交可回退；参考 upstream 3.x 分支 | 步 3 后启动失败超 2 小时 |
| R3 | LangChain4j 与 Boot 3.5 依赖冲突 —— **最可能的具体成因是 Jedis 版本被 Boot BOM 降级**（另有 OkHttp / Jackson 已知 classpath 问题） | 中 | 中 | 按 §6.4 先做 `mvn dependency:tree` 预检；用 langchain4j-bom 锁版本或覆盖 `jedis.version`；仍不通再评估换框架 | 依赖预检出现版本降级，或运行时 `NoSuchMethodError` |
| R4 | 虚拟线程无收益（pinning/连接池限制） | 中 | 低 | 压测后决定是否开启；产出真实结论（本身即交付物） | 压测显示吞吐下降 |
| R5 | `redis:8` 镜像拉取失败/过大 | 低 | 中 | 预拉取；备选内存向量检索（609 片段足够） | 拉取超 30 分钟 |
| R6 | 范围过大导致半成品 | 中 | 高 | 里程碑分离，每阶段可独立演示；M3 增强项可选 | M2 超期 3 天以上 |
| R7 | 中文嵌入模型需自备 ONNX + tokenizer | 中 | 低 | 阶段1 用内置英文模型先跑通；阶段2 再换 | — |
| R8 | WSL/Windows 双 git 导致行尾噪声（旧仓库已发生） | 中 | 低 | `.gitattributes` 统一 LF；固定用一侧 git 提交 | `git status` 出现大量非预期改动 |
| **R9** | **公共客服端点被滥用**：`/api/cs/chat` 匿名可达，无鉴权、无成本上限，配合无上限工具循环可被刷量；模型输出未经转义即为 XSS 面 | 中 | 高 | 决策 #40/#41：限流 + 工具循环上限 + SSE 时长上限 + 强制转义；DoD 加安全检查项 | 单 session 短时间高频请求，或 `innerHTML` 处出现未转义模型输出 |
| **R10** | **重写后答案质量回退**：prompt / 工具契约在跨语言重写中必然漂移，而 DoD 只有"产出对比表"、没有通过标准 | 中 | 高 | 把 Python 版 `test_questions.py` 的 10 问固化为回归集，通过率 **不得低于当前基线**；对"语料红线"单独立项验证（§7.1） | 回归集通过率低于基线，或出现引用语料旧价格 |
| **R11** | **容器内 AWT 无字体导致验证码失效**（前后台登录均不可用） | 高 | 中 | 镜像装 `fontconfig` + `fonts-dejavu-core`；M3 起容器后立即验证码截图 | 容器内访问 `/common/mall/kaptcha` 返回 500 或图片为空 |

---

## 13. 附录

### 13.1 待实测验证项（不留假设）

1. 目标模型通道的 function calling 稳定性（R1）—— **先在当前在用的 Agnes 直连上测**，OmniRoute 作为备选
2. **Jedis / OkHttp / Jackson 的依赖解析结果（R3 → §6.4）** —— M2 开写编排层的硬门槛
3. Hutool ≥ 5.8.40 在 JDK 21 的验证码表现，**且在容器内（含字体）**（R11）
4. LangChain4j 官方 starter 与 Boot 3.5 的实际装配（含规避 `1.19.1` / `1.19.1-beta29` 误发布版本）
5. 虚拟线程开启前后的实测吞吐/延迟（R4，**容器内**）
6. 英文 vs 中文嵌入的中文检索质量差距（R7），以及换模型时整索引重建的耗时
7. `redis:8` 在本机 Docker 的可运行性与 `FT.*` 可用性（R5）
8. 升级后**带尾斜杠**的旧 URL 行为（§6.3）
9. 语料剔除价格/库存后，10 问回归集是否仍达标（R10 → §7.1）

### 13.2 明确不做（范围边界）

- ❌ 不做 `/admin/cs/**` 客服后台（M4 候选）
- ❌ 不做客服写操作（加购物车/取消订单）
- ❌ 不保留 `/api/agent`（删除，需要时从 git 历史取回）
- ❌ 不改动两个旧仓库（`newbee-mall`、`ai开发` 原样保留）
- ❌ 不用 Spring AI / pgvector / Chroma / MySQL VECTOR
- ❌ 不用 `redis/redis-stack` 镜像（已停更，改用 `redis:8`）

### 13.3 参考

- 旧 Python 客服行为基准：`D:/GitHub/xiangmu/ai开发/12_agent_cs/`（原地不动）
- 商城 UI 规格：`D:/GitHub/xiangmu/newbee-mall/docs/DESIGN.md`
- 升级参考（只读不 merge）：`newbee-mall` 的 `upstream/spring-boot-3.x`（Boot 3.1.0 + Java 17）
- 版本约束来源（v1.1 已逐条复核）：
  - Spring Boot 3.5 系统要求（Java 17~25，Java 25 自 **3.5.6** 起文档化）— https://docs.spring.io/spring-boot/3.5/system-requirements.html
  - Spring Boot 3.0 迁移指南（尾斜杠、`spring.factories`、`spring.redis.*`、驱动坐标）— https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide
  - Thymeleaf 3.1 变更（移除 `#request/#session/...` 表达式对象）— https://www.thymeleaf.org/doc/articles/thymeleaf31whatsnew.html
  - JEP 491「Synchronize Virtual Threads without Pinning」（**JDK 24**）— https://openjdk.org/jeps/491
  - Hutool CHANGELOG（issue #3985 于 **5.8.40** 修复）— https://github.com/chinabugotech/hutool/blob/v5-master/CHANGELOG.md
  - Redis Stack 停更公告（6.2/7.2/7.4 于 2025-12 停止维护）— https://hub.docker.com/r/redis/redis-stack
  - Redis 8.0 发布（内置 Query Engine / JSON）— https://github.com/redis/redis/releases/tag/8.0.0
  - LangChain4j Spring Boot 集成（官方 starter / `@AiService`）— https://docs.langchain4j.dev/tutorials/spring-boot-integration/
  - Boot 3.5 的 Jedis 版本管理现状（issue #45669）— https://github.com/spring-projects/spring-boot/issues/45669

---

## 14. v1.1 评审修订记录（2026-09-17）

v1.0 经独立技术评审，核实两处真实仓库（`newbee-mall`、`ai开发/12_agent_cs`）与版本断言后，共 10 条意见，全部并入本稿；另有 3 条「已核实」断言被证伪，一并标注。

| # | 评审发现 | 本稿处置 | 关键证据 |
|---|---|---|---|
| 1 | RAG 语料把价格/库存写死进 chunk，与「数据铁律」自相矛盾 | §7.1 新增**语料红线**；决策 #39；DoD 加断言项 | 609 片段中 575 段含价格库存；`kb_build.py:70-76`；注入截断 200 字符恰覆盖该两行 |
| 2 | 公共 SSE 端点零防护 + 前端无 XSS 方案，风险册无安全项 | §7.2 安全与滥用防护；§8.4 输出编码；决策 #40/#41；新增 R9 | Python 版 `while response.tool_calls:` 无上限；`cs_server.py:116` 无并发上限 |
| 3 | 质检默认值与基准相反；SSE `done`/`review` 时序未定义 | §7.3 改为默认 `gate`；§4.2 增加**时序约定**；决策 #17/#31 修正 | `cs_agent.py:162` 默认 `gate` 且同步阻塞 |
| 4 | 验证码在容器里缺 AWT 字体，DoD 必挂 | §6.3 新增条目；§9.1 镜像注意；新增 R11 | `CommonController.java:46-76` 走 AWT；`eclipse-temurin` 常见 `Fontconfig head is null` |
| 5 | Boot BOM 与 `langchain4j-community-redis` 的 Jedis 版本冲突 | 新增 **§6.4 依赖预检**；R3 补具体成因；决策流程前置 | Boot BOM 管理 `jedis.version`（2.7.5 为 3.8.0）；community-redis 依赖 Jedis 7.x |
| 6 | 升级清单遗漏：`maven-compiler-plugin` 显式 1.8、`spring-session-core` 死依赖、尾斜杠 | §6.1 三行修订；§6.2 步 2 验证改为查字节码版本 | `pom.xml:76-85`；全仓零 `org.springframework.session` 引用 |
| 7 | 「LangChain4j 无官方 Boot starter」为**错误断言** | §2#4 / §5 / §3.2 修正为官方 starter | `langchain4j-spring-boot-starter` 存在，需 Boot 3.5+ |
| 8 | 「双写新索引 + 原子切换」在 LangChain4j 层不可实现 | §7.1 改为两选一的可行方案；补维度变更代价 | 该 store 只暴露 `indexName`，无别名 API |
| 9 | Redis Stack 镜像已停更（2025-12） | §2#5/#8、§5、§9.1、§13.1 全部改为 **`redis:8`** | Redis 官方停更公告 + Redis 8 内置 Query Engine |
| 10 | 容器访问不到宿主网关；DoD 记忆要求与 session 载体冲突；现状表缺模型通道 | §9.2 补 `host.docker.internal`；§7.4 改用前端 `conversationId`；§1.2 补三行现状 | WSL 亦连不通宿主 20128；未启用 Spring Session |

**被证伪的 v1.0 断言**（引用时不要再沿用）：① 「无官方 Boot starter」；② Hutool issue #3985「5.8.25 仍存」（实为 **5.8.40 已修复**，报告版本 5.8.33）；③ 「仅 6 个直接依赖」（实为 8 个）。

**未采纳的建议**：（无 —— 10 条评审意见全部并入。）
