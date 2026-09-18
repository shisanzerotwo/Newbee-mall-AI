# newbee-mall-ai 项目状态快照

> **用途**：会话压缩 / agent 交接用的状态快照。**任何 agent 接手本项目，先读这个文件 + `docs/DESIGN.md`。**
> 最后更新：2026-09-18（M2-3 完成、待检查）

---

## 1. 项目定位

把 **newbee-mall（Java 电商）** 与 **AI 智能客服** 融合为**一个 Spring Boot 应用**：
客服不是 iframe 里的外部网站，而是商城内**原生能力**（同进程、同栈、同前端）。

- **落点**：`D:\GitHub\xiangmu\newbee-mall-ai`（新建仓库，旧仓库 `newbee-mall` / `ai开发` 原样保留）
- **来源**：商城代码拷自 `D:\GitHub\xiangmu\newbee-mall`；AI 客服参照 `D:\GitHub\xiangmu\ai开发\12_agent_cs`（Python）
- **GitHub 推送**：**暂停中**（`origin` 已配 `https://github.com/shisanzerotwo/newbee-mall-ai.git`，但远程仓库尚未创建）

## 2. 技术栈

| 层 | 选型 |
|---|---|
| 运行时 | **Java 21**（本机用 JDK 25 编译 `--release 21`） |
| 框架 | **Spring Boot 3.5.16**（从 2.7.5 升级） |
| AI 编排 | **LangChain4j 1.20.0**（core + open-ai 稳定；community-redis / embeddings 用 `1.20.0-beta30`） |
| 向量库 | **redis:8**（Docker，Query Engine 已实测可用）宿主端口 **16379** |
| 数据库 | MySQL 9.7（本机 3306 / 容器内网） |
| 前端 | Thymeleaf（商城原生）+ SSE |

## 3. 里程碑进度

| 里程碑 | 内容 | 状态 |
|---|---|---|
| **M1** | Boot 2.7.5→3.5.16 / Java 8→21 升级 + javax→jakarta(44处) + 配置迁移 + 冒烟基线 | ✅ **完成**（冒烟 16/16，与升级前一致） |
| **M2-1** | LangChain4j 引入与装配（`CsAgentConfig` → ChatModel Bean） | ✅ 完成（claude 两轮检查通过） |
| **M2-2** | `MallTools` 6 个只读 `@Tool` + 2 条 Mapper SQL | ✅ 完成（claude 两轮通过，测试 10/10） |
| **M2-3** | RAG：`KnowledgeBuilder` + `RagService`（混合检索 RRF）+ 语料红线 | ✅ 完成（提交 `95b0f29`；测试 14/14；语料红线 0 命中） |
| **M3 容器化** | Dockerfile + docker-compose + init.sql | ✅ 完成且**真机验证**（3 容器 healthy） |
| **M2-4** | 编排 `CsAgentService`（工具循环 + RAG）+ 质检 `QaReviewer`（audit/gate） | ✅ **完成**（测试 51/51；**真实模型端到端已跑通**；claude 三轮复核：终审「可以提交」） |
| **M2-5** | SSE 流式接口 + 会话记忆落库 | ✅ **完成**（测试 **106/106**；claude 6 项审查全过「可以提交」；真机时序红线验证通过） |
| M2-6 | 收尾测试与 CI | ⬜ **下一步**（用例已随 M2-1~M2-5 累积到 **106** 个） |
| **M3-A** | 前端原生融合第一步：`/cs` 完整页 + 共用 SSE 客户端（`cs-core.js`）+ 六区块面板 | ✅ 完成（提交 `217c96e`；测试 118/118；冒烟 16/16；**iframe 0 处**） |
| M3-B | 浮窗（全站唤起）+ 详情页/订单页「问客服」入口 | 🔄 **进行中**（任务卡 `docs/TASK_M3-B.md`） |
| M3 其余 | 虚拟线程压测、中文嵌入对比（`bge-small-zh`）、Testcontainers/CI | ⬜ |

**提交数**：33（最近：`217c96e` M3-A 前端原生融合第一步）

> ✅ **M2-5 时序红线已验证守住**（DESIGN §4.2）：真机实测 `stage → tool(checkStock×2) → delta → done` →
> **`review` 在 `done` 之后仍能送达**，且 complete 只发生一次（claude 已复核代码 + 端到端测试双重证据）。
>
> ⚠️ **已知上游现象（非代码缺陷）**：agnes 限流时 OmniRoute **只回 keepalive 心跳 chunk**
> （`id=chatcmpl-keepalive`，`delta:{}`）→ 我们拿不到 content，只能走兜底话术。
> 已用 curl 直接探测证得（同一时刻同参请求：1/3 正常返回、2/3 全是 keepalive）。

