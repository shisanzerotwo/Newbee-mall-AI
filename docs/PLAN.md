# newbee-mall-ai 实现计划（M1：升级跑通）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在新建仓库 `D:\GitHub\xiangmu\newbee-mall-ai` 中，把 newbee-mall 从 Spring Boot 2.7.5 + Java 8 升级到 **Spring Boot 3.5.x + Java 21**，且商城全部功能（前台 12 页 + 后台 8 页 + Redis 缓存 + ZSet 订单超时）回归通过。

**Architecture:** 拷贝 newbee-mall 源码到 `mall-backend/` 子目录作为唯一应用；升级分 5 步独立提交（基线 → pom → jakarta → 配置 → 回归），每步可单独回退；用一份可重复执行的冒烟脚本作为进度判据，并**在升级前先对旧仓库跑一次建立基线**（阳性对照）。

**Tech Stack:** Java 21（本机 JDK 25 编译 `--release 21`）· Spring Boot 3.5.x · MyBatis Spring Boot Starter 3.0.x · Thymeleaf 6 · MySQL 9.7 · Redis（本机 3.0.504 开发期）· Maven

**设计依据：** `docs/DESIGN.md` v1.1（§6 升级方案 / §6.3 已知坑 / §6.4 依赖预检）

---

## 前置检查（开工前确认，任一不满足就先解决）

> **2026-09-17 实测校正**：原计划中的 Maven 路径已失效（`.m2/wrapper` 被 C 盘清理删除），本文档已同步修正。

- [x] JDK 25 可用：`/mnt/c/Users/22421/.jdks/openjdk-25/bin/java.exe -version` → `openjdk 25`（**必须带 `.exe`**）
- [x] Maven 3.9.16 可用：`D:\tools\apache-maven-3.9.16`（本次装入）→ **必须经 `bash ops/mvn.sh` 调用**（本机 WSL 无 Linux Maven/JDK）
- [x] MySQL 在跑：3306 监听中（`E:\mysql-9.7.1`）
- [x] Redis 在跑：6379 监听中（本机 3.0.504）
- [ ] **Docker Desktop 未安装**（实测）→ M1 不依赖；Task 10 的容器字体项标记「延后到 M3」，并在 README 记 TODO
- [ ] 数据库密码：Task 9 启动前需提供 `DB_PASSWORD`（Task 7 已把密码外置为环境变量）

---

## File Structure（M1 结束时）

```
newbee-mall-ai/
├── .gitattributes                    # 统一 LF（决策 #37）
├── .gitignore
├── docs/
│   ├── DESIGN.md                     # v1.1（已提交 07645fb）
│   ├── PLAN.md                       # 本文件
│   └── UPGRADE-BOOT3.md              # Task 11 产出
├── ops/
│   ├── mvn.sh                        # Maven 包装器（WSL → PowerShell → Windows Maven）
│   └── smoke.sh                      # Task 4 产出（冒烟脚本）
└── mall-backend/
    ├── pom.xml                       # Task 5 修改
    └── src/                          # Task 3 拷入
        ├── main/java/ltd/newbee/mall/…
        ├── main/resources/application.properties   # Task 7 修改
        └── test/java/…
```

**职责边界**：`ops/mvn.sh` 只做「WSL → Windows Maven」的环境转发，不含构建逻辑；`ops/smoke.sh` 只做「URL → 期望状态码/关键字」判定，不含业务逻辑；`mall-backend/` 是唯一应用；文档与运维脚本留在仓库根。

---

## Task 1: 仓库骨架与行尾策略

**Files:**
- Create: `.gitattributes`
- Create: `.gitignore`

- [ ] **Step 1: 写 `.gitattributes`（防重演旧仓库 310 文件行尾噪声）**

```
* text=auto eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf
*.ps1 text eol=crlf
*.jar binary
*.png binary
*.jpg binary
*.gif binary
*.ico binary
*.woff binary
*.woff2 binary
```

