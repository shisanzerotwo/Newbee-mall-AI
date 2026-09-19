# 开发文档（DEVELOPMENT）

> 面向**改这个项目的人**：怎么跑、怎么写、怎么测、怎么调、哪些线不能碰。
> 结构/数据流看 `ARCHITECTURE.md`；设计取舍看 `DESIGN.md`；当前状态看 `STATUS.md`。
> 本文只写**操作层面**的东西，且每条都在本机验证过。

---

## 1. 五分钟上手

```bash
# ① 环境（本机 bash 是 WSL，不是 Git Bash）
cd /mnt/d/GitHub/xiangmu/newbee-mall-ai

# ② 起依赖（MySQL + Redis 8 + 应用，三个容器）
# ⚠️ 本机 WSL 里**没有可用的 docker**（Docker Desktop 的 WSL 集成未启用）：执行会报
#    "could not be found in this WSL 2 distro"。改用 Windows 侧 docker（已在 PATH 里，实测可用）：
powershell.exe -NoProfile -Command "cd 'D:\GitHub\xiangmu\newbee-mall-ai'; docker compose up -d"
powershell.exe -NoProfile -Command "cd 'D:\GitHub\xiangmu\newbee-mall-ai'; docker compose ps"   # 期望：三个都 healthy

# ③ 或本地跑应用（改代码时用这个，热重启快）
set -a && . ./.env && set +a     # ⚠️ 必须 export：Maven 不读 .env
bash ops/mvn.sh spring-boot:run

# ④ 验证（16 项冒烟）
bash ops/smoke.sh http://127.0.0.1:28089
```

**三个最容易卡住的点**：

| 症状 | 原因 | 解 |
|---|---|---|
| `bash ops/mvn.sh` 报找不到 Java | 本机无 Linux Maven/JDK | 用 `ops/mvn.sh`（它转发到 `D:\tools\apache-maven-3.9.16` + JDK 25），**不要**直接 `mvn` |
| 测试连不上 DB / 报 `Access denied` | 没 export `.env` | `set -a && . ./.env && set +a` |
| RAG 报 `unknown command 'FT.CREATE'` | 连到了本机 6379（Redis 3.0.504） | 用容器 redis:8 的 **16379**（`CS_RAG_REDIS_PORT=16379`） |

---

## 2. 代码地图（只列你要动的地方）

```
mall-backend/src/main/java/ltd/newbee/mall/
├─ config/
│  ├─ CsAgentConfig.java      # 模型 Bean（ChatModel / StreamingChatModel）
│  ├─ CsRagConfig.java        # 嵌入模型 + Redis 向量库（⭐ 嵌入模型可切换，见 §5.3）
│  └─ NeeBeeMallWebMvcConfigurer.java
├─ controller/
│  ├─ mall/CsController.java  # ⭐ SSE 入口 + 限流（改接口先看这里的类注释）
│  ├─ mall/CsSseWriter.java   # ⭐ SSE 事件的唯一出口（complete 时机由它把关）
│  ├─ mall/CsPageController.java
│  └─ common/NewBeeMallExceptionHandler.java   # ⚠️ 全局异常处理，对 SSE 有短路
├─ service/agent/             # ⭐ 客服全部逻辑都在这
│  ├─ CsAgentService.java     # 非流式编排（工具循环 + 质检）
│  ├─ CsStreamService.java    # 流式编排（SSE 用这条）
│  ├─ MallTools.java          # 6 个只读 @Tool
│  ├─ MallToolInvoker.java    # 工具分发（两张表共用一份 switch，防漂移）
│  ├─ RagService.java         # 混合检索（关键词 + 向量 → RRF）
│  ├─ KnowledgeBuilder.java   # ⭐ 建库（含语料红线清洗）
│  ├─ QaReviewer.java         # 质检（audit/gate）
│  ├─ CsChatMemoryService.java# 会话记忆（异步落库）
│  ├─ CsRateLimiter.java      # 限流（令牌桶 + in-flight）
│  └─ CsUsageMeter.java       # 用量记账
└─ resources/
   ├─ static/mall/js/cs-core.js    # ⭐ 前端共用核心（SSE 客户端 + 转义 + 渲染）
   ├─ static/mall/js/cs-widget.js  # 浮窗
   ├─ templates/mall/cs.html       # /cs 完整页
   └─ templates/mall/footer.html   # ⚠️ 全站共用，浮窗资源注入在这（必须在 fragment 内）
```

