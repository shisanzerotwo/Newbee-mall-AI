# Spring Boot 2.7.5 → 3.5 / Java 8 → 21 升级实战

> 状态：M1 进行中（草稿，Task 11 完善）

## 1. 升级前的实测基线
### 1.1 依赖与版本

**升级前**（8 个直接依赖，无 Lombok / 无 Spring Security / 无 JPA）：

| 依赖 | 升级前 | 升级后 |
|---|---|---|
| `spring-boot-starter-parent` | 2.7.5 | **3.5.16** |
| `java.version` | 1.8 | **21**（+ `maven.compiler.release=21`） |
| `mybatis-spring-boot-starter` | 2.2.2 | **3.0.5** |
| `hutool-captcha` | 5.8.7 | **5.8.47** |
| MySQL 驱动 | `mysql:mysql-connector-java` | **`com.mysql:mysql-connector-j`**（9.7.0，由 BOM 管理） |
| `spring-session-core` | 存在 | **已删除**（实测全仓零 `org.springframework.session` 引用，死依赖） |
| `maven-compiler-plugin` | 显式 `source/target=${java.version}` | **删 source/target**，改由 `maven.compiler.release` 约束 |

**Boot 3.5.16 实测管理的版本**（由 `spring-boot-dependencies-3.5.16.pom` 核实）：
`jedis 6.0.0`｜`lettuce 6.6.0.RELEASE`｜`mysql 9.7.0`｜`thymeleaf 3.1.5.RELEASE`｜`spring-framework 6.2.19`｜`maven-compiler-plugin 3.14.1`

### 1.2 javax 使用分布

**实测 44 处**（原估 46，差异见下）：

```
20 javax.servlet.http.HttpServletRequest
13 javax.annotation.Resource
 7 javax.servlet.http.HttpSession
 4 javax.servlet.http.HttpServletResponse
 1 javax.imageio.ImageIO      ← 保留（java.desktop，非 Jakarta EE）
```

> 为什么是 44 而不是 46：原估算包含 `AgentApiController.java`（决策 #18 已删）内的 `import javax.annotation.Resource` 与 `import javax.servlet.http.HttpServletRequest` 各 1 处。已用 `git show ca178f4:...AgentApiController.java | grep -cE "^import javax\.(annotation\.Resource|servlet\.http\.HttpServletRequest);"` 核实为 2。
### 1.3 冒烟基线

**采集时间**：2026-09-17（旧仓库 HEAD 版本，`ops/smoke.sh`）
**运行环境**：Spring Boot 2.7.5 + **JDK 25**（实测可运行，仅有 `restricted method` / `Unsafe` 警告，无错误）+ MySQL 9.7.1 + Redis 3.0.504
**启动耗时**：`Started NewBeeMallApplication in 4.821 seconds`

**结果：11/11 全部通过**

```
=== 冒烟回归 @ http://127.0.0.1:28089 ===
--- 前台 ---
✅ 首页        /                                    (200)
✅ 商品搜索    /search?keyword=<URL编码的“化妆水”>    (200)
✅ 商品详情    /goods/detail/10003                    (302)
✅ 购物车页    /shop-cart                             (302)
✅ 登录页      /login                                (200)
✅ 注册页      /register                             (200)
✅ 个人中心    /personal                             (302)
--- 后台 ---
✅ 后台登录    /admin/login                          (200)
✅ 后台首页    /admin/index                          (302)
--- 基础设施 ---
✅ 验证码图片  /common/kaptcha                       (200)
✅ 静态资源    /mall/styles/header.css                (200)
=== 通过 11 / 失败 0 ===
```

**三条必须记录的行为特征（升级后必须保持一致）**：

1. `/goods/detail/*`、`/shop-cart`、`/personal` 未登录时 **302 → /login**（登录拦截器所致），**不是故障**；升级后若变成 200/404，说明拦截器配置坏了。
2. 中文搜索关键字必须 **URL 编码**：Windows `curl.exe` 直接传中文会被 codepage 破坏 → 实测返回 **400**。
3. **WSL 无法访问 Windows 的 loopback**：实测 WSL 直连 `127.0.0.1:28089` 与「网关 IP `172.21.0.1:28089`」均返回 `000`，只有 Windows 自带的 `curl.exe` 能拿到 200。因此 `ops/smoke.sh` 优先使用 `/mnt/c/Windows/System32/curl.exe` 并将响应体落到仓库内临时文件。

