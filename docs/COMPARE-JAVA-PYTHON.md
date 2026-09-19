# Java vs Python 客服同题对比

> 对应 DoD：固定 10 问回归集 + Java/Python 同题对比表（见 [DESIGN.md](DESIGN.md) §10.1）。
> Java 回归集：`mall-backend/src/test/java/ltd/newbee/mall/service/agent/CsAgentTenQuestionIT.java`。
> Python 基准源码：`D:/GitHub/xiangmu/ai开发/12_agent_cs/test_questions.py`。
> 本轮只做源码层面和已有实测结果的对照；Python 旧凭证失效，不在本文中编造复跑答案。

## 1. 对照口径

| 项 | Java | Python |
|---|---|---|
| 固定问题来源 | 复制 `test_questions.py` 的 10 问 | `test_questions.py` 原文 |
| 测试入口 | `CsAgentTenQuestionIT`，命名以 `IT` 结尾，手动运行、不进默认 CI | `test_questions.py`，真实模型脚本 |
| 客观判定 | 答案非空；需要数据的问题必须调用约定工具；价格/库存回答必须出现工具结果中的数字；不存在订单不得声称状态；无关问题不得调用工具 | 原脚本仅判定“回答非空且长度 >20”；没有工具轨迹和数字一致性断言 |
| 运行方式 | `set -a && . ./.env && set +a && bash ops/mvn.sh test -Dtest=CsAgentTenQuestionIT` | `& 'D:/GitHub/xiangmu/ai开发/venv/Scripts/python.exe' -X utf8 'D:/GitHub/xiangmu/ai开发/12_agent_cs/test_questions.py'` |

## 2. 10 问同题对照表

本轮 Java 实测结果来自 `mall-backend/target/cs-regression/java-results.tsv`；该文件由
`CsAgentTenQuestionIT` 运行时生成。本轮 Python 未取得答案：旧 `.env` 直连返回
`401 This token status is unavailable`，因此 Python 列全部标注为未能复跑。

| # | Python 原问题（原文） | 该题想验证什么（源码注释） | Java 对应测试与判定 | Java 答案（本轮实测） | Python 答案（本轮实测） | 是否一致 | 差异原因 |
|---:|---|---|---|---|---|---|---|
| 1 | `无印良品的笔记本多少钱？` | 搜索 + 详情；已下架要如实告知 | 期望调用 `searchGoods` 或 `getGoodsDetail`；回答须含工具结果中的数字 | 未取得；`TimeoutException: timeout`，`fullMs=41012` | 未能复跑（401 凭证失效） | 无法判断 | Java 上游超时；Python 凭证失效 |
| 2 | `无印良品的化妆水有货吗？` | 库存查询；注释给出 10003 在售、库存 1000 的预期场景 | 期望调用 `checkStock`；回答须含工具结果中的数字 | 未取得；`TimeoutException: Read timed out`，`fullMs=65632` | 未能复跑（401 凭证失效） | 无法判断 | Java 上游超时；Python 凭证失效 |
| 3 | `MUJI 化妆盒卖多少钱？` | 搜索 + 详情；注释给出 10019 在售、30 元的预期场景 | 期望调用 `searchGoods` 或 `getGoodsDetail`；回答须含工具结果中的数字 | 未取得；`TimeoutException: Read timed out`，`fullMs=63704` | 未能复跑（401 凭证失效） | 无法判断 | Java 上游超时；Python 凭证失效 |
| 4 | `店里有什么手机卖？` | 分类/搜索；可能全下架，要求诚实回答 | 期望调用 `searchGoods` 或 `searchByCategory` | 未取得；`TimeoutException: timeout`，`fullMs=64280` | 未能复跑（401 凭证失效） | 无法判断 | Java 上游超时；Python 凭证失效 |
| 5 | `推荐一款洗面奶` | 搜索 + 推荐；在售商品优先 | 期望调用 `recommendGoods` | 未取得；`TimeoutException: Read timed out`，`fullMs=34038` | 未能复跑（401 凭证失效） | 无法判断 | Java 上游超时；Python 凭证失效 |
| 6 | `我的订单 2024051312345678 什么状态？` | 订单查询；不存在的单号必须核实而不是编状态 | 期望调用 `queryOrder`；回答须明确表达查不到，且不得声称已支付/待发货等状态 | 未取得；`TimeoutException: timeout`，`fullMs=34177` | 未能复跑（401 凭证失效） | 无法判断 | Java 上游超时；Python 凭证失效 |
| 7 | `有没有扫地机器人？` | 分类查询 | 期望调用 `searchGoods`、`searchByCategory` 或 `recommendGoods` | 未取得；`TimeoutException: Read timed out`，`fullMs=56699` | 未能复跑（401 凭证失效） | 无法判断 | Java 上游超时；Python 凭证失效 |
| 8 | `化妆品有哪些分类？` | 分类知识；走 RAG/分类工具 | 期望调用 `searchByCategory` 或 `searchGoods` | 未取得；`TimeoutException: timeout`，`fullMs=61111` | 未能复跑（401 凭证失效） | 无法判断 | Java 上游超时；Python 凭证失效 |
| 9 | `你们支持退货吗？` | 规则/闲聊；无需商城数据工具 | 不得调用任何商城工具；回答非空 | 通过，`fullMs=29675`：`您好呀~ 我们新蜂商城是支持退货的哦！一般来说，商品在签收后7天内，保持完好、不影响二次销售的情况下，都可以申请退货呢。…` | 未能复跑（401 凭证失效） | 无法判断 | Java 有真实回答；Python 未取得 |
| 10 | `今天天气怎么样？` | 无关问题；应礼貌引导回客服主题 | 不得调用任何商城工具；回答非空 | 未取得；`TimeoutException: timeout`，`fullMs=34062` | 未能复跑（401 凭证失效） | 无法判断 | Java 上游超时；Python 凭证失效 |

