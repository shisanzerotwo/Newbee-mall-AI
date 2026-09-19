# TASK 数据铁律门禁 + #6 断言反转 + #8 退避重试（交给 codex 执行）

> **背景**：10 问回归暴露三类问题，编排者（pi）已出方案，你按卡执行。
> **执行者**：codex（herdr `doer`，pane wG:p7）
> **复核者**：claude（herdr `reviewer`，pane wG:p2）—— 写者≠验证者，不得自查代替
> **验证者**：编排者 pi（独立复跑 + 抽样核对）
> 任务来源：用户已批准「A2 编排层门禁 + #6 断言反转 + #8 退避重试」的组合方案。

---

## 0. 环境须知

- 项目根：`D:\GitHub\xiangmu\newbee-mall-ai`；你的 shell 是 **Git Bash**（`/d/...`），不是 WSL
- Maven 必须用 `bash ops/mvn.sh`（从项目根执行）；测试前 `set -a && . ./.env && set +a`
- 本机内存紧张：跑测试必带 `-DargLine="-Xmx700m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m -XX:CICompilerCount=2"`
- **不许 git commit / push**（编排者负责）
- 单测**不许打真实模型**（用现有 Mock 模式；`CsAgentServiceTest` 是现成范例）
- 工作区当前干净（我已撤回此前半成品编辑），从零开始

---

## 1. 任务 A：数据铁律门禁（A2，核心）

**文件**：`mall-backend/src/main/java/ltd/newbee/mall/service/agent/CsAgentService.java`

背景：#4 实测证明模型会**概率性跳过工具、凭幻觉报价格/库存**（编造了库里不存在的红米7/iPhone 12 Pro）。
prompt 已修（模糊策略不再豁免数据铁律），但 prompt 只是软约束 —— 需要编排层**代码强制**。

### 设计（编排者已定，按此实现）

1. **开关**：`@Value("${cs.agent.data-rule-guard:true}") boolean dataRuleGuard`（默认 true）
2. **检测方法** `violatesDataRule(String answer, List<ToolCall> toolCalls)`：
   - 规则：回答文本命中「价格/库存特征」**且** `toolCalls.isEmpty()`
   - 价格/库存特征正则（建议）：`[¥￥]\s*\d+|\d+(?:\.\d+)?\s*(?:元|台|件)`（数字紧邻货币/量词）
   - ⚠️ 注意误伤面：日期（2024 年）、订单号（20240513…）不算价格 —— 正则要求"数字**紧贴**元/台/件/¥"，`2024年`不命中 ✅
   - 方法是 static 纯函数（不依赖 Spring），便于单测直测
3. **answer() 集成**（generate 之后、gate/audit 分流之前）：
   - `dataRuleGuard && violatesDataRule(...)` → 触发门禁：
     - `log.warn` 触发留痕
     - 调 `regenerateWithToolReminder(generated, question)` 重问一次
     - 重问结果**仍违规** → 放行 + `log.error` 留痕（用户不能空手）
     - 返回重问结果（工具轨迹保留原样，即空——那是事实）
4. **新增方法** `regenerateWithToolReminder(Generated generated, String question)`：
   - 基于原 messages 追加一条 UserMessage，提醒要点：
     「你的回答中出现了价格/库存信息，但本轮没有调用任何工具。数据铁律要求：商城数据必须先调用工具获取。
     请先调用合适的工具（searchGoods / getGoodsDetail / checkStock 等）核实，再基于工具结果重新回答。
     不要道歉式开头。」
   - 之后同样走 `generate` 的工具循环逻辑（或复用 chat 循环），**允许它调工具**
5. 注意：`retryOnce`（质检打回用）已存在，但语义不同 —— 新方法不要复用它的"质检反馈"话术

### 单元测试（`CsAgentServiceTest` 追加，Mock 模型）

- **阳性**：Mock 第一次回答「红米7 ¥899/435台」无 tool_calls → 断言门禁触发（第二次调用发生）；
  Mock 第二次带工具调用 `getGoodsDetail` + 正常回答 → 最终 `CsAnswer` 的 answer 来自第二次
- **阴性 1**：Mock 回答含价格但带 tool_calls → 不触发（不重问）
- **阴性 2**：Mock 回答纯话术（退货政策，无价格特征）无工具 → 不触发
- **开关**：`cs.agent.data-rule-guard=false` 时即使阳性场景也不重问
- 直测 `violatesDataRule`：`"9 元"` 命中、`"2024 年"` 不命中、`"订单 2024051312345678"` 不命中、`"435 台"` 命中、空回答不命中

### 参考背景（为什么这样设计）

- Python 版 db_tools 上下架写反的教训：结构性保障 > prompt 兜底（语料红线同思路）
- #4 的实测：prompt 双指令冲突 → 概率性跳过工具（3 次验证才稳定）→ 需要出口拦截
- `claimsOrderStatus` 类似思路：行为红线用代码判，不靠模型自觉

## 2. 任务 B：#6 断言反转（测试设计缺陷修复）

**文件**：`mall-backend/src/test/java/ltd/newbee/mall/service/agent/CsAgentTenQuestionIT.java`

- #6 的 `answerMustContainAny` 词表扩充：`"没有查到", "未查到", "没查到", "查无", "不存在", "没有这个订单", "没有该订单", "无法确认", "需要核实", "帮您核实"`（保留原有 6 个）
- javadoc 注明**断言分层**：正向词表 = 软检查（表达"查不到"的意图）；**硬红线** = 已有的 `claimsOrderStatus`（不得编造订单状态）
- 自测：把第一轮 #6 真实回答"我帮您查了一下，订单号 2024051312345678 **没有查到**相关信息呢"代入新词表 → 必须命中

## 3. 任务 C：#8 题间退避 + 429 重试

**文件**：同上 IT

- `runCase` 循环之间 `Thread.sleep(2500)`（免费速率窗口自述 3s，留余量；最后一题后不必 sleep）
- `runCase` 捕获含 `RateLimitException` / `429` 的异常 → 退避 4s 重试一次（4s 来自项目对上游重置窗口的实测）
- 重试仍失败 → 如实记录失败（不粉饰）
- 注意：`@Timeout(value = 30, MINUTES)` 总预算是否够 → 10 题 × (回答 ~8s + 退避 2.5s + 可能重试) ≈ 3~4 分钟 ✅ 够

## 4. 红线（违反即返工）

1. **不许 git commit / push**
2. 单测不许打真实模型（门禁测试用 Mock）
3. 不许改既有测试语义（#6 是修断言设计缺陷，需在报告中单独说明改动理由）
4. 不碰 `.env`；密钥不落盘
5. 门禁违规时**放行不抛异常**（用户不能空手）；拦截与放行都要有日志
6. 文档里的事实必须核实；无法核实的标"未验证"

## 5. 交付与报告

- 改动文件列表（路径 + 行数）
- 每项的验证证据：**命令 + 真实输出**（单测全绿 + 阳性/阴性对照结果）
- 未完成项及原因（如实）
- 发现的新问题