## 2. 迁移清单（实际执行）

| 项 | 现值 | 目标 | 实际改动量 |
|---|---|---|---|
| `spring-boot-starter-parent` | 2.7.5 | 3.5.16 | 1 行 |
| `java.version` | `1.8` | `21` | 1 行 + 新增 release 属性 |
| `javax.servlet.*` / `javax.annotation.*` | 44 处 | `jakarta.*` | 24 文件 44 行 |
| `mybatis-spring-boot-starter` | 2.2.2 | 3.0.5 | 1 行 |
| `hutool-captcha` | 5.8.7 | 5.8.47 | 1 行 |
| MySQL 驱动坐标 | `mysql:mysql-connector-java` | `com.mysql:mysql-connector-j` | 2 行 |
| `spring-session-core` | 存在 | 删除 | 5 行 |
| `maven-compiler-plugin` | 显式 source/target | 仅留 encoding | 3 行 |
| `spring.redis.*` | 3 个键 | `spring.data.redis.*` | 3 行 |
| 数据库密码 | 明文 `REDACTED_PASSWORD` | `${DB_PASSWORD:}` | 1 行 |
| `agent.api-key` | 存在（无主） | 删除 | 1 行 |
| 10 个 MyBatis XML | — | **未改**（不需改） | 0 |

## 3. 遇到的实际问题与解法

