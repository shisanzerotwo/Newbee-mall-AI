# TASK M3-A：前端原生融合（浮窗 + `/cs` 完整页 + 上下文带入 + 六区块面板）

> **依据**：`docs/DESIGN.md` §8.1（双形态）、§8.2（上下文面板六区块）、§8.4（**输出编码，强制**）、§4.3（上下文带入）
> **前置**：M2-5 已完成（`31067d1`）；SSE 接口 `POST /api/cs/chat` 已在生产可用
> **本任务的核心价值**：解决项目最初的痛点 —— **客服与商城「界面割裂」**（旧实现是 iframe 套外部网站）

---

## 1. 目标

把客服做成**商城的原生能力**（同模板、同 CSS 变量、同 header/footer），提供两种形态：
- **右下角浮窗**（全站可用，轻量）
- **`/cs` 完整页**（三栏，能看上下文）

**绝对不要用 iframe** —— 那是被本次重写取代的旧方案。

---

## 2. 交付物

| # | 文件 | 内容 |
|---|---|---|
| 1 | `templates/mall/cs.html` | **`/cs` 完整页**：商城 header/footer + 三栏（会话历史 232px + 对话 + 上下文面板 340px） |
| 2 | `static/mall/css/cs-widget.css` | 浮窗 + 对话流的样式（复用商城 CSS 变量，**不要另起一套色板**） |
| 3 | `static/mall/js/cs-widget.js` | 浮窗：气泡、SSE 流式、3 条 chips、「展开完整页」 |
| 4 | `static/mall/js/cs-core.js` | **共用**：SSE 客户端 + 转义 + 商品卡片渲染（浮窗与完整页共用，**不要写两份**） |
| 5 | `controller/mall/CsPageController.java` | `GET /cs`（返回页面，接收并回填 `goodsId` / `orderNo`） |
| 6 | `templates/mall/footer.html`（改） | 注入浮窗容器 + script（**保留原有内容，只追加**） |
| 7 | `templates/mall/detail.html` + `order-detail.html`（改） | 「问客服」入口（带 `goodsId` / `orderNo`） |
| 8 | 测试 | 页面渲染测试 + XSS 转义单测 + 上下文带入测试 |

---

## 3. 🔴 红线

### 3.1 输出编码（DESIGN §8.4，**强制**）

- LLM 流式文本与工具返回的商品名，渲染前**一律**走转义函数
- **禁止裸 `innerHTML` 拼接模型输出**
- 转义必须覆盖 **`& < > " '`**（注意：**单引号也要转** —— Python 版 `web/index.html:219` 的 `esc()` 漏了它，
  在属性语境下有缺口，本项目**不继承这个缺陷**）
- 优先用 `textContent`；必须用 `innerHTML` 时只允许拼**自己构造的结构**，模型文本一律经转义后插入

### 3.2 商品卡片数据**从工具结果解析，不从 LLM 文本解析**

（DESIGN §8.4 明确）—— 模型会幻觉出商品名/价格，结构化展示必须用 `tool` 事件里的真实数据。
`tool` 事件的 `args` 里有 `goodsId`，`checkStock`/`getGoodsDetail` 的结果是可信来源。

### 3.3 数据铁律
页面上显示的价格/库存/订单状态，**只能来自工具结果**，绝不能来自 RAG 片段或模型措辞。

### 3.4 不破坏商城既有页面
`header.html` / `footer.html` / `detail.html` 都是商城在用的模板，**只能追加，不能改坏既有结构**。
改完必须跑冒烟（`bash ops/smoke.sh`）确认 16/16。

---

## 4. 接口契约（已实现，直接对接）

`POST /api/cs/chat`，请求体：
```json
{"question":"...","conversationId":"<前端 localStorage 生成>","goodsId":10003,"orderNo":null}
```
响应是 SSE，事件顺序 **stage → tool* → delta* → done → review**（audit 模式）：
```
event:stage  data:{"stage":"检索知识库","elapsed":0.31}
event:tool   data:{"name":"checkStock","args":{"goodsId":10003},"ms":42,"ok":true}
event:delta  data:{"text":"「无印良品高保湿化妆水」"}
event:review data:{"qualified":true,"reason":"..."}
event:done   data:{"totalMs":4210,"firstTokenMs":1180}
event:error  data:{"message":"..."}
```

⚠️ **客户端只在收到 `review` 或连接关闭后停止读取** —— `done` **不是**终止事件（DESIGN §4.2）。

---

## 5. 上下文带入（§4.3）

| 入口 | URL | 效果 |
|---|---|---|
| 商品详情页「问客服」 | `/cs?goodsId=10003` | 首屏提示「正在咨询：无印良品高保湿化妆水」；之后问“这个多少钱”能正确解析 |
| 订单详情页「问客服」 | `/cs?orderNo=2024…` | 自动带入订单号 |
| 全站浮窗 | 页面上的 `data-goods-id` | 浮窗头部显示当前咨询商品 |

---

## 6. 六个上下文区块（§8.2，完整页右侧面板）

识别意图 · 工具调用（含耗时）· 命中商品 · 关联订单 · 质检复核 · 引用来源（含融合分）

- 数据来源：SSE 的 `stage` / `tool` / `review` 事件 + 工具结果
- **引用来源（融合分）**：M2-5 的 SSE 目前**没有** `rag` 事件 —— 若面板要显示 RAG 来源，
  需**在 CsStreamService 增加一个 `rag` 事件**（含 `[{title, score}]`）。**这是本任务允许的后端改动**，
  但要保持向后兼容（老客户端忽略未知事件即可）。

---

## 7. 环境要点

- 本机 bash 是 **WSL**（`/mnt/d/...`）；调 Windows 程序要带 `.exe`
- Maven 一律 `bash ops/mvn.sh <args>`；跑测试前 `set -a && . ./.env && set +a`
- 冒烟：`bash ops/smoke.sh http://127.0.0.1:28091`
- 起应用（Windows 侧，注意 **WorkingDirectory** 否则日志跑错地方）：
  ```
  java -jar mall-backend/target/*.jar --server.port=28091
  ```

## 8. 验收标准

1. `bash ops/mvn.sh test` 全绿（现有 106 个不能挂）
2. 冒烟 16/16（证明没改坏商城）
3. `GET /cs?goodsId=<真实id>` 页面渲染出「正在咨询：<真实商品名>」
4. 浮窗在首页/详情页可见，点开能流式对话（真机验证，**至少跑一次真实 SSE**）
5. **XSS 用例**：构造含 `<img src=x onerror=alert(1)>` 与 `'` 的输入，断言渲染后被转义（要有测试）
6. 商品卡片数据来自 `tool` 事件（可断言渲染的 goodsId 与事件一致）

## 9. 不做的事

- 不做拖拽/手机抽屉/消息重生成（§8.3 是可选增强，留到 M3 末尾）
- 不动 SSE 的后端语义（除 §6 允许的 `rag` 事件新增）
- 不引入前端框架/构建工具（保持原生 JS，与商城现状一致）