## 4. ⭐ 关键决策与基准（容易搞错，务必遵守）

1. **基准原则：凡冲突，以商城源码为准，不以 Python 版为基准。**
   Python 教学版 `db_tools.py` 把上下架判断**写反了**（`== 1` 判在售），而商城权威定义是
   **`Constants.SELL_STATUS_UP = 0`**（购买链路 `where ... and goods_sell_status = 0`；
   实测分布 `0`→573 / `1`→2）。Python 版把 573 个在售说成"已下架"。
2. **语料红线（M2-3）**：写入向量库的 chunk **不得含价格 / 库存 / 上下架状态** ——
   因为「数据铁律」要求价格/库存/订单状态**一律以工具查询为准**，RAG 只作语义参考。
3. **不引入 `@AiService` / `langchain4j-spring-boot-starter`**（只有 beta）→ M2-1 决定手写编排。
4. **Jedis 版本必须覆盖**：`community-redis` 要 7.2.1，Boot BOM 强制降到 6.0.0 →
   pom 里显式 `<jedis.version>7.2.1</jedis.version>`（DESIGN §6.4 预检的真实触发点）。
5. **M2-2 注入 `ChatModel` 时必须用 `@Qualifier("csChatModel")`**（M2-4 会加质检用的第二个模型 → 否则 Bean 歧义）。
6. **容器化**：mysql/redis **不映射宿主端口**（避免与本机冲突）；app 端口可配 `${APP_PORT:-28089}`；
   redis 映射宿主 **16379**（避开本机 Redis 3.0.504 的 6379）。
7. **模型必须用 model id，不能用显示名**（M2-4 打通模型通道时踩的最大一坑）：
   OmniRoute 的 `/v1/models` 同时返回 `id` 与 `name`，请求里写 `name`（`Agnes 2.0 Flash`）会报
   `not available in the active live catalog`；必须写 **id** —— `agnes/agnes-2.0-flash`。
8. **质检模式默认 audit 而非 gate**（M2-4 实现的取舍，已回改 DESIGN 对齐）：gate 同步阻塞会拉长响应，
   audit 更适合 SSE 流式；文档里的 5 处 `gate` 表述已改成 `audit`（决策 #17/#31、数据流图、§4.2 时序、§7.3）。
9. **重试分两层，且已把内置那层关掉**（M2-4 事实订正，2026-09-18）：
   LangChain4j 的 `OpenAiChatModel.Builder` **有** `maxRetries`（默认 **2**，即单次调用最多 3 个 HTTP 请求，
   指数退避不可控），**可以设 0 关闭** —— 本项目在 `CsAgentConfig` 里设为 **0**，只留自实现那层
   （`cs.agent.max-model-retries=3`，线性退避 **4s×n**，贴合 OmniRoute 的 3s 重置窗口）；
   否则两层叠加最坏 3×3 = **9 个请求**。
   自实现用 **`NonRetriableException` 黑名单**（400/401/403/模型不可用立即抛出），其余（429/5xx/网络 IO）才重试。
   ⚠️ 不要写成「`RetriableException | IOException` 白名单」——`IOException` 是受检异常，编译不过。
   📌 **教训**：初版曾断言"Builder 无 maxRetries"并称"用 javap 核实过"，实际是 javap 过滤正则
   漏了 `Retries`（写成 `Retry`）→ **用过滤器"没搜到"时，先质疑过滤器，别急着当结论**。
   行为证据：`OpenAiRetryBehaviorTest` 用假上游实测请求数 **默认 3 / maxRetries(0) 1 / maxRetries(1) 2**。

10. **退避必须严格大于上游重置窗口**（M2-4 实测教训，2026-09-18）：
    agnes 的 429 响应**自述** `reset after 3s`，而**每一次尝试都会刷新这个窗口** ——
    所以 1s/2s/3s 这类短退避跨不过去（实测 3 次尝试全部 429）。
    现用 `4000ms × 第几次`（4s / 8s），取值**直接依据响应自述的重置窗口**。
    ⚠️ **表述纪律**（claude 终审指出）：**不要**把"同参数 curl 单发返回 200"当作
    "Java 路径没被限流"的反证 —— 该额度可能是**池级限流**，单发成功不足以证明该路径正常。
    只用响应自述的窗口作为依据。

