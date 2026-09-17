# newbee-mall-ai

把 **newbee-mall（Java 电商）** 与 **AI 智能客服** 融合为一个 Spring Boot 应用的改造项目。

> 客服不再是 iframe 里的外部网站，而是商城内的**原生能力**：同进程、同栈、同前端。
> 目标技术栈：**Java 21 + Spring Boot 3.5 + LangChain4j + Redis 8**。

---

## 当前状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| **M1** | 新仓库 + **Spring Boot 2.7.5 → 3.5.16 / Java 8 → 21 升级** + 冒烟回归 | ✅ **已完成**（商城 11/11 通过，与升级前基线一致） |
| **M2** | 客服核心：LangChain4j 装配 + RAG（Redis 向量） + 6 个 @Tool + 编排 + 质检 + SSE 流式 | ⬜ 未开始 |
| **M3** | 前端原生融合（浮窗 + `/cs`） + Docker Compose + 虚拟线程压测 + 中文嵌入对比 + CI | ⬜ 未开始 |

| 文件 | 内容 |
|---|---|
| `docs/DESIGN.md` | **设计规格 v1.1**（41 项决策 / 11 条风险 / 含独立技术评审修订记录） |
| `docs/PLAN.md` | **M1 实现计划**（11 个任务，含升级前基线与 DoD） |
| `docs/UPGRADE-BOOT3.md` | **升级实战记录**（实测基线、迁移清单、9 个踩坑与解法、验证证据、回退方式） |
| `docs/MALL-UI-SPEC.md` | 商城前台 UI 规格（来自旧仓库的 UI 重设计） |
| `ops/mvn.sh` | Maven 包装器（WSL/Git Bash → Windows 侧 Maven，含 WSLENV 密钥转发） |
| `ops/smoke.sh` | 冒烟回归脚本（升级前后对照用） |
| `mall-backend/` | 唯一应用（Spring Boot 3.5.16 + Java 21） |

---

## 架构（目标态）

```
                    ┌──────────────────────────────────────────┐
                    │      newbee-mall-ai（单进程/单部署）      │
   浏览器 ──────────┤  ┌─ 商城域 ──────────────────────────┐   │
   （同一站点）      │  │ controller/mall · service · dao   │   │
                    │  │ Thymeleaf 模板 · 静态资源          │   │
                    │  └───────────┬──────────────────────┘   │
                    │              │ 直调 Service/Mapper       │
                    │  ┌─ 客服域 ──▼──────────────────────┐   │
                    │  │ controller/agent  (M2 新增)       │   │
                    │  │ service/agent     (M2 新增)       │   │
                    │  └───────────┬──────────────────────┘   │
                    └──────────────┼───────────────────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                    ▼
        MySQL 9.7            Redis（缓存/ZSet/向量）  LLM 网关
   （商城数据 + 记忆表）                          （OmniRoute/备用 key）
```

---

## 启动（M1 状态）

**前置**：MySQL 9.7（3306）、Redis（6379）需已在运行。

```bash
# 1. 设置数据库密码（WSL 下必须由 ops/mvn.sh 经 WSLENV 转发给 Windows 侧）
export DB_PASSWORD='<你的 MySQL 密码>'

# 2. 启动
cd newbee-mall-ai
bash ops/mvn.sh spring-boot:run

# 3. 访问
#    http://127.0.0.1:28089
```

**冒烟回归**（脚本会自动选用 Windows `curl.exe`，因为 WSL 访问不到 Windows 的 loopback）：

```bash
bash ops/smoke.sh
# 期望：通过 11 / 失败 0
```

> ⚠️ **两个本机环境陷阱**（详见 `docs/UPGRADE-BOOT3.md` §3）：
> 1. WSL 的环境变量**不会**自动传给 Windows 子进程 → 必须经 `WSLENV` 转发（已固化在 `ops/mvn.sh`）。
>    漏掉的症状很有迷惑性：**应用能启动、页面返回 200**，但连不上库、缓存永不写入。
> 2. WSL 无法访问 Windows 的 `127.0.0.1:28089`（直连与网关 IP 都是 `000`），冒烟必须用 Windows 的 `curl.exe`。

---

## 待办

- [ ] **安装 Docker Desktop**（M3 的 `docker compose up -d` 前置；本机当前未安装）
- [ ] **启动 OmniRoute 网关**并实测 function calling（M2 的模型通道前置；当前未运行）
- [ ] M1 的两处待人工验证：订单超时链路下单验证、容器内验证码字体（见 `UPGRADE-BOOT3.md` §4）

---

## 来源与边界

- 商城源码来自 `D:\GitHub\xiangmu\newbee-mall`（**上游 fork**，本改造不修改上游仓库）
- 旧版 Python 客服（`D:\GitHub\xiangmu\ai开发\12_agent_cs`）**原地保留**，作为 M2 的行为对照基准
- 已删除的 `/api/agent` 只读接口可从 `newbee-mall` 的 `ca178f4` 提交取回
