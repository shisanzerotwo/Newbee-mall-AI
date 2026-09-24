# 文档索引与阅读地图

> **这个文件解决一个问题**：仓库里有 21 份 markdown，同一个人隔一周回来也会不知道先看哪份。
> 本文件只做两件事：**按「你要干什么」给出阅读顺序**，以及**把易打架的数字连同口径固定下来**（§6）。
>
> 维护约定：里程碑或数字变化时更新本文件；**任何数字必须带口径 + 日期**（这是本项目踩过最多的坑）。
> 最后核实：**2026-09-24**（HEAD `0a51930`）

---

## 1. 一句话 & 当前状态（实测值）

把 **newbee-mall（Java 电商）** 与 **AI 客服** 融为**一个 Spring Boot 应用** ——
客服不是 iframe 里的外部网站，而是商城内的**原生能力**（同进程、同栈、同前端）。

| 指标 | 值 | 口径 / 日期 |
|---|---|---|
| 提交 | **62** | `git log --oneline \| wc -l`，@`0a51930`（全部在 09-17 ~ 09-19 三天） |
| 默认测试 | **177 通过 / 0 失败 / 0 跳过**（46.7s） | `bash ops/mvn.sh test` 实测，**2026-09-24** |
| 真实模型 IT | 4 个文件（需显式 `-Dtest=` 调用，不进默认 `mvn test`） | `*IT.java`，另需 `CS_ENABLE_REAL_MODEL_IT` |
| 冒烟 | 16/16 | `ops/smoke.sh`，2026-09-19 |
| 10 问回归 | **10/10** | `CsAgentTenQuestionIT`，2026-09-19（历史 1/10 → 7/10 → 9/10 → 10/10） |
| 未决事项 | 3 项（见 §7） | — |

---

## 2. 按角色选读（四条路径，别按文件名顺序读）

| 你想干什么 | 按这个顺序读 |
|---|---|
| **A. 只想跑起来看看** | `USER_GUIDE.md` §1 启动 → §2 演示路径 → `DEMO.md` → 完 |
| **B. 要改代码** | `DEVELOPMENT.md`（环境/代码地图/约定/测试/调试手法/红线）→ `ARCHITECTURE.md` → 动手。**不用读 `TASK_*.md`**（那是历史留痕，见 §4） |
| **C. 要接手这个项目** | **`STATUS.md`（先读这个）** → 本文件 → `DEVELOPMENT.md` → `DESIGN.md` §12 风险册 |
| **D. 要问「为什么这么设计」** | `DESIGN.md`（41 项决策 + 实施期修订记录）→ 实测依据：`COMPARE-JAVA-PYTHON.md` / `RAG-EVAL.md` / `PERF-M3.md` → 升级踩坑：`UPGRADE-BOOT3.md` |

---

## 3. 文档全表（21 份 md）

### 3.1 ⭐ 入口类（3 份）

| 文件 | 什么时候读 | 备注 |
|---|---|---|
| **`STATUS.md`** | **接手第一步** | 状态快照：里程碑 / 关键决策 / 环境坑 / 下一步。会话压缩与 agent 交接靠它 |
| **`USER_GUIDE.md`** | 想用而不是想改 | 启动 / 端口 / 演示 / FAQ / 性能实测 / **边界说明（哪些没做）** |
| **`DEVELOPMENT.md`** | 要改代码 | 代码地图 / 两条产线数据流 / 核心约定 / 写测试 / **调试手法（探针范式 + 深坑速查表）** / 红线 |

### 3.2 规格与计划（3 份）

| 文件 | 内容 | 什么时候读 |
|---|---|---|
| `DESIGN.md`（57 KB，最大） | 设计规格 v1.1：**41 项决策 + R1~R12 风险登记册** + 实施期修订记录 | 想推翻某个设计前**必须**读 |
| `PLAN.md` | M1 实现计划（11 任务、升级前基线方法、DoD） | 只对 M1 升级这段历史有意义 |
| `MALL-UI-SPEC.md` | 商城前台 UI 规格（承自旧仓库的重设计） | 动前台样式时读 |

### 3.3 架构与实测（4 份）

