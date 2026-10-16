# 新蜂商城前端重设计规格 v4 · TOOLINK（D2 专业工具风）

> 状态：**已落地**（2026-10-08）· 前身：`DESIGN-v3.md`（ONYX／电光紫／深色优先）
> 本文件是 v4 落地的唯一视觉依据。所有 CSS / 模板改动都可回溯到本文的 token 与组件规范。
>
> **v4 是一次换方向，不是迭代**：v3 的深色优先、电光紫、大圆角体系整体作废；
> 但 **DOM 结构、Thymeleaf 片段、类名约定、JS 事件契约全部保留**。

---

## 0. 为什么要换（v3 的三个硬伤）

| 硬伤 | 表现 | v4 的对策 |
|---|---|---|
| **同构复制型 AI 味** | 每张卡片一模一样、每个区块同一种布局 —— "千篇一律"的字面来源 | 区块结构差异化 + 信息架构差异化（L3/L4） |
| **紫粉渐变泛滥** | 品牌紫 + 辉光 `--glow` + 渐变铺底，是 2023–24 年 AI 生成 UI 的签名色 | 去彩色，强调色改「近黑」，删除全部渐变与辉光 |
| **圆角/阴影过重** | 卡片 18px、按钮 10px、胶囊按钮，靠柔影做层级 | 圆角收紧到 4–10px，层级改由 **1px 细线 + 字重 + 灰阶**重建 |

---

## 1. 设计方向

| 项 | 内容 |
|---|---|
| 名称 | **TOOLINK** |
| 关键词 | 浅色默认 · 中性灰阶 · 近黑强调 · 高密度 · 1px 线构建层级 |
| 氛围词 | 精确、克制、工程感。参考气质：Linear / Vercel / GitHub |
| 核心主张 | 商城与 AI 客服共用一套 token；客服是这套视觉里的原生能力 |
| 成功标准 | 视觉上"看不出是 AI 做的"，唯一裁判是"像不像真人设计师出的稿" |

### 1.1 中性灰阶（浅色默认）

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `--bg` | `#fafafa` | `#0a0a0b` | 页面底 |
| `--bg-1` | `#ffffff` | `#121214` | 卡片 / 面板 |
| `--bg-2` | `#f4f4f5` | `#1a1a1c` | 输入框 / 悬浮态 |
| `--bg-3` | `#ededef` | `#232326` | 标签底 / 三级面 |
| `--bd` | `#e4e4e7` | `#26262a` | 描边（默认） |
| `--bd-2` | `#d4d4d8` | `#38383d` | 描边（强调 / hover） |
| `--t1` | `#18181b` | `#fafafa` | 主文字 |
| `--t2` | `#52525b` | `#a1a1aa` | 次要文字 |
| `--t3` | `#6b6b75` | `#84848e` | **纯装饰**，禁止承载语义文字 |

> **规则（硬性）**：`--t3` 只允许用于序号、分隔符、placeholder、装饰性线条。
> 任何承载信息的文字（副标题、订单状态、时间、描述、表头）一律用 `--t2`。

### 1.2 强调色：近黑（无彩色）

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `--ink` | `#18181b` | `#ffffff` | 主按钮底、导航激活、链接 |
| `--ink-hi` | `#2f2f36` | `#e4e4e7` | hover 提亮 |
| `--ink-fg` | `#ffffff` | `#0a0a0b` | 压在 ink 上的前景 |
| `--ink-dim` | `rgba(24,24,27,.06)` | `rgba(250,250,250,.08)` | 选区、极弱底 |

**全站唯一彩色例外**：价格暖橙 `--price`（浅 `#c2410c` / 深 `#fb923c`）。
另：**AI 客服的用户消息气泡**用价格暖橙（`--price`），是设计上唯一允许上色的非价格元素。

### 1.3 语义色（对齐 v4 色板）

| Token | 浅色 | 深色 |
|---|---|---|
| `--price` | `#c2410c` | `#fb923c` |
| `--ok` | `#15803d` | `#4ade80` |
| `--warn` | `#a16207` | `#fbbf24` |
| `--bad` | `#dc2626` | `#f87171` |

### 1.4 形状与动效

| Token | 值 | v3 → v4 |
|---|---|---|
| `--r-lg` | 10px | 18 → 10 |
| `--r-md` | 8px | 14 → 8 |
| `--r-sm` | 6px | 10 → 6 |
| `--r-xs` | 4px | 8 → 4 |
| `--dur` | 120ms | 160 → 120 |
| `--ease` | `cubic-bezier(0.4,0,0.2,1)` | 收敛为标准缓动 |
| `--sh-1` | `0 1px 2px rgba(0,0,0,.06)` | 保留极弱阴影 |
| `--sh-2` | `0 8px 24px rgba(0,0,0,.12)` | 仅浮层使用 |
| `--grad-hero` | `none` | v3 有渐变铺底，v4 全删 |
| `--glow` | **已删除** | 紫色辉光，全站清零 |