**两条产线**（互不影响的改动路径）：

| 改什么 | 动哪里 | 验证 |
|---|---|---|
| 商城功能 | `controller/mall/*`、`service/*`、`templates/mall/*` | `bash ops/smoke.sh` 必须 16/16 |
| 客服能力 | `service/agent/*`、`config/Cs*`、`static/mall/js/cs-*.js` | 跑对应测试 + 真机 SSE |

---

## 3. 核心约定（不遵守会出事的）

### 3.1 数据铁律
**价格 / 库存 / 订单状态一律以工具调用结果为准**，RAG 只作语义参考。
结构性保障是**语料红线**：`KnowledgeBuilder` 写入向量库的 chunk **不含**这几个字段。
→ **不要**为了"让回答更丰富"把价格塞进语料。

### 3.2 SSE 时序：`done` 不是终止事件
质检默认 **audit**（异步旁路）→ `done` **先**发、`review` **后**到。
**绝不能在发完 `done` 后立即 `complete()`**（否则 `review` 到达时 `send()` 抛 `IllegalStateException`）。
终止条件 = 收到 `review` 或连接关闭 + **3s 兜底**。这些都由 `CsSseWriter` 统一把关 —— **别绕开它直接 `emitter.send`**。

### 3.3 会话维度：不要用 `sessionId`
本项目**未启用 Spring Session**，session 是 Tomcat 内存态、重启即变 →
用它则「记忆跨重启保留」在匿名场景**永远不可能满足**。
用**前端自持的 `conversationId`**（localStorage）。限流同理。

### 3.4 注入 `ChatModel` 必须带 `@Qualifier("csChatModel")`
项目里有多个模型 Bean（编排 / 质检 / 流式），按类型注入会歧义。

### 3.5 XSS：模型文本一律走 `textContent`（**不是** `escapeHtml`）
两条客服渲染路径（`cs-core.js` / `cs-widget.js`）的实际防线是 **`createElement` + `textContent`**：
不解析 HTML，从根上不存在“忘了转义”。`cs-core.js` 仍导出 `Cs.escapeHtml`（覆盖 `& < > " '` 五个字符，
**单引号也要转**）作为备用工具，但**当前没有任何调用点** —— 别以为改了它会影响防线。
**禁止**裸 `innerHTML` 拼接模型输出。商品卡片数据**从 `tool` 事件解析**，不从 LLM 文本解析（防幻觉）。

> 该结论来自**行为级核查**（不是读代码猜的）：`docs/XSS-VERIFICATION.md` ——
> 12 条 payload × 2 条路径 + 阳性对照 + 阴性对照，已固化为 `CsXssBrowserIT`。

---

## 4. 怎么写测试

| 类型 | 放哪 | 跑法 | 注意 |
|---|---|---|---|
| 单元测试 | `src/test/...`（`*Test`） | `mvn test`（默认跑） | **禁止依赖真实上游模型** |
| 需要 DB/Redis 的集成测试 | 同上 | 同上 | 需要 MySQL + redis:8 |
| **真实模型端到端** | `*IT`（如 `CsAgentRealCallIT`） | 手动 `mvn test -Dtest=XxxIT` | 会打上游，**不要塞进默认 test**（上游 429 会让 CI 变随机红灯） |
| 评测类（只输出数字） | `*Test` 但**不硬断言阈值** | 同上 | 如 `RagChineseQualityTest`：打印命中率供对比，断言只保证"链路没整体坏" |

**写法示例**：

```bash
set -a && . ./.env && set +a
bash ops/mvn.sh test -Dtest='CsRateLimiterTest,CsControllerLimitTest'
bash ops/mvn.sh test                      # 全量（当前 163 个）
```