| 文件 | 内容 | 什么时候读 |
|---|---|---|
| `ARCHITECTURE.md` | 架构地图（模块边界 / 请求链路 / 数据流） | 改代码前 |
| `COMPARE-JAVA-PYTHON.md` | Java↔Python 同题对比 + **10 问四轮演进全记录**（含归因：哪一层修好的） | 想知道幻觉治理怎么做的 |
| `RAG-EVAL.md` | RAG 评测口径与复跑步骤（**含「40% 是灌水指标」的发现与「可满足子集 8/9」新口径**） | 改检索/嵌入前，**先读这个再动手** |
| `PERF-M3.md` | 并发压测 + 虚拟线程 ON/OFF 对照 + **两版压测方法都是错的**的记录 | 想讲"性能优化"或想再做压测时 |

### 3.4 验证记录（2 份）

| 文件 | 内容 |
|---|---|
| `XSS-VERIFICATION.md` | 12 条 payload 行为级核查（阳性/阴性对照 + 两次独立复现）；对应测试 `CsXssBrowserIT` |
| `UPGRADE-BOOT3.md` | Boot 2.7.5→3.5.16 / Java 8→21 升级实录（基线、迁移清单、**9 个坑**、验证证据、回退方式） |

### 3.5 演示与可视化（3 份）

| 文件 | 内容 |
|---|---|
| `DEMO.md` | 一键演示路径（启动 → 提问 → 看什么） |
| `report.html` | 进度可视化报告（里程碑 / 架构 / 技术栈） |
| `screenshots/` | 商城前端截图（首页 / 搜索 / 后台登录） |

### 3.6 其它

| 文件 | 内容 |
|---|---|
| `.github/workflows/ci.yml` | 两个 job：`build-and-test`（mysql:9.7 + redis:8 容器）、`xss-browser-it`（真实 Chrome，无 Chrome 则 skip + `::warning::`） |
| `.github/modernize/java-upgrade/` | M1 升级期间的工具钩子（历史遗留，不影响构建） |
| `ops/mvn.sh` | **Maven 包装器**（WSL/Git Bash → Windows 侧 Maven，含 WSLENV 密钥转发白名单） |
| `ops/smoke.sh` | 冒烟回归（16 项，自动用 Windows `curl.exe`） |
| `ops/loadtest.sh` · `ops/fake_upstream.py` | 压测脚本 + 本地假上游（慢速流式，**支持回显收到的 messages**） |
| `ops/scan-secrets.sh` | **推送前固定动作**：全历史密钥扫描（秒级 / 不打印命中内容 / 退出码三态：0 干净、1 有命中、**2 脚本没跑成**） |
| `ops/init.sql` | 容器初始化数据 |

---

## 4. 历史任务卡（8 份）：**读完即止，不要当待办**

`TASK_M2-5.md` · `TASK_M3-A.md` · `TASK_M3-B.md` · `TASK_M3-C.md` · `TASK_M3-D.md` · `TASK_PROD.md` · `TASK_GUARD.md` · `TASK_GUARD2.md`

这 8 份是**交给其他 agent 执行的工单**（含环境须知 / 问题证据 / 范围 / 红线 / 验收标准），
**全部已执行完**。它们的现存价值是三种：

1. **决策留痕** —— 当时为什么这么切范围（例如"只做不需要产品决策的四项"）；
2. **红线来源** —— 每条卡里的红线（不可写 `Visible`、不可碰用户稿等）在 `DEVELOPMENT.md` 里有汇总；
3. **可复用模板** —— 下次派活给 agent 时照抄结构。

⚠️ 读法：把它们当**档案**，不要当 backlog。当前真正的待办只在 `STATUS.md` §8。

---

## 5. 代码地图（30 秒版）

```
mall-backend/src/main/java/ltd/newbee/mall/
├── controller/mall/Cs*.java      ← 客服入口（CsController / CsHealthController / CsPageController / CsSseWriter）
├── controller/{mall,admin,common}/…  ← 原商城域（未改动或仅小幅改动）
├── service/agent/                ← ⭐ 客服域，10 个类
│     CsAgentService（工具循环编排） · RagService + KnowledgeBuilder（RAG 建库/检索）
│     MallTools + MallToolInvoker（6 个只读 @Tool） · QaReviewer（质检 audit/gate）
│     CsStreamService（SSE） · CsChatMemoryService（记忆落库） · CsRateLimiter · CsUsageMeter
└── config/                        ← CsAgentConfig（ChatModel Bean，注意 @Qualifier("csChatModel")）/ CsRagConfig

静态资源：static/mall/js/cs-core.js（共用 SSE 客户端）· cs-widget.js（全站浮窗）
          static/mall/styles/cs.css · static/mall/css/cs-widget.css · templates/mall/cs.html
```

