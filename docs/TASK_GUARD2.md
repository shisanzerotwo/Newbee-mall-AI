# TASK_GUARD2：修 #8「该查不查」幻觉变体（意图门禁 + prompt 硬化）

> 交给 **ZCode** 执行。本卡自包含，不需要会话上下文。
> 项目：`D:\GitHub\xiangmu\newbee-mall-ai`
> 基线提交：`a78c23d`（工作区应为干净）

---

## 0. 环境须知（先读，能省大量时间）

- **Maven 必须走包装器**（本机无 Linux Maven）：
  - 有 bash：`cd /d/GitHub/xiangmu/newbee-mall-ai && bash ops/mvn.sh <args>`
  - 只有 PowerShell：`$env:JAVA_HOME='C:\Users\22421\.jdks\openjdk-25'; & 'D:\tools\apache-maven-3.9.16\bin\mvn.cmd' -f 'D:\GitHub\xiangmu\newbee-mall-ai\mall-backend\pom.xml' <args>`
- ⚠️ **跑任何测试前必须把 `.env` 变量导入当前进程**（Maven 不读 `.env`）。漏了会报
  `Access denied for user 'root'@'localhost' (using password: NO)`（本项目踩过）：
  - PowerShell：`cd 'D:\GitHub\xiangmu\newbee-mall-ai'; Get-Content -LiteralPath '.env' -Encoding UTF8 | ForEach-Object { if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim('"').Trim("'"), 'Process') } }`
  - bash：`set -a && . ./.env && set +a`
- ⚠️ **本机内存紧张**（15.7 GB 常只剩 1~3 GB），跑测试必带：
  `-DargLine="-Xmx700m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m -XX:CICompilerCount=2"`
  否则会以 `insufficient memory ... Chunk::new` 崩掉（**那不是测试失败**）
- 依赖服务：MySQL 3306、Redis 16379（容器）都在运行中
- ⚠️ **不许改 `.env`**：模型通道已配好（`agnes-2.5-flash` 直连，免费额度）

---

## 1. 问题（背景与证据）

项目已有一层「数据铁律门禁」（提交 `a78c23d`）：**回答里出现价格/库存数字特征、且本轮没调用任何工具** → 判定违规，自动重问一次。

实测 10 问回归时，第 8 题「**化妆品有哪些分类？**」暴露出**门禁抓不到的新变体**：

```
[1] passed=false  fullMs=6002   tools=[]  回答=您好！我是新蜂商城的客服小蜂～关于化妆品的…
[2] passed=true   fullMs=13214  tools=[searchByCategory, searchGoods]
[3] passed=false  fullMs=6921   tools=[]  回答=好的，我来帮您梳理一下化妆品的主要分类~ 一般…
```

**3 次复跑 1 过 2 挂**。失败形态是：**模型用自己的通用知识（"化妆品一般分为护肤/彩妆/香水…"）回答商城问题，完全不查库**。
回答里没有任何价格/库存数字 → 现有正则不命中 → 门禁放行。

这与 #4（编造"红米7 ¥899"）是**同族问题的不同表现**：都是"该查不查"。本质是**问题意图要求查商城数据，但工具轨迹为空**。

---

## 2. 要做的（方案已设计好，按此实现）

### 2.1 门禁扩展为「意图 + 内容」双判定

文件：`mall-backend/src/main/java/ltd/newbee/mall/service/agent/CsAgentService.java`

1. 新增常量 `MALL_INTENT_PATTERN`（关键词启发式，**宁漏不误伤**）：
   ```
   价格|售价|多少钱|库存|有货|在售|下架|分类|品类|订单|推荐|有什么|有哪些|型号|品牌
   ```
2. 新增方法：
   ```java
   static boolean needsMallData(String question)      // 问题是否在问商城数据
   static boolean violatesDataRule(String question, String answer, List<ToolCall> toolCalls)
   ```
   组合判定：**工具轨迹非空 → 直接放行**；否则「回答命中价格/库存特征」**或**「问题命中商城意图」→ 违规。
3. `enforceDataRule(...)` 改用 3 参版本（保留现有 2 参 `violatesDataRule(answer, toolCalls)` 不删，避免破坏已有 18 个单测）。
4. 触发日志与重问逻辑沿用现有实现（重问一次 → 仍违规则放行 + `log.error` 留痕，**绝不抛异常**）。

### 2.2 prompt 硬化（两处，`CS_PROMPT` 常量）

1. **新增两条**（放在【数据铁律】第 4、5 条）：
   - 禁止用**通用知识**回答商城问题：分类、在售、价格、库存、订单都必须来自工具结果。
     例：问"化妆品有哪些分类"必须调 `searchByCategory` 查本店真实分类，不能背"一般化妆品分为护肤/彩妆/香水…"
   - 禁止"**只说不做**"：不要说"我先帮您查一下"却不调用工具 —— 要么立刻调用工具，要么如实说明查不到。
2. **修正现有第 4 条**（这是 #8 的诱因之一）：
   - 原文：`4. 不确定的信息说"我帮您核实一下"，而不是猜测`
   - 问题：这句教模型"只承诺不执行"。改为：`4. 不确定的信息先调用工具核实；确实查不到的，如实说明，而不是猜测`

### 2.3 单元测试（Mock 模型，**不许打真实模型**）

文件：`mall-backend/src/test/java/ltd/newbee/mall/service/agent/CsAgentServiceTest.java`（追加）

- **阳性**：问题「化妆品有哪些分类？」+ 无工具调用 + 回答是通用知识 → 门禁触发（发生第二次模型调用）
- **阴性 1**：`你们支持退货吗？` + 无工具 → **不触发**（闲聊不得误伤）
- **阴性 2**：`今天天气怎么样？` + 无工具 → **不触发**
- **阴性 3**：任意商城问题 + **有**工具调用 → 不触发
- **直测 `needsMallData`**：对上面 10 个真实问题逐个断言命中/不命中（尤其 #9、#10 必须不命中）

---

## 3. 红线（违反即返工）

1. **不许 git commit / push**
2. 单测不许打真实模型（用现有 Mock 模式）
3. 门禁违规必须**放行 + 留痕**，不许抛异常（用户不能空手）
4. 不许改动已提交的 #6 词表逻辑、#8 退避逻辑
5. 不碰 `.env`；密钥不落盘
6. 文档/注释里的数字与结论必须来自实跑

---

## 4. 验收（**必须给证据：命令 + 真实输出**）

1. `mvn test -Dtest=CsAgentServiceTest` → 全绿（18 个旧 + 新增）并贴输出
2. `mvn test`（全量，带 argLine）→ 全绿（基线 172）
3. `bash ops/smoke.sh http://127.0.0.1:28091` → 16/16
4. **真实单题复跑 #8 三次**（需 `CS_ENABLE_REAL_MODEL_IT=1`，会打上游、烧免费额度，3 次即可）：
   `mvn test -Dtest=CsAgentTenQuestionIT -Dcs.it.questions=8`
   期望 **3/3 都调用工具**；若仍挂，**如实报告**（不许只报成功的）
5. 有精力再跑一次完整 10 问：期望 ≥9/10

---

## 5. 报告格式

1. 改了哪些文件（路径 + 行数）
2. 每项的验证证据（命令 + 真实输出摘要）
3. **未通过/未完成项与原因**（如实说，不要假装做完）
4. 发现的新问题