### 4.1 ⭐ 写断言的两条铁律（本项目血泪）

1. **不许断言恒真的东西**。例如"桶数 > 0"这种，把被测逻辑删掉也能过 —— 那是假绿。
   → 断言**真正的不变量**（如"桶数有界"），且**必须做阴性对照**验证它真能红。
2. **阴性对照是标配**：把 bug 放回去，看测试是否变红。**没做过阴性对照的断言不算验证过。**

```bash
# 例：临时禁用修复 → 期望测试变红 → 立即恢复
cp F.java /tmp/F.bak
sed -i 's/if (bucketCount > MAX)/if (false)/' F.java
bash ops/mvn.sh test -Dtest=XxxTest    # 必须 FAIL
cp /tmp/F.bak F.java && grep -c 'if (bucketCount > MAX)' F.java   # 确认恢复（=1）
```

> ⚠️ **恢复后一定要 grep 确认**。我踩过一次：阴性对照没干净恢复，把源码留在了损坏状态。

### 4.2 全量测试跑到一半 JVM 崩了？（本机内存坑，2026-09-19 实测）

症状（不是 assertion 失败，日志里也**没有** `Tests run: ... Failures:` 汇总行）：

```text
# There is insufficient memory for the Java Runtime Environment to continue.
# Native memory allocation (malloc) failed to allocate 1449368 bytes. Error detail: Chunk::new
```

同时在 `mall-backend/` 留下 `hs_err_pid*.log` / `replay_pid*.log`（已被 `.gitignore` 的 `*.log` 盖住）。

**这不是代码问题，是本机内存不足**：总内存 15.7 GB，实测**空闲 1.9 GB 时必崩**
（ZCode 多进程、Docker/WSL、浏览器常吃掉十几 GB），崩的是 JIT 编译器的 native 内存分配。

处置顺序：

1. 先看空闲内存：`Get-CimInstance Win32_OperatingSystem` 的 `FreePhysicalMemory`
2. 给测试 JVM 让出 native 空间（实测一条命令跑完 163 个）：
   ```bash
   bash ops/mvn.sh test -DargLine="-Xmx700m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m -XX:CICompilerCount=2"
   ```
3. 清掉崩溃日志：`rm -f mall-backend/hs_err_pid*.log mall-backend/replay_pid*.log`

> ⚠️ **别把这种崩溃误判成“某个测试失败”** —— 看到 `insufficient memory` 就该去查内存，
> 而不是去查测试代码。（本轮实测：不限制内存连崩 2 次，限制后 45s 跑完 163/163。）

---

## 5. 常见改动怎么做

### 5.1 加一个工具（`@Tool`）
1. 在 `MallTools` 加方法（**只读**），参数用基本类型
2. 在 `MallToolInvoker` 的 `switch` 里登记（**两张表必须同步**，不然 `ok` 判定会漂）
3. 加单测（Mock Mapper，不打模型）
4. 真机验证：`bash ops/smoke.sh` + 走一次 SSE 看 `tool` 事件

### 5.2 改 SSE 事件协议
1. 改 `CsSseWriter` 的发送方法（**唯一出口**）
2. 前端 `cs-core.js` 的解析器同步
3. 更新 `DESIGN.md` §4.2 的协议表
4. **加一条端到端测试**（`@SpringBootTest` + 真发 HTTP，断言事件顺序）

### 5.3 换嵌入模型
```bash
# ① 先停应用（⚠️ 否则它继续往已删索引写 → hash_indexing_failures 爆表，而检索居然还能返回结果，极易误判）
# ② 删索引
docker exec newbee-mall-redis redis-cli FT.DROPINDEX goods_kb
# ③ 启动应用（KnowledgeBuilder 按新模型维度重建）
# ④ 验证：dim/num_docs/hash_indexing_failures 三项
docker exec newbee-mall-redis redis-cli FT.INFO goods_kb
```
选模型：`export CS_RAG_EMBEDDING_MODEL=bge-zh`（默认，中文 512 维）或 `minilm`（英文 384 维）。
**判据**：`hash_indexing_failures` 必须为 **0**（只看 `num_docs` 会被"部分失败"骗过）。