11. **⚠️ 配额耗尽时的失败预算与 DESIGN §2#14「<8s」冲突**（claude 终审实测，留给 M2-5）：
    免费额度受限时单次 `answer()` 实测 **24~80s**（`15:30:58→15:32:18` = 80s 后失败；
    `15:35:16→15:35:40` = 24s 后成功），而 §2#14 承诺「完整回答 <8s」，**差一个数量级**；
    当前 `maxModelRetries=3` + `timeout=60s` 的最坏预算还会吃掉 M2-5 的 120s SSE 上限大半。
    → **M2-5 必须处置**：`cs.model.timeout-seconds` 调到 ~15、`max-model-retries` 降到 1~2、
    并给可读话术（“当前咨询较多，请稍后再试”）；同时把「免费额度下实测 24~80s」记入 §12 风险册。

## 5. 本机环境要点（坑都在这里）

| 事项 | 事实 |
|---|---|
| **shell** | 本机 bash 是 **WSL**（不是 Git Bash）。路径用 `/mnt/d/...`；Git Bash 用 `/d/...` 且会自动补 `.exe`，WSL 不会 → 调 Windows exe **必须带 `.exe`** |
| **WSL ↔ Windows** | WSL **访问不到 Windows 的 localhost**（如 28089/20128）→ 需用 Windows 的 `curl.exe`；**环境变量不会自动跨界**，需 `WSLENV=VAR/w` 转发（`ops/mvn.sh` 现已处理 **4 个**：`DB_PASSWORD` / `CS_MODEL_API_KEY` / `CS_MODEL_NAME` / `CS_MODEL_BASE_URL`） |
| **Maven** | 本机无 Linux Maven；`D:\tools\apache-maven-3.9.16`（Windows 版）。**一律用 `bash ops/mvn.sh <args>`**（它做 WSL→Windows 转发） |
| **Docker** | Docker Desktop 29.8.0 已装；守护进程需手动启动；**Docker Hub 被墙**，已在 `~/.docker/daemon.json` 配 3 个国内镜像加速（原配置有 `.bak` 备份） |
| **MySQL** | `E:\mysql-9.7.1`，密码用 `DB_PASSWORD` 环境变量（不落盘） |
| **Redis** | 本机 6379 是 **3.0.504（无向量能力）**；RAG 必须用容器 redis:8 的 **16379** |
| **XML 注释** | **不能含 `--`**（我在 pom 里写了 `----------` → `ModelParseException`） |
| **LangChain4j HTTP 客户端** | 默认的 `langchain4j-http-client-jdk` **调 OmniRoute 会失败**（`HTTP/1.1 header parser received no bytes`；同参同 key 用 curl 正常）→ 必须换 **OkHttp**：`.httpClientBuilder(new OkHttpClientBuilder())` |
| **模型环境变量必须 export** | Maven **不读 `.env`**！`CS_MODEL_BASE_URL` / `CS_MODEL_API_KEY` / `CS_MODEL_NAME` 都要 export（经 `ops/mvn.sh` 的 WSLENV 转发），否则 `cs.model.name` 取默认 `auto` → 路由到不可用 provider |
| **OmniRoute 会过度拉黑** | 连续 429 会把 provider 标成 `unavailable`，但**实测此时直接 curl 反而成功** → 先重启网关再判定（`Stop-Process node` + `Start-Process omniroute.cmd serve`） |
| **并行工具调用** | 同一批次里 `edit/write` 与 `git commit` **并行执行** → 提交会漏掉刚改的文件（已踩两次） |

## 6. 常用命令

```bash
# 编译 / 测试
cd /mnt/d/GitHub/xiangmu/newbee-mall-ai
bash ops/mvn.sh -q compile
export DB_PASSWORD=<redacted> && bash ops/mvn.sh test -Dtest=MallToolsTest

# 冒烟（升级前后对照；自动用 Windows curl.exe）
bash ops/smoke.sh                      # 默认 http://127.0.0.1:28089
bash ops/smoke.sh http://127.0.0.1:28090   # 容器实例

# 本机起应用（需 DB_PASSWORD）
export DB_PASSWORD=<redacted> && bash ops/mvn.sh spring-boot:run

# 容器化
cd /mnt/d/GitHub/xiangmu/newbee-mall-ai
docker compose up -d          # 3 容器；首次构建较慢
docker compose ps             # 期望均 healthy
docker compose exec -T mysql mysql -uroot -p<redacted> -e "USE newbee_mall_db; SELECT COUNT(*) FROM tb_newbee_mall_goods_info;"
```