`src/test/java` 27 个文件：24 个 `*Test`（进默认 `mvn test`）+ **4 个 `*IT`**（需显式调用）。
名字里带 **`Guard`** 的是"结构守卫"测试（断言模板里真的注入了资源 / 真的没有危险写法），
它们的存在原因见 `DEVELOPMENT.md` §6 —— **静态断言全绿但页面不出现**这类事故踩过。

---

## 6. ⚠️ 三个数字口径（不写口径就会再打架）

本项目历史上出现过"提交数 54 vs 62""测试 163 / 166 / 177 三处不同"的假矛盾，
根因不是有人算错，而是**快照没写时间点**。所以：

| 数字 | 正确口径 | 常见误读 |
|---|---|---|
| **测试数** | `bash ops/mvn.sh test` 输出里的 `Tests run: N`。**默认不含 `*IT`**（真实模型/浏览器/DB 集成用例） | 拿某次里程碑的 N 当成"当前测试数"；把 `*IT` 算进默认套件 |
| **提交数** | `git log --oneline \| wc -l` | 拿文档里几天前的数字 |
| **冒烟 / 回归** | `ops/smoke.sh`（16 项）；`CsAgentTenQuestionIT`（10 问，**会打真实上游、烧额度**） | 把"跑了但静默跳过"当成通过（`CS_ENABLE_REAL_MODEL_IT` 不在白名单时会出现） |

**跑全量测试前的两个前提**（否则失败会被误判成代码回归）：
```bash
set -a && . ./.env && set +a        # Maven 不读 .env，漏了会看到一堆 DB Error
# Redis 需要带向量能力的 redis:8（宿主 16379）；本机 6379 是 3.0.504，不支持向量
bash ops/mvn.sh test -DargLine="-Xmx700m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m -XX:CICompilerCount=2"
```
> 最后那串 `-DargLine` 不是装饰：本机内存常年吃紧，**不限制会 JVM native OOM**，
> 而它的日志里**没有 Failures 汇总行**，极易被误读成"测试失败"。

---

## 7. 未决事项（需要产品/架构决策，不在代码层）

1. **限流仍是单机的**（令牌桶计数在内存）→ 多实例部署前需换共享存储（Redis）
2. **客服接口只有限流没有鉴权**（R9 部分）
3. **CI 的 `xss-browser-it` job 从未在真实 GitHub Actions 上跑过**（本机无法跑 Actions）

---

## 8. 相关资料在仓库外（知识库笔记）

项目文档是**事实来源**，知识库笔记是**方法论与话术来源**，两者分工明确：

| 位置 | 内容 |
|---|---|
| `D:/ObsidianChanku/ai学习/research/AI Agent学习/newbee-mall-ai 融合实战.md` | ⭐ 主笔记：与旧方案对比 / 7 节方法论 / 11 条关键决策 / 坑表 / **面试话术** |
| `D:/ObsidianChanku/ai学习/research/AI Agent学习/会话总结 2026-09-18~19.md` · `会话总结 2026-09-19.md` | 两篇会话复盘（时间线 + 失误分析） |
| `D:/ObsidianChanku/ai学习/research/AI Agent学习/踩坑记录.md` §19~§27 | 本项目的 9 条坑（含"中文 grep 打 GBK 日志 = 永远 0 命中"） |
| `D:/ObsidianChanku/ai学习/research/AI Agent学习/设计理由手册.md` #18 | 数据铁律「prompt 软约束 + 编排层硬门禁」两层 |
| `D:/ObsidianChanku/ai学习/research/AI Agent学习/Herdr 多角色编排方法论.md` §9 | 多 agent 分工的实证模式（谁执行 / 谁验证 / 怎么监工） |
| `D:/ObsidianChanku/ai学习/research/AI Agent学习/newbee-mall Agent 客服实战.md` | **前身**：Python 跨栈方案（旧 iframe 形态），对比着读 |