---

## 2. 主题机制（关键翻转）

**v3**：`html[data-theme="light"]` 写浅色覆盖，深色为 `:root` 默认。
**v4**：`:root` 直接写**浅色默认**，`html[data-theme="dark"]` 写深色覆盖。

- `onyx.css` 是 token 的**唯一来源**。
- `themes.css` 只保留 `html[data-theme="dark"]` 一块。
- `header.html` 首帧主题脚本默认值翻转为 `"light"`（旧值 `"t3"` 视为浅色，兼容存量用户）。
- `color-scheme` 同步：`:root` = `light`，dark 覆盖里 = `dark`。

---

## 3. 旧 token 别名兼容层（低风险改造的核心手法）

`onyx.css:root` 保留旧 v1/v2/v3 token 名，全部**重定向到中性色**：

```css
--brand-300: var(--bg-3);   --brand-500/600/700/800: var(--ink);
--brand: var(--ink);        --brand-hi: var(--ink);
--accent-500: var(--price); --accent-50: var(--price-dim);
--bg-card: var(--bg-1);     --border-1: var(--bd);
--radius*: 新的小圆角;       --shadow-sm: none;
--ease-out: 120ms var(--ease);
```

**效果**：未逐行改动的 `styles/*.css` 自动去紫，无需模板改动即可完成主体换肤。
之后再做**语义化清理**（把 `var(--brand-hi)` 等替换成直白的 `var(--ink)`），提升可读性。

---

## 4. 去 AI 味动作清单（本项目的"负面清单"）

| # | 要清除的东西 | v4 做法 |
|---|---|---|
| 1 | 紫粉渐变 / 电光紫残留 | 全站 `--brand*` → `--ink`，紫值 grep 计数 = 0 |
| 2 | 紫色辉光 `--glow` | 变量删除，所有引用改 `--sh-2` 或直接删 |
| 3 | 卡片悬浮三件套（位移+变色+阴影） | 只保留 `border-color` 变化，删除 `translateY(-3px)` 与 hover 阴影 |
| 4 | 胶囊按钮 / 胶囊标签 | 圆角降到 4–6px，标签改方角带 1px 描边 |
| 5 | 彩色焦点环 | 焦点只改 `border-color`，删除 3px 光圈 |
| 6 | 假评价、假销量、假"XX 人正在浏览" | 首页状态条改用**真实数据**（商品数 / 分类数，Controller 注入） |
| 7 | 悬停浮现条、滚动入场动画 | 删除 `.comment` 悬停条，去掉滚动 fade-up |
| 8 | 区块标题"h2 + 渐变短线" | 换成「monospace 序号 `01 /` + 1px 延伸线」，形成系统感而非复制感 |
| 9 | 每张卡片一模一样 | 四区块四种骨架（见 §5） |
| 10 | 错误页巨型渐变数字 | 改单色 monospace + 左侧 3px 描边 |

---

## 5. 首页 L3/L4 差异化（治"同构复制"）

同一页四个区块，**骨架层面各不相同**：

| 区块 | 骨架 | 关键实现 |
|---|---|---|
| `#banner` 分类导航 | **侧栏索引** | 232px 固定宽，item 38px 高，hover 显示 `::before` 2px `--ink` 竖条（无彩色） |
| `#sub_banner` 热点宫格 | **非对称宫格** | `repeat(4,1fr)` × 148px 行高，`:first-child` 跨 2×2 |
| `#flash` 秒杀 | **横滑轨道** | `display:flex` + `scroll-snap`，`li` 190px，**首卡 392px**（非等宽） |
| `#recommend` 推荐 | **两列行式列表** | `repeat(2,1fr)`，行内 `64px 图 + 信息` 两栏，行间 1px 底边线 |

区块标题统一签名（5 个区块共用，制造系统感）：
- `h2::before` = `content: "01 /"`，monospace `--t3`
- `h2::after` = `flex:1` 的 1px `--bd` 延伸线（**取代 v3 的 32×3px 渐变短线**）

---

## 6. 各页改造要点