### 5.4 调限流参数
```
cs.limit.requests-per-minute=10            # 会话维度（conversationId / userId）
cs.limit.ip-requests-per-minute=60         # IP 维度（防轮换 conversationId 绕过）
cs.limit.max-inflight-per-conversation=1   # 单会话同时只允许 1 个在途
cs.limit.max-question-chars=500            # 单次请求正文上限
```
**验证限流必须让请求速率真的超过配额**：串行发 70 次可能因令牌桶持续补充而全部通过，
要**并发快发**才压得住（实测：串行 70 次全过；并发 80 次 → 200=29/被拒=51）。

---

## 6. 调试手法

### 6.1 看日志
```bash
tail -f mall-backend/logs/newbee-mall.log
grep -a "流式问答失败\|模型调用失败\|被拒绝" mall-backend/logs/newbee-mall.log | tail
```
> ⚠️ **日志文件位置取决于进程 cwd**（配置里是相对路径 `logs/newbee-mall.log`）。
> 用 `java -jar` 从别的目录启动，日志会落到别处 —— 启动时**指定 WorkingDirectory**。

### 6.2 真机测 SSE（本机 WSL 调不动 localhost，必须用 Windows 侧 curl）
```bash
CURL="/mnt/c/Windows/System32/curl.exe"
"$CURL" -s -N -m 60 -o D:/tmp/out.txt -w 'HTTP=%{http_code} time=%{time_total}s\n' \
  -X POST 'http://127.0.0.1:28091/api/cs/chat' \
  -H 'Content-Type: application/json' --data-binary '@D:/tmp/body.json'
```
> ⚠️ Windows curl **读不了 WSL 路径**（要写 `D:/tmp/...`）；
> ⚠️ **SSE 是异步的**：`-o /dev/null` 只测到响应头（会显示 0.03s 的假快），
> 要**读完整流到文件**才是真实耗时。

### 6.3 报错时先查这几处
| 现象 | 先看 |
|---|---|
| 429 | 是被**会话维度**还是 **IP 维度**拒的（日志里有 `key=` 前缀 `c:`/`ip:`），还是 **in-flight**（消息里会写"上一条还在回答中"） |
| 模型调用失败 | `cs.model.name` 是不是**真 model id**（不是显示名）；上游是否限流（响应体里常有 `reset after Ns`） |
| 检索为空 | `FT.INFO goods_kb` 的 `num_docs` / `hash_indexing_failures` |
| 浮窗不出现 | 渲染后的 HTML 里有没有 `cs-widget.js`（**文件里有 ≠ 渲染时会带上**） |

### 6.4 ⚠️ 先确认"你的观察手段有效"
本项目最大的坑不是代码，是**观察手段本身失效**（详见知识库笔记 [[newbee-mall-ai 融合实战]]）。
改代码前问自己：
- 我的 grep 模式会不会漏（如搜 `Retry` 而方法名是 `Retries`）？
- 我的断言会不会恒真（删掉被测代码还过吗）？
- 我的测量口径测的是我想测的东西吗（`time_total` 对 SSE 有效吗）？
- 工具真的存在吗（`command -v unzip`）？

---

## 7. 🔴 红线（改代码前必读）

1. **不许把依赖真实模型调用的用例放进默认 `mvn test`**（上游限流会让 CI 随机红灯）
2. **不许绕过 `CsSseWriter` 直接 `emitter.send()`**（complete 时机与"只 complete 一次"由它守）
3. **不许把价格/库存写进 RAG 语料**（违反数据铁律）
4. **不许用 `sessionId` 做会话/限流维度**（未启用 Spring Session）
5. **不许在 `footer.html` 的 fragment 外注入资源**（会被 Thymeleaf 静默丢弃）
6. **改 `footer.html`/`header.html` 后必须跑冒烟 16/16**（全站共用）
7. **不许把密钥写进任何文件**；文档里的示例命令用占位符（`<你的MySQL密码>`）
8. **推送前必须做全历史密钥扫描**（见 §8）

