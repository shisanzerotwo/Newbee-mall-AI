# Spring Boot 2.7.5 → 3.5 / Java 8 → 21 升级实战

> 状态：M1 进行中（草稿，Task 11 完善）

## 1. 升级前的实测基线
### 1.1 依赖与版本
（待补）
### 1.2 javax 使用分布
（待补）
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

## 2. 迁移清单
（待补）

## 3. 遇到的实际问题与解法
（待补）

## 4. 验证证据
（待补）

## 5. 回退方式
（待补）

## 6. 上游参考与未采纳项
（待补）

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
