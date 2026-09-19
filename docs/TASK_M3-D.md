# TASK M3-D：DoD 剩余项（交给 codex 执行）

> **依据**：`docs/DESIGN.md` §10.1 完成定义（DoD）里**尚未完成**的项
> **执行者**：herdr 里的 codex agent（`doer`，tab `wG:t3` / pane `wG:p7`）
> **检查者**：herdr 里的 claude agent（`reviewer`，pane `wG:p2`）—— **codex 不得自查代替**
> **监督**：编排者（pi）

---

## 0. 环境须知（先读，能省很多时间）

- 项目根：`D:\GitHub\xiangmu\newbee-mall-ai`（WSL 路径 `/mnt/d/GitHub/xiangmu/newbee-mall-ai`）
- **本机 bash 是 WSL**：Windows 路径写 `/mnt/d/...`；调 Windows 程序必须带 `.exe`
- **Maven 必须用 `bash ops/mvn.sh <args>`**（WSL→Windows 包装器）
- 跑测试前必须 `set -a && . ./.env && set +a`（**Maven 不读 .env**）
- 应用当前跑在 **28091**（`java -jar mall-backend/target/*.jar --server.port=28091`）
- MySQL 本机 3306；**Redis 用容器 redis:8 的 16379**（本机 6379 不支持向量）
- **推送 GitHub 必须走 Windows 侧**：`powershell.exe -NoProfile -Command "git -C 'D:\GitHub\xiangmu\newbee-mall-ai' ..."`
  （WSL 连不上 github；hosts 被 Steam++ 接管，它代理 github.com 但不代理 api.github.com）

---

## 1. 本任务范围（按优先级，全部做完再报告）

### 🔴 P1：10 问回归集（DoD 质量项）

**目标**：对齐 Python 版 `D:/GitHub/xiangmu/ai开发/12_agent_cs/test_questions.py` 的 10 问，
作为**固定回归集**，量化本项目的答案质量。

**做法建议**（可自行判断更优方案）：
- **先读** Python 版那 10 个问题，逐条理解每题**想验证什么**（如：库存查询 / 价格 / 推荐 / 模糊问题 / 无关问题 / 越界问题）
- 在 Java 侧建**同样 10 问**的回归测试（放 `mall-backend/src/test/java/.../service/agent/` 下）
- **判定口径要客观可复算**（不能"看着不错"）：例如
  - 涉及库存/价格的问题：答案里的数字必须与工具返回一致
  - 无关/越界问题：应礼貌拒答或引导，不该编造商品
- **⚠️ 关键约束**：真实模型调用会打上游（agnes 免费额度会 429）。
  参考已有做法：`CsAgentRealCallIT` 是**手动跑的集成测试**，`RagChineseQualityTest` 用**评测输出**而非硬断言。
  **不要**把依赖真实上游的用例塞进默认 `mvn test`（会让 CI 变随机红灯）。
  建议：测试类命名 `*IT`（Maven 默认不跑），并在文档里写清怎么手动跑。

### 🔴 P1：Java vs Python 同题对比表（DoD 明确要求）

**目标**：产出 `docs/COMPARE-JAVA-PYTHON.md`，同题对比两边表现。

**必须包含**：
- **同题对照表**：10 个问题 × （Java 答案 / Python 答案 / 是否一致 / 差异原因）
- **⚠️ 已知的有意差异**（必须写进去，否则会被误读为回归）：
  1. Java 版**取消**了 Python 的「Java API 不可达 → 回退直连 MySQL」容错（同进程后不需要）
  2. Java 版质检**默认 audit**（Python 默认 gate）—— 理由：gate 同步阻塞拉长响应
  3. Python 版 `db_tools.py:103/121` 的上下架判断**写反了**（`== 1` 判在售），
     而商城权威定义是 `Constants.SELL_STATUS_UP = 0`；**本项目以商城源码为准**
     → 这个差异是**本项目的修复**，不是回归
- **量化对比**：首字延迟 / 完整回答耗时 / 工具调用次数（有数据就写，没有就注明未测）

### 🟡 P2：XSS 12 条 payload 全量核查（DoD 安全项）

**目标**：DoD 要求「12 条 XSS payload 在浮窗与 `/cs` 各跑一遍」。

- 先看 `docs/DESIGN.md` §8.4 与已有测试（`CsFrontendGuardTest` / `CsWidgetGuardTest`）**已覆盖什么**
- 补齐**未覆盖**的 payload 与渲染路径；断言渲染后**被转义**
- 注意：现有守卫是**静态断言**（读源码字符串），能证明"源码里没有危险写法"，但证明不了"浏览器真的转义了"。
  如能做**行为级**验证更好（项目里有用 JDK HttpServer 当假上游、Chrome headless 截图的先例）

### 🟡 P2：三份文档（DoD 文档项）

| 文件 | 内容要求 |
|---|---|
| `docs/ARCHITECTURE.md` | 代码地图、两条产线数据流（普通商城 / 客服）、关键约定、扩展点 |
| `docs/RAG-EVAL.md` | RAG 评测口径与结果（引用 `docs/PERF-M3.md` 的中文嵌入 A/B 结论），含如何复跑 |
| `docs/DEMO.md` | 一键启动 + 演示路径（按 DoD 的验收场景走一遍：浮窗问库存 / 详情页问价格 / 流式 / 会话记忆） |

**要求**：文档里的事实**必须核实**（模块行数用 `wc`、路由用 `grep`、命令实跑过），
不许写"大概/应该是"。与既有文档（DESIGN/STATUS/PERF-M3）**交叉链接**，不要重复抄。

### ⚪ P3（若时间有余，做不完要如实说）

- 容器内验证码可辨（AWT 字体）：起容器、访问登录页、截图确认验证码能看到
- 会话记忆**跨重启**保留：重启应用后同一 `conversationId` 能取回历史

---

## 2. 🔴 红线（违反即返工）

1. **不许把依赖真实上游模型的用例塞进默认 `mvn test`**（上游 429 会让 CI 随机红灯）
2. **文档里的事实必须核实**，不许推测；无法核实的明确标注"未验证"
3. **不许改动既有测试的语义**去让自己通过；发现既有测试有问题要**单独说明**
4. **不许 git commit**（由编排者决定提交时机）；**不许 push**
5. 不碰 `.env`（含密钥）；不把密钥写进任何文件
6. 改前端模板/JS 时，**只追加不改坏**既有结构（`footer.html` 是全站共用）

## 3. 交付与报告

**交付**：
- 新增/修改的文件列表（含路径与行数）
- **你实际跑过的命令 + 真实输出**（不是"应该会通过"）

**报告格式**（简短，写进最终回复）：
1. 完成了哪些（按 P1/P2/P3 分类）
2. 每个交付物的**验证证据**（命令 + 输出摘要）
3. **未完成项**及原因（如实说，不要假装做完）
4. 你发现的**新问题**（如果有）