---

## 8. 提交与推送

```bash
# 提交（本地）
git add -A && git commit -m "..."

# ⚠️ 推送必须走 Windows 侧 git（WSL 连不上 github.com:443）
powershell.exe -NoProfile -Command "git -C 'D:\GitHub\xiangmu\newbee-mall-ai' push origin master"

# 核对远程 SHA 与本地一致
git rev-parse HEAD
powershell.exe -NoProfile -Command "git -C 'D:\GitHub\xiangmu\newbee-mall-ai' ls-remote origin master"

# ⭐ 推送前：全历史密钥扫描（本项目真踩过 —— 文档里的示例命令带明文密码，被 19 个历史版本继承）
bash ops/scan-secrets.sh        # 一条命令扫全历史，退出码 0 = 未发现疑似泄漏

# 它的实现（自己重写时的要点）：
#   git rev-list --objects --all | awk '{print $1}' | git cat-file --batch --buffer > tmp
#   → 一次导出全部对象再按模式 grep。**别**逐对象 cat-file（1221 个对象 × 5 模式 要等数分钟）。
# 发现命中时：git filter-branch --tree-filter '...' -- --all
#            + 删 refs/original + reflog expire --expire=now --all + gc --prune=now
#            + 复扫确认 0
#
# ⚠️ 命中 ≠ 泄漏：本仓库当前有 21 处**已知无害**命中（占位符 <你的MySQL密码>、
#    省略号、.env.example 空值、以及描述这段历史的 commit message）。脚本已内置白名单过滤，
#    但白名单是启发式的 —— 真值判断必须人工。
```

> **本机 GitHub 通路的特殊性**：hosts 被 Steam++ 接管（66 条 github 域名 → 127.0.0.1）。
> 它代理了 `github.com`（git 可用），但**没代理 `api.github.com`** →
> `gh repo create` 这类 API 操作不可用，**新建仓库只能在浏览器做**。

---

## 9. 本机环境速查

| 项 | 值 |
|---|---|
| 项目 | `D:\GitHub\xiangmu\newbee-mall-ai`（WSL：`/mnt/d/GitHub/xiangmu/newbee-mall-ai`） |
| Maven | **必须** `bash ops/mvn.sh`（Windows 侧 `D:\tools\apache-maven-3.9.16`） |
| JDK | `C:\Users\22421\.jdks\openjdk-25`（编译 `--release 21`） |
| MySQL | 本机 3306（`E:\mysql-9.7.1`）；容器内网 |
| Redis | 本机 6379（3.0.504，**无向量**）；**RAG 用容器 16379**（redis:8） |
| Docker | ⚠️ **WSL 里不可用**（集成未启用，报 "could not be found in this WSL 2 distro"）→ 走 Windows 侧 `docker`（已在 PATH：29.8.0 + Compose v5.5.1）；实测三容器 `newbee-mall-{mysql,redis,app}` 均 healthy |
| 应用端口 | 本地 28091（我常用）/ 28089（默认）；容器 28090 |
| 容器名 | `newbee-mall-mysql` / `newbee-mall-redis` / `newbee-mall-app` |
| 日志 | `mall-backend/logs/newbee-mall.log`（**相对进程 cwd**） |

**运维脚本**：

| 脚本 | 用途 |
|---|---|
| `ops/mvn.sh` | Maven 包装器（WSL→Windows，含 `WSLENV` 白名单转发） |
| `ops/smoke.sh` | 16 项冒烟（自动用 Windows curl.exe） |
| `ops/loadtest.sh` | 并发压测（**刻意避开模型链路**，免被上游 429 污染） |
| `ops/fake_upstream.py` | 本地假上游（慢速流式，用于压 SSE 长连接） |
| `ops/init.sql` | 建表 + 初始化数据（含 `cs_chat_memory`） |
