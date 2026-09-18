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
| **M2-3** | RAG：`KnowledgeBuilder` + `RagService`（混合检索 RRF）+ 语料红线 | ⏸️ **实现完成待检查**（测试 14/14；语料红线 0 命中） |
| **M3 容器化** | Dockerfile + docker-compose + init.sql | ✅ 完成且**真机验证**（3 容器 healthy） |
| M2-4 | 编排 `CsAgentService` + 质检 `QaReviewer` | ⬜ **阻塞：模型通道** |
| M2-5 | SSE 接口 + 会话记忆落库 | ⬜（依赖 M2-4） |
| M2-6 | 单元/集成测试 | ⬜ |
| M3 其余 | 前端原生融合（浮窗 + `/cs`）、虚拟线程压测、中文嵌入对比、CI | ⬜ |

**提交数**：23+（最近：`d4e2885` README 容器化实测；M2-3 改动**尚未提交**）

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

## 5. 本机环境要点（坑都在这里）

| 事项 | 事实 |
|---|---|
| **shell** | 本机 bash 是 **WSL**（不是 Git Bash）。路径用 `/mnt/d/...`；Git Bash 用 `/d/...` 且会自动补 `.exe`，WSL 不会 → 调 Windows exe **必须带 `.exe`** |
| **WSL ↔ Windows** | WSL **访问不到 Windows 的 localhost**（如 28089/20128）→ 需用 Windows 的 `curl.exe`；**环境变量不会自动跨界**，需 `WSLENV=VAR/w` 转发（`ops/mvn.sh` 已处理 `DB_PASSWORD`） |
| **Maven** | 本机无 Linux Maven；`D:\tools\apache-maven-3.9.16`（Windows 版）。**一律用 `bash ops/mvn.sh <args>`**（它做 WSL→Windows 转发） |
| **Docker** | Docker Desktop 29.8.0 已装；守护进程需手动启动；**Docker Hub 被墙**，已在 `~/.docker/daemon.json` 配 3 个国内镜像加速（原配置有 `.bak` 备份） |
| **MySQL** | `E:\mysql-9.7.1`，密码用 `DB_PASSWORD` 环境变量（不落盘） |
| **Redis** | 本机 6379 是 **3.0.504（无向量能力）**；RAG 必须用容器 redis:8 的 **16379** |
| **XML 注释** | **不能含 `--`**（我在 pom 里写了 `----------` → `ModelParseException`） |
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

## 7. 当前阻塞

**模型通道不可用**（M2-4 的硬前置）。实测：
- kilocode（`kc`）：`credits_exhausted` → 402
- kimi-coding（`kmc`）：月度配额尽 → 403
- cursor（`cu`）：`unavailable` → 约 3.8 天后恢复
- **agnes / huggingchat**（用户新增，`providers list` 可见，`oauth status` **看不到**）：
  静态目录有模型，但调用报 `Model 'X' is not available in the active live catalog`（400）
- cline / openference：active 但**无模型目录**

→ **已交给 herdr 里的 codex（agent 名 `modelfix`，pane `wG:p6`）去查"live catalog"机制并跑通 function calling spike。**

## 8. 下一步

1. **M2-3 等 claude 检查通过** → 提交
2. **codex 修复模型通道** → 跑通 function calling spike
3. 两者都就绪 → **M2-4（编排 + 质检）**，注意用 `@Qualifier("csChatModel")`
4. 之后 M2-5（SSE + 会话记忆）、M2-6（测试）、M3 前端融合 + 压测 + CI
5. 全部完成后再推 GitHub（远程仓库尚未创建）

## 9. 文档索引

| 文件 | 内容 |
|---|---|
| `docs/DESIGN.md` | **设计规格 v1.1**（41 项决策 / 11 条风险，含独立评审修订记录） |
| `docs/PLAN.md` | M1 实现计划（11 任务，含基线方法） |
| `docs/UPGRADE-BOOT3.md` | **升级实战记录**（基线 / 迁移清单 / 9 个踩坑 / 验证证据 / 回退方式） |
| `README.md` | 项目说明 + 容器化实测 + 端口约定 |
| `docs/STATUS.md` | 本文件（状态快照） |
| `ops/mvn.sh` · `ops/smoke.sh` · `ops/init.sql` | 运维脚本与初始化数据 |