**本轮 Java 汇总**：10 问只取得 1 个有效回答（第 9 问），第 1-8、10 问均在上游超时前
没有返回模型文本；因此没有把超时结果解释成答案质量下降。完整逐题原始记录见
`mall-backend/target/cs-regression/java-results.tsv`，该目录属于构建产物，不提交到 git。

## 2b. ⚠️ 本轮"哪些断言真的被执行过"（claude 复核建议留痕）

跑通一次 ≠ 所有断言都验证过。本轮实际执行情况：

| 断言 | 本轮是否真的被执行 |
|---|---|
| 答案非空 | ✅ 第 9 问执行到 |
| **数字一致性**（回答里的数字须出现在工具结果中） | ❌ **未被有效执行** —— 需要工具调用成功的案例，而本轮 9/10 因上游超时未取到工具轨迹 |
| 该调工具的问题是否调了工具 | ❌ 同上（观测到的工具调用次数为 **0**，**不能据此推断设计行为**） |
| 不该调工具的问题是否没调 | ✅ 第 9 问（"你们支持退货吗"）执行到 |
| 不存在订单不得声称状态 | ❌ 第 6 问超时，未执行到断言 |

**结论：本轮仅验证了"链路可跑通 + 无关问题不调工具"，数字一致性等核心断言尚未被真正验证。**
→ 恢复稳定模型通道后需重跑（见 §5）。

### 3. 量化对比

> 🔄 **2026-09-19 第二轮（新通道 agnes-2.5-flash 直连）已把上表刷新**，最新数据见 §5；本节保留第一轮数字作为历史基线。

| 指标 | Java（第一轮，OmniRoute+agnes 限流） | Python | 说明 |
|---|---:|---:|---|
| 有效回答数 | **1/10** | **未测** | Java 仅第 9 问返回；其余为 `TimeoutException`；Python 旧凭证 401 |
| 首字延迟 | **未测** | **未测** | `CsAgentTenQuestionIT` 调用非流式 `answer()`，没有首字时间；Python 未复跑 |
| 完整回答耗时 | 见下表 | **未测** | Java 记录的是非流式 IT 的墙钟时间，不等于 SSE 首字延迟 |
| 工具调用次数 | 见下表 | **未测** | Java 超时案例在模型返回前没有工具轨迹；不能把 0 解释为“设计上不调用工具” |

### Java 本轮逐题耗时

| # | 完整耗时（ms） | 工具调用 | 结果 |
|---:|---:|---|---|
| 1 | 41012 | 0 | 超时 |
| 2 | 65632 | 0 | 超时 |
| 3 | 63704 | 0 | 超时 |
| 4 | 64280 | 0 | 超时 |
| 5 | 34038 | 0 | 超时 |
| 6 | 34177 | 0 | 超时 |
| 7 | 56699 | 0 | 超时 |
| 8 | 61111 | 0 | 超时 |
| 9 | 29675 | 0 | 有效回答；无工具符合“退换货”规则问答 |
| 10 | 34062 | 0 | 超时 |

> 性能实验历史、虚拟线程和中文嵌入 A/B 结论见 [PERF-M3.md](PERF-M3.md)；
> 当前项目状态和模型通道限制见 [STATUS.md](STATUS.md)。本文不重复抄录。

## 4. 三条有意差异

以下差异是设计决策或已知修复，不能把“Java 与 Python 不一致”直接判成 Java 回归。