| 页面 | 改造 |
|---|---|
| 头部 `header.html` + `header.css` | 去 scrolled 阴影、blur 12px、h 60px；logo 从渐变改 `--ink` 实底 26×26 `--r-sm`；搜索框从胶囊改方正描边 + `--ink` 按钮；主题菜单 6 套 → 2 套；**修复搜索图标 `icon-search1`** |
| 首页 `index.html` + `index.css` | 四区块四骨架（§5）；新增 `#statusbar` 真实数据条；删除 11 处 `.comment` 悬停条；`price` 类名统一 `item_price` |
| 列表 `search.css` | 筛选 chip 胶囊 → `--r-xs` 方角；排序激活改 `--ink` 实底；卡片去 `translateY` + 阴影；图片去 `scale` |
| 详情 `detail.css` | `.item_price` 从大卡改为**信息行**（左侧 3px `--ink` 竖条 + 28px 数字）；评价区改 grid 两栏 + `::before` 24×24 头像占位 |
| 购物车 `cart.css` | 表头/提示从 `--t3` 提到 `--t2`；数量框 60×32 `--r-xs`；结算按钮 `--ink` 实底去辉光 |
| 订单/个人中心/登录/支付 | 用别名层自动去紫 + 语义化清理；焦点环去 3px；进度条胶囊 → `--r-xs` |
| 客服 `cs-widget.css` + `cs.css` | 去辉光、去渐变头、FAB 改 `--r-sm` 方角；**用户气泡保留价格暖橙**（唯一彩色例外）；`.cs-history__item.is-active` 改 `--ink` 实底 |
| 错误页 ×4 | 内联 `:root` 换浅色 token；`.code` 去渐变文字改单色 monospace 88–96px；`.btn` 改 `--ink` 实底 |
| 后台 `admin/dist/css/skin.css` | 后台只做浅色；`:root` 加 `--ink` 系；`--brand*` 重定向近黑；侧栏激活 `--ink` 实底；圆角 14/10/8 → 8/6/4；删 3px 焦点环 |

---

## 7. 守卫红线（不可触碰）

改造全程必须保住以下**字面量**（静态断言读取源文件）：

| 文件 | 契约字面量 |
|---|---|
| `cs-widget.css` | `var(--brand-500`、`var(--bg-card`、`var(--bg-page`、`380px`、`560px`、`.cs-fab`、`.cs-float` |
| `styles/cs.css` | `@import url("/mall/css/cs-widget.css");` 必须在**第一行**（`.cs-page` 之前） |
| `templates/mall/detail.html` | `问客服`、`/cs(goodsId=`、`id="cs-context"`、`data-goods-id=` |

**守卫测试**（本项目 4 个，改造后必须全绿）：
- `CsFrontendGuardTest`（5）
- `CsWidgetGuardTest`（11）
- `CsWidgetInjectionTest`（5，端到端，**需 MySQL 在跑**）
- `CsXssBrowserIT`（3，需 Edge）

---

## 8. 验收标准

| # | 项 | 阈值 |
|---|---|---|
| 1 | 紫值残留（`5b48f0`/`7a68ff`/`4335d6`/`6d5bff`/`8b7cff`/`e8590c`） | 0 |
| 2 | `--glow` 残留 | 0 |
| 3 | `linear-gradient`（项目自有 CSS） | 0 |
| 4 | 卡片 hover `translateY(-Npx)` | 0 |
| 5 | 3px 彩色焦点环 | 0 |
| 6 | 正文对比度 ≥ 4.5:1；大字 ≥ 3.0:1 | 全部达标 |
| 7 | 横向溢出（双主题 × 4 断点 × 6 页面） | 0 |
| 8 | 首页四个区块骨架互不相同 | 目视 + CSS 断言 |
| 9 | `--t3` 不承载语义文字 | grep 复查 |
| 10 | AI 客服用户气泡为价格暖橙 | 目视 |
| 11 | `--grad-hero: none` 存在 | grep |
| 12 | 守卫测试 4 类全绿 | 全绿 |
| 13 | `ops/smoke.sh` | 16/16 |

---

## 9. 保留不动的部分（重要）

- 模板 DOM 结构与 Thymeleaf 片段（`header` / `footer` / `nav`）
- 工具类类名 `.w` / `.fl` / `.fr` / `.clearfix` / `.mt20`（**保留类名换实现**）
- `cs-core.js` 事件契约（`onStage` / `onRag` / `onTool` / `onDelta` / `onDone` / `onReview` / `onError` / `onFinish`）与 DOM 类名（`cs-msg` / `cs-panel` 等）
- 任何 Java 业务逻辑、jqGrid 列定义、wangEditor、上传逻辑、商品图数据
  - 例外：`IndexController` 新增 `goodsCount` / `categoryCount` 两个 request attribute（供状态条用真实数据）；`NewBeeMallIndexConfigService` 新增 `getGoodsCount()`，复用现成的 `getTotalNewBeeMallGoods(PageQueryUtil)`
