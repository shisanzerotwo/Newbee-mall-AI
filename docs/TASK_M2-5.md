# TASK M2-5：SSE 流式接口 + 会话记忆落库

> **依据**：`docs/DESIGN.md` §4.2（SSE 事件协议 + 时序约定）、§7.4（会话记忆）、§7.3（编排与质检）
> **前置**：M2-4 已完成（`d0130af`；53 个测试全绿；真实模型端到端跑通）
> **范围**：只做**后端**；前端浮窗 / `/cs` 页 / 上下文面板属 M3

---

## 1. 目标

把客服从「一次性返回整段回答」升级为「**逐字流式推送** + **会话记忆持久化**」，并对齐 DESIGN §4.2 的事件协议。

## 2. 交付物

| # | 文件 | 内容 |
|---|---|---|
| 1 | `config/CsAgentConfig.java`（改） | 新增 `csStreamingChatModel` Bean（`OpenAiStreamingChatModel` + OkHttp + timeout） |
| 2 | `service/agent/CsStreamService.java`（新） | 流式编排：RAG → 流式工具循环 → delta 推送 → 质检（audit/gate） |
| 3 | `controller/mall/CsController.java`（新） | `POST /api/cs/chat`（SSE）；`GET /api/cs/health` 可选 |
| 4 | `model/CsChatMemory.java` + `dao/CsChatMemoryMapper.java`（新） | 记忆表实体 + Mapper |
| 5 | `service/agent/CsChatMemoryService.java`（新） | 记忆读写（登录用 `userId`／匿名用 `conversationId`） |
| 6 | `ops/init.sql`（改） | `cs_chat_memory` 建表（含索引） |
| 7 | 测试 | 事件顺序 / **时序红线** / 记忆读写 / 上限策略 |

---

## 3. 🔴 红线（违反即返工）

### 3.1 SSE 时序：`done` **不是**终止事件（DESIGN §4.2 明确点名"M2-5 必须照此实现"）

```
event: stage    data: {"stage":"检索知识库","elapsed":0.31}
event: tool     data: {"name":"check_stock","args":{"goodsId":10003},"ms":42,"ok":true}
event: delta    data: {"text":"「无印良品高保湿化妆水」"}
event: review   data: {"qualified":true,"reason":"价格与工具结果一致"}
event: done     data: {"totalMs":4210,"firstTokenMs":1180}
event: error    data: {"message":"模型调用失败，请稍后再试"}
```

- **`audit`（默认）**：`done` **先**发，`review` **后**到 → **绝不能在发完 `done` 后立即 `SseEmitter.complete()`**
  （否则异步 `review` 到达时 `send()` 抛 `IllegalStateException`）
- **`gate`**：质检同步完成 → `review` **必然先于** `done`
- **终止条件**：收到 `review` 即结束；**兜底超时 3s**（质检未按时返回 → 发
  `review{"qualified":null,"reason":"质检超时"}` 再 complete）
- 必须写一条**测试**专门盯这条：audit 模式下 `done` 之后的 `review` 仍能送达，且只 complete 一次

### 3.2 数据铁律
价格 / 库存 / 订单状态**一律以工具结果为准**；RAG 只作语义参考（该红线由 §7.1 的语料红线结构性保障，别靠 prompt 兜底）。

### 3.3 会话维度：**不要用 `sessionId`**（DESIGN §7.4）
本方案**未启用 Spring Session**，session 是 Tomcat 内存态、重启即变 → 用它的话
DoD「记忆跨刷新/重启保留」在匿名场景**永远不可能满足**。
→ **登录用户用 `userId`；未登录用前端持久化的 `conversationId`（localStorage 生成、随请求携带）**

### 3.4 记忆写入**不得阻塞**流式响应
每轮对话结束后**异步**落库。流式响应已完成还被 DB 拖住是本末倒置。

---

## 4. 已核实的技术事实（别再重复验证，也别推翻）

| 事实 | 证据 |
|---|---|
| **`OpenAiStreamingChatModel` 的 Builder 没有 `maxRetries`**（流式模型**无内置重试**） | javap -c 全字节码（516 行）含 `etry` 的行数 = **0**；对照 `OpenAiChatModel` = 2 |
| 非流式 `OpenAiChatModel` 内置重试默认 **2** 次，`CsAgentConfig` 已设 `.maxRetries(0)` 关闭 | `OpenAiRetryBehaviorTest` + `CsAgentWiringRetryTest`（阴性对照：删掉即红） |
| 流式回调接口 | `StreamingChatResponseHandler`：`onPartialResponse(String)` / `onPartialToolCall` / `onCompleteResponse(ChatResponse)` / `onError(Throwable)` |
| 上游 429 的重置窗口 | 响应体自述 `reset after 3s`，**每次尝试都会刷新该窗口** → 退避必须 > 3s（现有实现 4s×n） |

> ⚠️ 因为流式**没有**内置重试，重试只能自己做。**注意**：一旦已经推送过 `delta`，
> 中途失败**不能**重试（文本已到用户眼前，重试会造成重复/错乱）—— 重试只对「**首字之前**」的失败有意义。

---

## 5. 顺带必须处置的失败预算（claude 终审要求，记在 STATUS §4 决策 11）

实测：免费额度受限时单次 `answer()` **24~80s**，而 DESIGN §2#14 承诺「完整回答 **<8s**」——**差一个数量级**；
现 `maxModelRetries=3` + `timeout=60s` 的最坏预算还会吃掉 M2-5 的 120s SSE 上限大半。

**处置**：
- `cs.model.timeout-seconds`：60 → **15**
- `cs.agent.max-model-retries`：3 → **2**
- 失败时给**可读话术**（如「当前咨询较多，请稍后再试」），不要抛原始异常文本给前端
- 把「免费额度下实测 24~80s」写入 `docs/DESIGN.md` §12 风险册

---

## 6. 验收标准

1. `bash ops/mvn.sh test` 全绿（新增用例计入）
2. **真实模型**跑通一次流式问答：能看到 `delta` 逐段到达（不是最后一次性喷出），且 `firstTokenMs` 有意义
3. **时序红线测试**：audit 模式下 `done` 之后 `review` 仍送达；complete 只发生一次
4. 会话记忆：同一 `conversationId` 连问两轮，第二轮能带上第一轮上下文（落库可查）
5. 重启应用后，用同一 `conversationId` 仍能取回历史（证明不依赖 session）
6. 冒烟 `bash ops/smoke.sh` 仍 16/16（不能把商城功能改坏）

## 7. 不做的事（范围边界）

- **不做**前端浮窗 / `/cs` 页面 / 上下文六区块面板（M3）
- **不做** SSE 鉴权与限流（M3，DESIGN §7.2）
- **不动** `MallTools` / `RagService` / `KnowledgeBuilder` / `QaReviewer` 的既有语义
- **不引入** Spring Session、`@AiService`、`langchain4j-spring-boot-starter`