### 4.1 Java 不再保留“Java API 不可达 → 直连 MySQL”的跨进程容错

Python 版 `api_tools.py` 采用“HTTP 调 Java `/api/agent`，失败回退 MySQL 直查”，
源码注释和回退入口见 `api_tools.py:11,27-31,52-53,83-84,110-111,131-132`。
Java 重构后模型编排与商城服务在同一个 Spring Boot 进程中，`MallTools` 直接调用
Service/Mapper，不存在“外部 Java API 不可达”这一层，因此不保留该回退分支。
这是架构合并后的删除，不是容错能力丢失。

### 4.2 质检默认值：Python `gate`，Java `audit`

Python 版 `cs_agent.py:162` 默认 `QA_MODE="gate"`，不合格时同步打回重答。
Java 版默认 `audit`：回答先返回，质检在旁路异步执行并通过 SSE `review` 事件送达；
需要同步把关时可配置 `cs.qa.mode=gate`。对应实现见
`mall-backend/src/main/java/ltd/newbee/mall/service/agent/CsAgentService.java`。

选择 `audit` 的原因不是降低正确性要求，而是避免质检 LLM 同步阻塞流式回答；
Python 的同步 `gate` 实测会拉长响应时间。该差异已写入 [DESIGN.md](DESIGN.md) 的决策与风险记录。

### 4.3 Python 的上下架判断写反；Java 按商城权威定义修复

Python `db_tools.py:103` 和 `db_tools.py:121` 使用
`== 1` 判断“在售”，并在多个搜索/推荐结果中还把 `== 1` 当作在售标记。
商城权威定义是 `Constants.SELL_STATUS_UP = 0`：
`mall-backend/src/main/java/ltd/newbee/mall/common/Constants.java:278`。
现状数据记录为 `0 → 573` 件在售、`1 → 2` 件下架。

Python 因而把大部分在售商品标成“已下架”，这是 Python 侧缺陷。Java `MallTools`
按 `SELL_STATUS_UP`（0）判断在售，属于**本项目的修复**，不是回归。
该基准原则和实测分布见 [STATUS.md](STATUS.md) 与 [DESIGN.md](DESIGN.md)。

## 5. 抽样复跑（2026-09-19，题号 1 / 2 / 10）

第一轮 10 问只取得 1/10 有效回答（见 §2b），因此本轮改用**抽样**方式复跑三题，
覆盖三类判定：价格题（#1，需工具数字）、库存题（#2，需工具数字）、无关题（#10，不得调工具）。

复跑命令（会覆盖结果文件，需要保留全量结果时先备份）：

```bash
cd /mnt/d/GitHub/xiangmu/newbee-mall-ai
export CS_ENABLE_REAL_MODEL_IT=1 && set -a && . ./.env && set +a
bash ops/mvn.sh test -Dtest=CsAgentTenQuestionIT -Dcs.it.questions=1,2,10
```

> `-Dcs.it.questions` 是本次为抽样新增的**可选**过滤（不指定时仍跑全部 10 题，既有语义不变）。
> ⚠️ `CS_ENABLE_REAL_MODEL_IT` 原先**不在** `ops/mvn.sh` 的 WSLENV 白名单里，
> 不转发时 `@EnabledIfEnvironmentVariable` 会让 IT **静默跳过**（表现为“跑了却什么都没发生”），
> 本轮已补进白名单。

| # | 问题 | 结果 | fullMs | 工具调用 | 说明 |
|---:|---|---|---:|---|---|
| 1 | 无印良品的笔记本多少钱？ | ❌ 未取得 | 39.2s | 0 | `TimeoutException: timeout` |
| 2 | 无印良品的化妆水有货吗？ | ❌ 未取得 | 66.7s | 0 | `TimeoutException: Read timed out` |
| 10 | 今天天气怎么样？ | ✅ 通过 | 30.7s | 0 | 回答非空、未调商城工具，符合“无关问题应引导回客服主题” |

原始记录：`mall-backend/target/cs-regression/java-results-sampled-3q-20260919.tsv`
（构建产物，不入 git；`java-results.tsv` 已被第三轮覆盖，第一轮备份另存于本地 `/tmp`）。

~~结论（如实）：真正要验证的**数字一致性断言仍然没有被执行到** ——~~
→ **已被第三轮闭环，见 §7**。

## 7. 第三轮：新通道 agnes-2.5-flash 直连（2026-09-19，**10 问 7/10 通过**）

用户提供了新的免费通道（`https://api.agnes-ai.cn/v1` + 新 key，模型 `agnes-2.5-flash`，**绕开 OmniRoute 直连**）。
换通道后单问耗时从「24~80s 超时」降到 **1~26s**（总耗时 81s，快一个数量级以上），
并首次实现了：

