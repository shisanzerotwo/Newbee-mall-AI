# TASK 生产化剩余项（交给 claude 执行）

> **依据**：`docs/DESIGN.md` §12 风险登记册（R9/R10/R12）+ `docs/STATUS.md` §8 下一步
> **执行者**：herdr 里的 claude（`reviewer`，pane `wG:p2`）
> **复核者**：herdr 里的 codex（`modelfix`，pane `wG:p6`）—— **写者 ≠ 验证者，不得自查代替**
> **监督 / 提交**：编排者（pi）—— 你**不许** commit / push

---

## 0. 环境须知（先读，能省很多时间）

- 项目根：`D:\GitHub\xiangmu\newbee-mall-ai`（WSL：`/mnt/d/GitHub/xiangmu/newbee-mall-ai`）
- **本机 bash 是 WSL**：调 Windows 程序要带 `.exe`；Windows 侧 docker 在 PATH 里
- **Maven 必须用** `bash ops/mvn.sh <args>`（WSL→Windows 包装器）
- 跑测试前：`set -a && . ./.env && set +a`（**Maven 不读 .env**）
- ⚠️ **本机内存紧张**（15.7 GB 常只剩 1~3 GB）：跑全量测试请带
  `-DargLine="-Xmx700m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m -XX:CICompilerCount=2"`
  否则会以 `insufficient memory ... Chunk::new` 崩掉（**那不是测试失败**，详见 `docs/DEVELOPMENT.md` §4.2）
- 应用当前在 **28091**；容器 `newbee-mall-{mysql,redis,app}` healthy；Redis 用容器 **16379**
- 推送 GitHub 必须走 Windows 侧 git（但本轮**不需要**你推送）

---

## 1. 范围（4 项，按优先级；做完前两项再做后两项）

### 🔴 P1：中文检索语义命中率（当前语义查询 **2/10 = 20%**，换 bge 后 **4/10 = 40%**）

- 现状证据：`docs/RAG-EVAL.md`、`docs/PERF-M3.md`（**先读这两份，别重复踩坑**）
- **第一步只做诊断，不改代码**：把当前评测的失败用例逐条打开看，归类原因 ——
  切分太碎？嵌入模型上限？缺查询改写？RRF 权重不当？候选集太小？
- **第二步按数据选方案**（可 A/B）。候选方向（自行判断更优的）：
  - 更强的中文嵌入模型（如 bge-large-zh 级别）—— ⚠️ 要如实报告成本：模型体积、下载、启动时间、内存
  - 切分策略调整（当前是按商品切还是按字段切？过长/过短都伤召回）
  - 查询侧增强（同义词扩展 / 查询改写）
  - RRF 融合权重与候选数
- **验收要求**：
  1. 给出**可复算的数字**（命中率分子/分母写清，评测脚本与命令可复跑）
  2. **阴性对照**：把改动退回旧配置，指标必须回落（证明增益来自改动而非评测噪声）
  3. 不许"感觉变好了"
- **红线**：不许把价格 / 库存 / 上下架状态写回向量库语料（数据铁律）；引入新依赖前先说明理由与成本

### 🟡 P2：`/api/cs/health` 收敛（R9 相关）

- 现状：该端点暴露用量计数等内部信息（演示无害，正式环境不合适）
- 目标：**默认最小化** —— 只返回健康必需项（如整体状态 / DB / Redis 可达性）；
  用量计数移到需要显式开关（或开发用 profile）才暴露的路径
- 要求：改完跑现有相关测试 + `bash ops/smoke.sh` 必须 **16/16**；
  `docs/USER_GUIDE.md` / `README.md` 里对该端点的描述同步
- **验收**：给出改动前后同一 curl 的响应体对比（证明收敛了，而不是删没了）

### 🟡 P3：XSS 行为验证接进 CI

- 现状：`mall-backend/src/test/java/ltd/newbee/mall/controller/mall/CsXssBrowserIT.java`
  已入库并实测通过，但**不进 CI**（它要拉 Chrome）
- 目标：二选一，并给出理由 ——
  (a) 在 `.github/workflows/ci.yml` 里加一个**独立 job**（装 Chrome 后跑 `-Dtest=CsXssBrowserIT`）；或
  (b) 证明它不适合进 CI（如耗时/镜像体积/稳定性），给出**实测数据**支撑的结论
- **红线**：**绝不许**让 CI 变成随机红灯的来源 —— 无 Chrome 环境必须 **skip 而非 fail**
  （`CsXssBrowserIT` 已用 `Assumptions` 实现跳过，别破坏它）
- **验收**：本地跑一遍与 CI 等价的命令；给出 workflow 的 diff 或"不进 CI"的证据

### ⚪ P4（小）：`CS_ENABLE_REAL_MODEL_IT` 守卫放宽

- 现状：`@EnabledIfEnvironmentVariable(named = "CS_ENABLE_REAL_MODEL_IT", matches = "1")`
  只认字面 `1` → 写 `=true` 时 IT 会**静默跳过**（表现为"跑了却什么都没发生"）
- 目标：接受 `1 / true / yes`（大小写不敏感）；或改用 `Assumptions` 让"跳过及其理由"出现在报告里
- 影响面：`CsAgentTenQuestionIT`、`CsAgentRealCallIT` 两个类（先 grep 确认还有没有别的）
- **验收**：两种写法各跑一次，证明守卫都被满足（或跳过理由可见）

---

## 2. 红线（违反即返工）

1. **不许 `git commit` / `git push`**（由编排者决定提交时机）
2. **不许把依赖真实上游模型的用例塞进默认 `mvn test`**（上游 429 会让 CI 随机红灯）
3. **不许改既有测试的语义**去让自己通过；发现既有测试有问题要**单独说明**
4. **不许把价格 / 库存写进 RAG 语料**（违反数据铁律）
5. 不碰 `.env`；**密钥不许写进任何文件**
6. **文档里的事实必须核实**（行数用 `wc`、命令实跑过），无法核实的明确标注"未验证"
7. 改前端模板 / JS 时**只追加不改坏**既有结构（`footer.html` 是全站共用，改后必须跑冒烟 16/16）

---

## 3. 交付与报告

**交付**：新增/修改文件列表（路径 + 行数）+ **你实际跑过的命令 + 真实输出**（不是"应该会通过"）

**报告格式**（简短）：
1. 完成了哪些（按 P1/P2/P3/P4 分类）
2. 每项的**验证证据**（命令 + 输出摘要；数字类要给分子/分母）
3. **未完成项及原因**（如实说，不要假装做完）
4. 你发现的**新问题**