- [ ] **Step 2: 写 `.gitignore`**

```
# 构建产物
target/
*.class
*.jar
!.mvn/wrapper/maven-wrapper.jar

# IDE
.idea/
*.iml
.vscode/
*.log

# 本地配置与密钥
.env
*.local.properties

# 运行时数据
data/
ops/*.bak
```

- [ ] **Step 3: 提交**

```bash
cd "D:/GitHub/xiangmu/newbee-mall-ai"
git add .gitattributes .gitignore
git commit -m "chore: 统一行尾策略与忽略规则"
```

---

## Task 2: 基线差异审查（决定拷贝哪个版本）

**背景：** `newbee-mall` 工作区有 **310 个 M + 4 个未跟踪**，但其中 **只有 18 个文件是真实内容改动**，其余全是 CRLF 行尾噪声（本机 `core.autocrlf=true` 所致；第三方 vendor 文件如 `adminlte.css`/`chart.js`/`select2` 不可能被改动）。**不能盲目拷工作区，也不能盲目拷 HEAD。**

**Files:**
- Read: `D:/GitHub/xiangmu/newbee-mall`（只读，不改动旧仓库）

- [ ] **Step 1: 逐文件检测真实内容差异（剔除 CR 后比对）**

> ⚠️ **不要用 `git diff --numstat` 的 `$1 != $2` 筛选**：那会漏掉「行内替换」（增删行数相等，如 CSS token 改名 `--bg-surface` → `--surface`）。本次实测就因此漏掉了 **9 个 CSS 文件**，直接后果是 9 个页面前台背景色错乱。

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall"
for f in $(git status --porcelain | grep "^ M" | awk '{print $2}'); do
  if ! git show "HEAD:$f" 2>/dev/null | tr -d '\r' | diff -q - <(tr -d '\r' < "$f") >/dev/null 2>&1; then
    echo "REAL: $f"
  fi