| # | 结果 | 耗时 | 工具调用 | 说明 |
|---:|---|---:|---|---|
| 1 价格 | ✅ | 5.7s | `getGoodsDetail` | **「数字一致性」断言首次真实验证**：回答「9 元」来自工具结果 |
| 2 库存 | ✅ | 4.1s | `checkStock` | 库存 1000 与工具一致 |
| 3 价格 | ✅ | 3.1s | `getGoodsDetail` | 30/40 元来自工具 |
| 4 手机 | ❌ | 3.3s | **0 次** | 模型未调工具直接作答（红米7/iPhone12，疑似模型训练知识而非商城数据）→ **P1 新待办** |
| 5 推荐 | ✅ | 26.1s | 3 个工具 | 价格/库存均来自工具 |
| 6 订单 | ❌ | 2.0s | `queryOrder` | **行为正确**（未编造状态），但“没有查到”未命中断言词表 → **断言口径过窄** |
| 7 分类 | ✅ | 14.1s | 3 个工具 | 结果多到触发恢复话术 |
| 8 化妆品分类 | ❌ | 4.3s | - | **上游免费速率限制**（连续 7 问后撞上限额，非代码缺陷） |
| 9 退货 | ✅ | 6.7s | 无（正确） | 退换政策直接答 |
| 10 天气 | ✅ | **1.0s** | 无（正确） | 引导回商城主题 |

**本轮核心结论：§2b 里「未被执行」的断言清单已全部执行到**：
数字一致性（#1/2/3/5 价格与库存均来自工具）✅；该调工具的调了（#1/2/3/5/7）✅；
不该调的没调（#9/10）✅；不存在的订单未编造状态（#6 行为正确但措辞未中词表）。

**新暴露的 2 个问题（如实）**：
1. **#4 模型跳过工具直接作答**（第一轮实测）：编造了库里不存在的红米7/ iPhone 12（真实库存品是 iPhone 11 Pro，
   还被它篿改成 iPhone 12 Pro，价格 8699 是从真品套的）→ **已调查并修复**（见下）
2. **#6 断言词表过窄**：“没有查到相关信息”未被 [未找到/查不到/…] 命中；行为正确却被判失败 →
   词表应加“没有查到”等自然变体

**#4 根因与修复（2026-09-19 当场闭环）**：
- DB 核实：商城根本没有红米7 / iPhone 12（手机类真实在售是耳机、二手苹杢7P、小米9/MIX2S 等）
  → 模型答案是**幻觉编造**，不是“训练知识碰巧答对”，比最初判断更严重
- **根因是 prompt 自相冲突**：【处理模糊问题】让“轻模糊直接给多范围解答”（含价位分档），
  与【数据铁律】“必须先调工具”冲突 → 模型概率性服从前者 → 同一题一次幻觉、复跑又正确调 3 个工具（非确定性）
- **修复**：在【处理模糊问题】中明确“模糊策略只决定交互方式，不豁免数据铁律；
  轻模糊也必须先调工具、基于工具返回的商品分档；拿不到就说“我帮您查一下”
- **验证（概率性问题必须多次）**：修复前 1 挂 1 过；修复后**连续 3 轮全部 passed=true**，
  工具轨迹均为 `searchByCategory → searchGoods → getGoodsDetail`（3/3 调工具，无幻觉）

**总耗时 81.3s / 10 题（平均 8.1s）**，第一轮单题就 24~80s → **通道速度提升一个数量级**。
R12 的「免费额度 24~80s」问题对新通道**不复存在**（本轮最慢单题 26s，其余 ≤14s）。

## 8. 结论（2026-09-19 更新）

第三轮（agnes-2.5-flash 直连）**10 问 7/10 通过**，「数字一致性」等核心断言首次全部执行到；
Python 侧旧凭证仍未复跑，两侧对照仍待补。

剩余两个已知问题（见 §7）→ 后续：
- #4 模型跳过工具直接作答：prompt 层強化「商城数据必须先调工具」
- #6 断言词表补充自然变体（如“没有查到”）

复跑命令（新通道下已稳定，不再需要备份结果文件的前提）：

```bash
cd /mnt/d/GitHub/xiangmu/newbee-mall-ai
export CS_ENABLE_REAL_MODEL_IT=1 && set -a && . ./.env && set +a
bash ops/mvn.sh test -Dtest=CsAgentTenQuestionIT
```

以及原 Python 脚本；两边逐题答案、工具轨迹和耗时补齐后，再更新“是否一致”列。