## 7. 模型通道（✅ 已打通，2026-09-18）

**M2-4 的硬前置已满足**（实测 function calling 成功返回 `tool_calls`），且 **M2-4 的真实模型端到端已跑通**：
客服回答了「无印良品的化妆水有货吗」，调用 2 次 `checkStock`（10085 / 10080，均返回真实库存 1000 在售），
回答带人味（“有的~” + 反问引导），质检按 AUDIT 模式旁路。

> ⚠️ 三个使用要点（都踩过，详见 §5 的环境坑表）：
> 1. LangChain4j **必须用 OkHttp 客户端**（默认 JDK 客户端调不通 OmniRoute）
> 2. 模型环境变量**必须 export**（Maven 不读 `.env`）
> 3. provider 被标 `unavailable` 时**先重启网关再判定**

| provider | 状态 |
|---|---|
| **agnes** | ✅ **可用**（实测 `finish_reason: tool_calls`，参数正确） |
| huggingchat | active（未实测） |
| kilocode / kimi-coding / cursor / cline / openference | ❌ 额度耗尽 / 配额尽 / 无模型目录 |

### ⭐ 关键结论（踩过的坑，别再踩）

1. **OmniRoute 的 `/v1/models` 同时返回 `id` 与 `name`**：
   - `id` = **真实 model id**（如 `agnes/agnes-2.0-flash`）← **请求必须用这个**
   - `name` = 显示名（如 `Agnes 2.0 Flash`）← **用它请求会报** `not available in the active live catalog`
   - ⚠️ `omniroute models <provider>` 列的是**显示名**，极具误导性
2. **免费用户有限流**：HTTP 429（提示 `reset after 3s`）→ 调用需要**重试**
3. **`oauth status` 只显示 OAuth 类型** provider；**API key 类型的要看 `providers list`**
   （agnes / huggingchat 都是 API key 类型，在 `oauth status` 里根本看不到）
4. **herdr 里启动 codex 会先弹「目录信任确认」**（`Do you trust the contents of this directory?`）
   → 不回答就会表现为"启动后立即退出"，且 herdr 标记为 `blocked`。回答 `1. Yes, continue` 即可。

### 应用侧配置（`newbee-mall-ai/.env`，已被 gitignore）

```
CS_MODEL_BASE_URL=http://localhost:20128/v1
CS_MODEL_API_KEY=
CS_MODEL_NAME=agnes/agnes-2.0-flash
```

`ops/mvn.sh` 的 `WSLENV` 已扩展到 `DB_PASSWORD` + `CS_MODEL_API_KEY` + `CS_MODEL_NAME` + `CS_MODEL_BASE_URL`（否则 WSL 启动应用时这些变量传不到 Windows 侧）。

## 8. 下一步

1. ~~M2-3 等 claude 检查~~ ✅ 已提交 `95b0f29`
2. ~~codex 修复模型通道~~ ✅ 已打通（根因：model id vs 显示名；已实测 `finish_reason: tool_calls`）
3. ~~M2-4（编排 + 质检）~~ ✅ 已完成，测试 47/47 + 真实模型端到端跑通
4. **M2-5（SSE 流式 + 会话记忆）** ✅ 已完成 → claude 6 项全过、可以提交
5. **M2-6**：收尾测试 + CI（可用 Testcontainers）
6. **M3 前端融合**（浮窗 + `/cs` 页 + 上下文六区块面板）—— 这才是最初「界面割裂」的最终解
7. 之后虚拟线程压测 / 中文嵌入对比（`bge-small-zh-v1.5`）/ CI
8. 全部完成后再推 GitHub（远程仓库尚未创建）

## 9. 文档索引

| 文件 | 内容 |
|---|---|
| `docs/DESIGN.md` | **设计规格 v1.1**（41 项决策 / 11 条风险，含独立评审修订记录） |
| `docs/PLAN.md` | M1 实现计划（11 任务，含基线方法） |
| `docs/UPGRADE-BOOT3.md` | **升级实战记录**（基线 / 迁移清单 / 9 个踩坑 / 验证证据 / 回退方式） |
| `docs/report.html` | **进度可视化报告**（里程碑 / 架构 / 技术栈） |
| `docs/screenshots/` | 商城前端截图（首页 / 搜索 / 后台登录，Chrome headless） |
| `README.md` | 项目说明 + 容器化实测 + 端口约定 |
| `docs/STATUS.md` | 本文件（状态快照） |
| `ops/mvn.sh` · `ops/smoke.sh` · `ops/init.sql` | 运维脚本与初始化数据 |