done
```

Expected（2026-09-17 实测，共 **18 个**）：
```
REAL: docs/DESIGN.md
REAL: src/main/resources/application.properties
REAL: src/main/resources/static/mall/css/common.css
REAL: src/main/resources/static/mall/styles/cart.css
REAL: src/main/resources/static/mall/styles/detail.css
REAL: src/main/resources/static/mall/styles/header.css
REAL: src/main/resources/static/mall/styles/index.css
REAL: src/main/resources/static/mall/styles/login.css
REAL: src/main/resources/static/mall/styles/my-orders.css
REAL: src/main/resources/static/mall/styles/order-detail.css
REAL: src/main/resources/static/mall/styles/pay-select.css
REAL: src/main/resources/static/mall/styles/personal.css
REAL: src/main/resources/static/mall/styles/search.css
REAL: src/main/resources/templates/mall/cart.html
REAL: src/main/resources/templates/mall/footer.html
REAL: src/main/resources/templates/mall/header.html
REAL: src/main/resources/templates/mall/login.html
REAL: src/main/resources/templates/mall/register.html
```

- [ ] **Step 2: 列出未跟踪文件**

```bash
git status --porcelain | grep "^??"
```

Expected:
```
?? src/main/java/ltd/newbee/mall/controller/agent/AgentCsModelAdvice.java
?? src/main/resources/static/mall/css/themes.css
?? src/main/resources/static/mall/js/cs-widget.js
?? src/main/resources/static/mall/styles/cs-widget.css
```

- [ ] **Step 3: 逐项决定（按下表勾选，写进 `docs/UPGRADE-BOOT3.md` 的「基线选择」节）**

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

- [ ] **Step 4: 把上面的决定表写入 `docs/UPGRADE-BOOT3.md` 草稿（Task 11 完善）**

```bash
mkdir -p "/mnt/d/GitHub/xiangmu/newbee-mall-ai/docs"
# 手工把 Step 3 的表格粘进文件
```

---

## Task 3: 拷贝源码 + 基线提交

**Files:**
- Create: `mall-backend/pom.xml`, `mall-backend/src/**`（从旧仓库拷贝）

- [ ] **Step 1: 拷贝 HEAD 版本到 mall-backend/**

```bash
set -e
SRC="/mnt/d/GitHub/xiangmu/newbee-mall"
DST="/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
mkdir -p "$DST"
cd "$SRC"
# 用 git archive 取 HEAD（干净、可追溯，不含工作区噪声）
git archive HEAD pom.xml src | tar -x -C "$DST"
echo "文件数: $(find "$DST" -type f | wc -l)"
```

Expected: 输出文件数（约 200+，不含 target/）

- [ ] **Step 2: 应用 Task 2 决定的「带」项（从旧仓库工作区覆盖）**

```bash
SRC="/mnt/d/GitHub/xiangmu/newbee-mall"
DST="/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
R="/mnt/d/GitHub/xiangmu/newbee-mall-ai"

# —— 主题 CSS 组：11 个真实改动 + 1 个新增，必须整组（token 改名跨文件耦合）——
for f in common.css themes.css; do
  cp "$SRC/src/main/resources/static/mall/css/$f" "$DST/src/main/resources/static/mall/css/"
done
for f in header.css cart.css detail.css index.css login.css my-orders.css \
         order-detail.css pay-select.css personal.css search.css; do
  cp "$SRC/src/main/resources/static/mall/styles/$f" "$DST/src/main/resources/static/mall/styles/"
done

# —— 模板（header/footer 随后手工摘除客服块）——
for f in header.html footer.html cart.html login.html register.html; do
  cp "$SRC/src/main/resources/templates/mall/$f" "$DST/src/main/resources/templates/mall/"
done

# —— 商城 UI 规格：改名，绝不可覆盖 docs/DESIGN.md ——
cp "$SRC/docs/DESIGN.md" "$R/docs/MALL-UI-SPEC.md"

# —— 明确不带 ——
#   application.properties      保持 git archive 的 HEAD 版（Task 7 再改）
#   cs-widget.js / cs-widget.css / AgentCsModelAdvice.java   决策 #7：M3 重写

# —— 校验：应拷入 12 个 CSS ——
echo "styles/ 下 CSS 数: $(find "$DST/src/main/resources/static/mall/styles" -name '*.css' | wc -l)"
```

- [ ] **Step 3: 删除按设计应移除的接口（决策 #18）**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
rm mall-backend/src/main/java/ltd/newbee/mall/controller/agent/AgentApiController.java
echo "已删除 AgentApiController（可从 git 历史取回：newbee-mall commit ca178f4）"
```

- [ ] **Step 4: 从 footer.html 摘掉 iframe 浮窗（M1 只保证可编译；M3 重写）**

编辑 `mall-backend/src/main/resources/templates/mall/footer.html`，删除：
- `<div id="nb-cs-widget" …>` 整块（含 `nbCsPanel` / `nbCsFrame` / `nbCsFab`）
- 底部 `<link rel="stylesheet" th:href="@{/mall/styles/cs-widget.css}">` 与 `<script th:src="@{/mall/js/cs-widget.js}">`

- [ ] **Step 5: 从 header.html 摘掉客服链接与对应模型属性**

编辑 `mall-backend/src/main/resources/templates/mall/header.html`，删除：
```html
<a class="cs-link" th:href="${agentCsUrl}" target="_blank" rel="noopener">智能客服</a>
```

- [ ] **Step 6: 从 footer.html 摘掉客服相关文案块**

编辑 `footer.html`，删除「智能客服 / AI 小蜂 / 在线咨询」那一组 `<p>` 与 `<a th:href="${agentCsUrl}">`。

- [ ] **Step 7: 确认零残留**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
grep -rn "agentCsUrl\|nb-cs-widget\|cs-widget" src/ || echo "✅ 零残留"
```

Expected: `✅ 零残留`

- [ ] **Step 8: 基线提交**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
git add -A
git commit -m "chore: 拷入 newbee-mall 基线（Boot 2.7.5/Java 8，移除旧 iframe 客服集成）"
git log --oneline | head -3
```

---

## Task 4: 冒烟脚本（升级前先建立阳性对照）

**Files:**
- Create: `ops/smoke.sh`

**为什么先做：** 升级后要证明「没坏」，前提是升级前能证明「本来好的」。没有基线的回归等于没测。

- [ ] **Step 1: 写冒烟脚本**

```bash
#!/usr/bin/env bash
# newbee-mall-ai 冒烟回归：URL → 期望状态码 / 关键字
# 用法：bash ops/smoke.sh [base_url]
set -u
BASE="${1:-http://127.0.0.1:28089}"
PASS=0; FAIL=0

check() {  # check <名称> <路径> <期望状态码> [关键字]
  local name="$1" path="$2" want="$3" kw="${4:-}"
  local code body
  body=$(curl -s -o /tmp/smoke_body.txt -w "%{http_code}" "$BASE$path" 2>/dev/null || echo "000")
  code="$body"
  if [ "$code" != "$want" ]; then
    echo "❌ $name  $path  期望 $want 实得 $code"; FAIL=$((FAIL+1)); return
  fi
  if [ -n "$kw" ] && ! grep -q "$kw" /tmp/smoke_body.txt; then
    echo "❌ $name  $path  状态码 $code 但缺少关键字「$kw」"; FAIL=$((FAIL+1)); return
  fi
  echo "✅ $name  $path  ($code)"; PASS=$((PASS+1))
}

echo "=== 冒烟回归 @ $BASE ==="
# —— 前台 ——
check "首页"        "/"                     200 "新蜂商城"
check "商品搜索"    "/search?keyword=化妆水" 200
check "商品详情"    "/goods/detail/10003"   200
check "购物车页"    "/shop/cart"            200
check "登录页"      "/login"                200
check "注册页"      "/register"             200
check "个人中心"    "/personal"             302   # 未登录应重定向
# —— 后台 ——
check "后台登录"    "/admin/login"          200
check "后台首页"    "/admin/index"          302   # 未登录应重定向
# —— 基础设施 ——
check "验证码图片"  "/common/kaptcha"       200
check "静态资源"    "/mall/styles/header.css" 200
echo "=== 通过 $PASS / 失败 $FAIL ==="
[ "$FAIL" -eq 0 ]
```

- [ ] **Step 2: 对旧仓库跑一次，建立基线（阳性对照）**

```bash
# 终端 A：在旧仓库启动（HEAD 版本）—— 旧仓库没有 ops/mvn.sh，直接用 PowerShell
powershell.exe -NoProfile -Command "cd 'D:\GitHub\xiangmu\newbee-mall'; \$env:JAVA_HOME='C:\Users\22421\.jdks\openjdk-25'; & 'D:\tools\apache-maven-3.9.16\bin\mvn.cmd' spring-boot:run"
# 终端 B（用绝对路径，因为当前不在 newbee-mall-ai 目录）：
bash /mnt/d/GitHub/xiangmu/newbee-mall-ai/ops/smoke.sh http://127.0.0.1:28089
```

Expected: 记录实际通过/失败数，写入 `docs/UPGRADE-BOOT3.md` 的「升级前基线」
**注意**：若旧仓库因 Java 8 → JDK 25 运行报错，改为用旧环境跑；基线必须拿到，否则后续对比无意义。

- [ ] **Step 3: 提交脚本**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
git add ops/smoke.sh
git commit -m "test: 新增冒烟回归脚本（升级前后对照用）"
```

---

## Task 5: pom 升级（Boot 3.5 + Java 21）

**Files:**
- Modify: `mall-backend/pom.xml`

- [ ] **Step 1: 先记录当前关键版本（写进升级文档）**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
grep -nE "spring-boot-starter-parent|<version>2\.7|java.version|mybatis.start.version|hutool-captcha.version" pom.xml
```

Expected: 输出 `2.7.5` / `1.8` / `2.2.2` / `5.8.7`

- [ ] **Step 2: 改父 POM 版本与 Java 版本**

把：
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.7.5</version>
    <relativePath/>
</parent>
```
改为（Boot 3.5 最新补丁版以实际可用为准）：
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.6</version>
    <relativePath/>
</parent>
```

把：
```xml
<java.version>1.8</java.version>
```
改为：
```xml
<java.version>21</java.version>
<maven.compiler.release>21</maven.compiler.release>
```

- [ ] **Step 3: 删掉 maven-compiler-plugin 的显式 source/target（关键：避免用高版本 API 而不报错）**

删除整个 plugin 块：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.11.0</version>
    <configuration>
        <source>${java.version}</source>
        <target>${java.version}</target>
        <encoding>UTF-8</encoding>
    </configuration>
</plugin>
```
改为只保留编码（`release` 由父 POM + `maven.compiler.release` 属性生效）：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <encoding>UTF-8</encoding>
    </configuration>
</plugin>
```

- [ ] **Step 4: 升级依赖版本属性与坐标**

```xml
<mybatis.start.version>3.0.4</mybatis.start.version>
<hutool-captcha.version>5.8.40</hutool-captcha.version>
```

MySQL 驱动坐标改名：
```xml
<!-- before -->
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
</dependency>
<!-- after -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>
```

- [ ] **Step 5: 删除死依赖 `spring-session-core`**

实测：全仓零 `org.springframework.session` 引用 → 它是**死依赖**。**不要**换成 `spring-session-data-redis`（那会把 session 自动搬到 Redis，属于行为变更）。

```xml
<!-- 整块删除 -->
<dependency>
    <groupId>org.springframework.session</groupId>
    <artifactId>spring-session-core</artifactId>
</dependency>
```

- [ ] **Step 6: 提交**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
git add mall-backend/pom.xml
git commit -m "build: 升级 Spring Boot 3.5 + Java 21（依赖改名、删死依赖、改用 release）"
```

---

## Task 6: `javax` → `jakarta` 迁移（44 处）

**Files:**
- Modify: `mall-backend/src/main/java/**`（25 个文件）

- [ ] **Step 1: 先确认待改范围（实测 44 处）**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
grep -rhoE "javax\.[a-z]+\.[a-zA-Z.]+" src/main/java | sort | uniq -c | sort -rn
```

Expected:
```
20 javax.servlet.http.HttpServletRequest
13 javax.annotation.Resource
 7 javax.servlet.http.HttpSession
 4 javax.servlet.http.HttpServletResponse
 1 javax.imageio.ImageIO      ← 不改（java.desktop，非 Jakarta EE）
```

> 注：最初的 46 处估算含 `AgentApiController.java` 的 2 处 import；该文件已在 Task 3 删除（决策 #18），故实际为 **44 处**（已实测核实）。

- [ ] **Step 2: 批量替换（只替换 Jakarta 相关的三类前缀）**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
grep -rl "javax\.servlet\|javax\.annotation" src/main/java | while read -r f; do
  sed -i 's/\bjavax\.servlet\./jakarta.servlet./g; s/\bjavax\.annotation\./jakarta.annotation./g' "$f"
done
```

- [ ] **Step 3: 验证 ImageIO 未被误改，且 javax 只剩它**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
grep -rn "javax\." src/main/java
```

Expected: 只剩 1 行 `import javax.imageio.ImageIO;`

- [ ] **Step 4: 全量检查 jakarta 改动数**

```bash
grep -rn "jakarta\." src/main/java | wc -l
```

Expected: `44`

- [ ] **Step 5: 提交**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
git add -A mall-backend/src
git commit -m "refactor: javax 迁移到 jakarta（44 处，ImageIO 保留）"
```

---

## Task 7: 配置键迁移（Boot 3 格式）

**Files:**
- Modify: `mall-backend/src/main/resources/application.properties`

- [ ] **Step 1: 定位需迁移的键**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
grep -nE "spring\.redis|spring\.datasource\.driverClassName|spring\.session|agent\.api-key" src/main/resources/application.properties
```

Expected: 4-5 行（`spring.redis.host/port/database` + `agent.api-key`）

- [ ] **Step 2: 迁移 Redis 前缀**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
sed -i 's/^spring\.redis\./spring.data.redis./' src/main/resources/application.properties
grep -n "spring.data.redis" src/main/resources/application.properties
```

Expected:
```
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.database=0
```

- [ ] **Step 3: 顺手把密码改为环境变量占位（决策 #22：密钥不落盘）**

```properties
# before
spring.datasource.password=REDACTED_PASSWORD
# after（本机开发用环境变量或 application-local.properties，已 gitignore）
spring.datasource.password=${DB_PASSWORD:}
```

**注意**：改完必须设置环境变量 `DB_PASSWORD` 才能启动，否则连库失败。
本机验证时：`export DB_PASSWORD='<向用户索取>'`

- [ ] **Step 4: 删除无主配置 `agent.api-key`（Task 3 的尾巴，与决策 #18 对齐）**

`AgentApiController` 已删（决策 #18），模板里的 `agentCsUrl` / `nb-cs` 也已清干净，但 `application.properties:31` 仍留着：

```properties
# 整行删除
agent.api-key=REDACTED_KEY
```

> 理由：死配置。它会拖累 DoD 的「无任何硬编码密钥（grep 核查）」——一个名为 `api-key` 的条目必然被 grep 命中，届时要么误报要么被迫人工豁免。

- [ ] **Step 5: 提交**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
git add mall-backend/src/main/resources/application.properties
git commit -m "config: 迁移 Boot 3 配置键（spring.data.redis.*）、外置数据库密码、删除无主 agent.api-key"
```

> **待定（P5，低优先级）**：Maven 坐标 `ltd.newbee.mall:newbee-mall` 仍是旧名，与新仓库 `newbee-mall-ai` 不一致。改名会改变构建产物文件名（`newbee-mall-1.0.0-SNAPSHOT.jar`），需同步 Dockerfile / compose 里的 jar 名。**本 M1 暂不改**，待 M3 容器化时一并决定。

---

## Task 8: 编译验证（第一个硬门槛）

**Files:** 无（只验证）

- [ ] **Step 1: 编译**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
bash ops/mvn.sh -q clean compile 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`（无 `package javax.* does not exist`）

- [ ] **Step 2: 验证字节码版本是 21（证明 release 生效）**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai/mall-backend"
f=$(find target/classes -name "*.class" | head -1)
"/mnt/c/Users/22421/.jdks/openjdk-25/bin/javap.exe" -verbose "$f" | grep -m1 "major version"
```

Expected: `major version: 65`（Java 21）。若为 52（Java 8）或 69（Java 25）说明配置未生效，回 Task 5 Step 3。

- [ ] **Step 3: 依赖冲突预检（设计 §6.4）**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
bash ops/mvn.sh -q dependency:tree -Dincludes=redis.clients:jedis
bash ops/mvn.sh -q dependency:tree -Dincludes=org.springframework.session
```

Expected: jedis 版本被 Boot BOM 管理（M1 未引入 LangChain4j，暂不冲突）；`org.springframework.session` **无输出**（死依赖已删）

- [ ] **Step 4: 无代码改动则跳过提交；有 target/ 误入则清理**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
git status --short
```

Expected: 干净（`target/` 已被 .gitignore 覆盖）

---

## Task 9: 启动 + 冒烟回归（对照 Task 4 基线）

**Files:** 无（只验证）

- [ ] **Step 1: 启动应用**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
export DB_PASSWORD='<向用户索取>'      # ops/mvn.sh 会自动转发给 Windows 侧进程
bash ops/mvn.sh spring-boot:run
```

Expected: 日志出现 `Tomcat started on port(s): 28089`，**无** `jakarta.servlet` 相关 NoClassDefFoundError

- [ ] **Step 2: 跑冒烟脚本**

```bash
bash "/mnt/d/GitHub/xiangmu/newbee-mall-ai/ops/smoke.sh" http://127.0.0.1:28089
```

Expected: 与 Task 4 基线**逐项一致**（通过数 ≥ 基线）

- [ ] **Step 3: 失败项记录与修复**

任一项失败 → 把「URL / 期望 / 实得 / 异常栈」写入 `docs/UPGRADE-BOOT3.md` 的「升级后问题」节，修完后重跑 Step 2。**不要跳过失败项继续**。

- [ ] **Step 4: 验证前端渲染无残留服务端表达式**

```bash
curl -s http://127.0.0.1:28089/ | grep -c "th:" || echo "0（正确：模板已渲染）"
```

Expected: `0`

---

## Task 10: 缓存与订单超时专项验证（功能没被升级弄坏）

**Files:** 无（只验证）

- [ ] **Step 1: 验证 Redis 首页缓存命中**

```bash
# 清空缓存后首次访问，再访问一次
redis-cli -n 0 FLUSHDB
curl -s http://127.0.0.1:28089/ > /dev/null
redis-cli -n 0 DBSIZE
```

Expected: DBSIZE > 0（缓存写入生效）

- [ ] **Step 2: 验证缓存二次访问走命中（看日志）**

在应用日志中确认第二次访问**未**出现 `SELECT` 相关 SQL（首页数据来自缓存）。

- [ ] **Step 3: 验证 ZSet 订单超时队列可用**

```bash
redis-cli -n 0 ZCARD <ORDER_DELAY_QUEUE_KEY>
```

Expected: 命令成功（key 名以 `ltd.newbee.mall.common.Constants` 中的常量为准）

- [ ] **Step 4: 下单 → 观察订单号进入延迟队列**

在浏览器完成一次下单，再次执行 Step 3，Expected: 计数 +1。

- [ ] **Step 5: 把三项结果写入升级文档**

- [ ] **Step 6: 若 Docker 已安装，补验容器字体（Hutool 验证码）**

```bash
# 仅在 Docker 可用时执行
docker run --rm eclipse-temurin:21-jre sh -c "fc-list 2>/dev/null | head -3 || echo 'NO FONTS'"
```

Expected: 若输出 `NO FONTS`（或 `Fontconfig head is null`）→ 记入 `docs/UPGRADE-BOOT3.md` 的「M3 待办：镜像需装字体」
**Docker 未安装则跳过，并在 README 记 TODO。**

---

## Task 11: 升级实战文档

**Files:**
- Create: `docs/UPGRADE-BOOT3.md`（Task 2 已起草）
- Modify: `README.md`（新建）

- [ ] **Step 1: 完成 `docs/UPGRADE-BOOT3.md`，必须包含 6 节**

```markdown
# Spring Boot 2.7.5 → 3.5 / Java 8 → 21 升级实战

## 1. 升级前的实测基线
- 依赖清单（8 个直接依赖 + 版本）
- javax 使用分布（44 处，附 grep 命令与输出）
- 冒烟基线（通过 X / 失败 Y）

## 2. 迁移清单（做了什么）
（表格：项 / 现值 / 目标 / 改动量）

## 3. 遇到的实际问题与解法
（每条：现象 / 根因 / 解法 / 证据）
至少覆盖：maven-compiler-plugin 的 source/target、spring-session-core 死依赖、
           Spring 6 尾斜杠匹配、Hutool 版本、MySQL 驱动坐标改名

## 4. 验证证据
- `mvn clean compile` 输出（BUILD SUCCESS）
- 字节码 major version: 65
- 冒烟回归对比表（升级前 / 升级后）
- 缓存 & 订单超时验证结果

## 5. 回退方式
（每个 commit 单独可回退，给出 commit 列表与回退命令）

## 6. 上游参考与未采纳项
- upstream/spring-boot-3.x 只在哪些点上参考过、为何不 merge
- 未采纳建议及理由（不静默丢弃）
```

- [ ] **Step 2: 写 `README.md`（一图看懂 + 启动方式）**

必须包含：项目定位一句话、架构图（从 DESIGN.md §3.1 取）、当前里程碑状态（M1 完成 / M2 进行中）、启动命令、文档索引、Docker TODO。

- [ ] **Step 3: 提交**

```bash
cd "/mnt/d/GitHub/xiangmu/newbee-mall-ai"
git add README.md docs/UPGRADE-BOOT3.md
git commit -m "docs: 补齐升级实战记录与 README（M1 收尾）"
git log --oneline
```

---

## M1 完成定义（DoD）

- [ ] `mvn clean compile` BUILD SUCCESS，字节码 `major version: 65`
- [ ] 应用在 28089 启动，日志无 jakarta 相关异常
- [ ] 冒烟脚本通过数 **≥ 升级前基线**（逐项对照）
- [ ] 首页缓存命中 + ZSet 订单超时队列两项验证通过
- [ ] 仓库无硬编码密码（`grep -rn "REDACTED_PASSWORD" .` 零命中）
- [ ] `docs/UPGRADE-BOOT3.md` 六节齐全，含证据与回退方式
- [ ] `git log` 显示分层提交（骨架 / 基线 / 脚本 / pom / jakarta / 配置 / 文档）

---

## 后续里程碑（不在本计划范围，M1 验收后另写计划）

- **M2（客服核心）**：OmniRoute function calling spike → LangChain4j 官方 starter 装配 → **§6.4 依赖预检**（Jedis 冲突）→ RAG 建库（**语料红线：不含价格/库存**）→ 6 个 `@Tool` → 编排 + 质检（默认 `gate`）→ SSE（`done` 非终止 + 3s 兜底）→ ChatMemory 落库
- **M3（融合与工程化）**：浮窗 + `/cs`（Thymeleaf 原生）→ 上下文带入 → 可观测面板 → Docker Compose（`redis:8` + `temurin:21-jre` **含字体**）→ 虚拟线程压测（容器内）→ 中文嵌入对比 → Testcontainers + CI

**为什么 M2/M3 计划要等 M1**：M1 会实测出三个影响后续设计的事实——① LangChain4j 与 Boot 3.5 的实际依赖冲突（§6.4 预检结果）；② 容器字体缺失情况；③ 尾斜杠等 Spring 6 行为变更的实际影响。这些不落定，M2 的计划会写错。

---

## Self-Review（对照 DESIGN.md v1.1 自查）

| 设计条目 | 本计划覆盖 |
|---|---|
| §6.1 迁移清单 14 行 | Task 5（pom/依赖）、Task 6（44 处 javax）、Task 7（配置键）✅ |
| §6.2 步骤 6 步 | Task 3/5/6/7/9/11 一一对应 ✅ |
| §6.3 坑：compiler-plugin | Task 5 Step 3 ✅ |
| §6.3 坑：spring-session 死依赖 | Task 5 Step 5 ✅ |
| §6.3 坑：Hutool JDK 21 | Task 5 Step 4（升 5.8.40）✅ |
| §6.3 坑：容器字体 | Task 10 Step 6（Docker 可用时）✅ |
| §6.4 依赖预检 | Task 8 Step 3 ✅ |
| §6.2 步 4 冒烟回归 | Task 4（基线）+ Task 9（对照）✅ |
| 决策 #18 删 `/api/agent` | Task 3 Step 3 ✅ |
| 决策 #22 密钥外置 | Task 7 Step 3 ✅ |
| 决策 #24 旧仓库不动 | 全计划只读旧仓库 ✅ |
| 决策 #37 行尾策略 | Task 1 ✅ |

**未覆盖（有意）**：M2/M3 全部内容（另立计划）；Docker 相关（本机未装，标记延后）。