| # | 现象 | 根因 | 解法 |
|---|---|---|---|
| 1 | PLAN.md 里的 Maven 路径全部失效（`.m2/wrapper/...` 不存在） | 该目录已被 **2026-09-13 的 C 盘清理**删除（清理记录里有「m2 0.39 GB」） | 下载 Apache Maven 3.9.16 到 `D:\tools\`（**不碰 C 盘**），并封装 `ops/mvn.sh` 统一转发 |
| 2 | **WSL 完全访问不了 Windows 的 28089**：直连 `127.0.0.1` 与「网关 IP `172.21.0.1`」均返回 `000` | WSL2 默认 NAT 网络 + 主机防火墙 | 冒烟脚本改用 Windows 自带的 `/mnt/c/Windows/System32/curl.exe`（实测唯一可行路径）；响应体落到仓库内临时文件供双侧读取 |
| 3 | **应用启动成功但连不上 MySQL**（`CannotGetJdbcConnectionException`），页面仍返回 200（静默降级） | **WSL 的环境变量不会自动传给 Windows 子进程**——`DB_PASSWORD` 跨边界后为空。用 `DB_PASSWORD=x powershell -Command '$env:DB_PASSWORD'` 对比实测：不带 `WSLENV` 读到 `[]`，带 `DB_PASSWORD/w` 读到 `[test123]` | `ops/mvn.sh` 内 `export WSLENV="${WSLENV:+$WSLENV:}DB_PASSWORD/w"`。**教训**：`ops/mvn.sh` 最初用命令行插值传密码（有注入面）→ 审计建议删掉 → 删后发现密码压根传不过去 → 正解是 WSLENV（既无注入面又能传递） |
| 4 | `mvn dependency:tree -Dincludes=a:b` 报 `No plugin found for prefix '.springframework.boot'` | `ops/mvn.sh` 的 `$*` 未加引号，参数在 PowerShell 里被 `:` 二次解析拆散 | 逐参数单引号包裹后再拼接（并对含 `%` 的参数显式拒绝，防 cmd.exe 展开） |
| 5 | `ops/mvn.sh` 在 Git Bash 下 `wslpath: command not found`（exit 127） | 脚本用了 WSL 专有命令且未做分支 | 加分支：WSL 用 `wslpath -w`，Git Bash/MSYS 用 `pwd -W`，其它环境明确拒绝并提示用原生 mvn |
| 6 | WSL 里 `/mnt/c/.../bin/java -version` 报「No such file or directory」 | WSL 不像 Git Bash 会自动补 `.exe` | JDK 可执行文件一律带 `.exe`（`java.exe` / `javap.exe`） |
| 7 | 中文查询返回 400 | Windows `curl.exe` 命令行收中文会被 codepage 破坏 | 冒烟脚本里中文查询参数改用 **URL 编码** |
| 8 | 商品详情 / 购物车 / 个人中心返回 302（曾被误判为故障） | 这三个路径**受登录拦截器保护**（正常行为） | 基线期望值改为 302，并写进 §1.3 的行为特征 |
| 9 | 升级把 Redis 客户端 Lettuce 从 6.1 抬到 6.6，而服务端是本机 Redis **3.0.504**（不支持 `HELLO 3`） | 迁移清单未覆盖的运行时风险 | 写探针实测：RESP3 协商失败自动降级，连接与 SET/GET 均正常 → **不构成障碍** |

## 4. 验证证据

| 项 | 证据 |
|---|---|
| 编译 | `bash ops/mvn.sh -q clean compile` → **BUILD SUCCESS**（exit=0） |
| 字节码版本 | `javap.exe -verbose ...Constants.class` → **major version: 65**（Java 21，证明 `maven.compiler.release=21` 生效） |
| 产物完整性 | 旧 100 java → 104 class；新 98 java → 102 class；集合差集 = 仅 `AgentApiController.class` + `AgentCsModelAdvice.class`（Task 3 删除所致），**无缺无多** |
| 依赖健康 | `dependency:tree`：零 `omitted for conflict/duplicate`；spring-data-redis 3.5.13 + lettuce 6.6.0 + HikariCP 6.3.3 |
| 运行 | `Tomcat started on port 28089`，`Started NewBeeMallApplication in 4.057 seconds`；容器由 **Tomcat 9.0.68 → 10.1.55** |
| **冒烟回归** | **升级前 11/11 → 升级后 11/11**（逐项一致，见 §1.3） |
| 首页缓存 | `FLUSHDB` 后访问首页 → DBSIZE=5（`mall:index:carousel` / `mall:index:category` / `mall:index:goods:3,4,5`），`TTL mall:index:carousel` = **1798s**（≈30 分钟） |
| 运行期异常 | 应用日志中 `exception\|error` 计数 = **0** |
| 硬编码密钥 | `grep -rn "REDACTED_PASSWORD\|agent.api-key" mall-backend/src/` → **零命中** |

**待人工/延后验证**：
- 订单超时链路（下单 → ZSet）需浏览器登录下单（验证码阻碍自动化）；当前 `ZCARD mall:order:delay = 0`（无未支付订单，属正常），队列可读写已验证
- 容器内验证码字体（Docker 未安装）→ 延后 M3

## 5. 回退方式

M1 全部改动均为分层独立提交，**任一步可单独回退**：

```
ac42f0a docs: 回填 F1——javax 迁移实际 44 处
94b9c23 config: 迁移 Boot 3 配置键、外置数据库密码、删除无主 agent.api-key
8f7fd1e refactor: javax 迁移到 jakarta（44 处，ImageIO 保留）
f6732e8 fix: 采纳 claude 审计 P1/P2/P3，记录 P4/P5
4631c0c fix: ops/mvn.sh 参数引号缺陷
fe38ef2 build: 升级 Spring Boot 3.5.16 + Java 21
83cc124 test: 新增冒烟回归脚本 + 升级前基线（11/11）
b3f5db7 chore: 拷入 newbee-mall 基线（Boot 2.7.5/Java 8）
741fe56 fix: 修正基线差异审查方法
88f6db6 fix: 修正失效的 Maven/JDK 路径，补齐忽略规则
e020b87 chore: 统一行尾策略与忽略规则
675392b docs: 新增 M1 实现计划
07645fb docs: 新增 v1.1 设计规格
```

**回退粒度**：
- 只回退配置 → `git revert 94b9c23`
- 只回退 jakarta（回到 Boot 3 + javax，编译不过）→ `git revert 8f7fd1e`
- 整体回到升级前 → `git reset --hard b3f5db7`（此时 pom 仍是 2.7.5 / Java 8）

**旧仓库未受任何影响**（全程只读），随时可作为对照基线。

## 6. 上游参考与未采纳项

**上游参考**：`newbee-mall` 的 `upstream/spring-boot-3.x` 分支（Spring Boot 3.1.0 + Java 17）
- 只参考，**未 merge**：与本仓库差异达 **128 文件 / -13526 行**，merge 会冲掉本地全部定制（UI 重设计、Redis 缓存、ZSet 订单超时、SQL 索引、商品评价）
- 实际只借用了两点：Java 17+ 的可行性判断；Boot 3 的依赖坐标改名方向

**未采纳项（显式留痕，不静默丢弃）**：

| 项 | 建议方 | 未采纳原因 |
|---|---|---|
| 将 Redis 选型从 Redis Stack 改为 `redis:8` | 设计评审 | **已采纳**（写入 DESIGN §2#5），不是未采纳项——此处仅说明选型变更已落地 |
| P5：把 Maven 坐标 `ltd.newbee.mall:newbee-mall` 改为 `newbee-mall-ai` | claude 审计（低优先） | 改名会改变构建产物 jar 名，需同步 Dockerfile / compose；**留到 M3 容器化时一并决定** |
| 用 `spring-session-data-redis` 替换 `spring-session-core` | 初版计划 | 改为**直接删除**：实测全仓零引用，替换会引入未经设计的 session 搬移行为 |
| 升级后立即跑虚拟线程压测 | 设计 §6.3 | 属 M3 范围；M1 只需保证功能不回退 |

## 附录：基线选择（Task 2 产出）

| 文件 | 真实改动内容 | 决定 | 理由 |
|---|---|---|---|
| **CSS 组（12 个，必须整组带）** | | |
| `css/common.css` | CSS token 定义改名 | ✅ **带** | token 被下面 9 个 CSS 共享 |
| `css/themes.css`（新增） | 主题 token 定义 | ✅ **带** | 同上 |
| `styles/header.css` | 头部样式 v2 | ✅ **带** | 与 header.html v2 配套 |
| `styles/cart.css`、`detail.css`、`index.css`、`login.css`、`my-orders.css`、`order-detail.css`、`pay-select.css`、`personal.css`、`search.css`（9 个） | 消费改名后的 token | ✅ **带（整组，不可拆）** | **拆开即破 9 个页面的背景色** |
| **模板（5 个）** | | |
| `templates/mall/header.html` | v2 头部 + 「智能客服」链接 | ⚠️ **带但改**（摘除客服链接） | M3 改为原生浮窗入口 |
| `templates/mall/footer.html` | 含 `nb-cs-widget` iframe 浮窗块 | ⚠️ **带但改**（摘除浮窗块） | M3 用 Thymeleaf fragment 重写 |
| `templates/mall/cart.html`、`login.html`、`register.html` | 各 +2 行 `themes.css` 引用 | ✅ **带** | 不带上会 404 |
| **配置与文档（2 个）** | | |
| `application.properties` | 仅 `agent.cs-url` 2 行 | ❌ **不带（用 HEAD 版）** | Task 5/7 基于 HEAD 升级，叠加未提交改动会让 diff 变脏；M3 走原生融合也不需要该配置 |
| `docs/DESIGN.md` | 商城 UI v2 规格（+29 行） | ✅ **带，但改名 `docs/MALL-UI-SPEC.md`** | ⚠️ **绝不能**覆盖新仓库的 `docs/DESIGN.md`（那是 v1.1 设计规格） |
| **未跟踪新增（4 个）** | | |
| `css/themes.css` | 主题 token | ✅ **带** | 见上 |
| `js/cs-widget.js` | iframe 浮窗实现 | ❌ **不带** | 决策 #7：M3 改原生融合 |
| `styles/cs-widget.css` | 同上 | ❌ **不带** | 同上 |
| `controller/agent/AgentCsModelAdvice.java` | 注入 `agentCsUrl` | ❌ **不带** | 与 iframe 方案绑定，M3 重新实现 |
| **已提交但需删（1 个）** | | |
| `controller/agent/AgentApiController.java` | `/api/agent` 三接口 | 🗑️ **删除** | 决策 #18：内部改直调 Service |
