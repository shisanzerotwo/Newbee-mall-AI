# XSS 12 条 payload 行为级核查

> 范围：M3-D P2 第一项。对应 `docs/DESIGN.md` §8.4 的 DoD：
> 「12 条 XSS payload 在浮窗与 `/cs` 各跑一遍」。
>
> 本文件只记录核查结论，不改写 DESIGN §8.4 原文。

## 1. 已有静态覆盖

本轮先核实了现有两份守卫测试。

### `CsFrontendGuardTest`

- `cs-core.js` 确实声明了 `escapeHtml`，替换表覆盖 `& < > " '` 五个字符。
- `cs-core.js` 不含 `.innerHTML =`、`.innerHTML=`、`innerHTML +=`、`insertAdjacentHTML`、`document.write`。
- `cs.html` 不含 `th:utext`、裸 `innerHTML` 或 `<iframe`。
- `orderNo` 走 `th:text` / `data-*`，流式文本写入独立的 `botText` 子节点。

### `CsWidgetGuardTest`

- `cs-widget.js` 不含 `innerHTML`、`insertAdjacentHTML`、`document.write`、`iframe`。
- 文本走 `textContent`，节点走 `createElement`。
- 浮窗复用 `Cs.renderGoodsCard`，商品卡片只来自 `tool` 事件。

### 静态覆盖的边界

这些测试是源码字符串断言，**不能证明浏览器实际没有解析或执行 payload**。
另外，`escapeHtml` 目前是导出能力，两个客服渲染路径的主要防线实际是
`createElement` + `textContent`；商品卡片的链接参数另走 `encodeURIComponent`。

## 2. 12 条 payload

行为探针逐条提交下列原始字符串：

1. `<script>window.__xssExecuted.push('script')</script>`
2. `<img src=x onerror="window.__xssExecuted.push('img')">`
3. `<svg onload="window.__xssExecuted.push('svg')"></svg>`
4. `" onmouseover="window.__xssExecuted.push('double-attr')" data-x="`
5. `' onfocus='window.__xssExecuted.push("single-attr")' autofocus data-x='`
6. `<a href="javascript:window.__xssExecuted.push('js-url')">click</a>`
7. `<iframe srcdoc="<script>parent.__xssExecuted.push('iframe')</script>"></iframe>`
8. `</textarea><script>window.__xssExecuted.push('textarea')</script>`
9. `` `${window.__xssExecuted.push('template')}` ``
10. `\u003cimg src=x onerror="window.__xssExecuted.push('unicode')"\u003e&#x3c;img src=x onerror="window.__xssExecuted.push('entity')"&#x3e;`
11. `<img src=x onerror="window.__xssExecuted.push('long')">` + `A` × 4096
12. 前导 `U+0000` + `<img src=x onerror="window.__xssExecuted.push('control')">`

覆盖类别依次为：脚本标签、图片事件、SVG 事件、双引号属性逃逸、单引号属性逃逸、
`javascript:` URL、iframe、`textarea` 逃逸、模板字符串注入、Unicode/实体绕过、
超长输入、控制字符。

## 3. 两条路径的行为验证

使用本机 Chrome 154 headless，真实加载：

- `mall-backend/src/main/resources/static/mall/js/cs-core.js`
- `mall-backend/src/main/resources/static/mall/js/cs-widget.js`
- `mall-backend/src/main/resources/templates/mall/cs.html` 的原始内联脚本

假上游只 mock `POST /api/cs/chat` 的 SSE 响应；每条 payload 先作为用户问题提交，
再作为 `delta` 文本返回。对每条记录同时检查：

- 用户气泡文本完整保留；
- 机器人流式气泡文本完整保留；
- `/cs` 的历史按钮文本和 `title` 属性完整保留；
- 渲染区域内没有新增 `SCRIPT`、`IMG`、`SVG`、`IFRAME` 节点；
- 没有新增 `on*` 事件属性；
- 没有 `href` / `src` / `xlink:href` 以 `javascript:` 开头；
- 全局执行标记数组保持为空。

### 结果

| 路径 | payload 数 | 结果 |
|---|---:|---|
| 浮窗 `cs-widget.js` | 12 | 12/12 safe |
| `/cs` 页面内联逻辑 | 12 | 12/12 safe |

共 24 个行为用例，未发现 payload 被解析为 DOM、注册事件、形成 `javascript:` URL
或执行脚本。

## 4. 非假通过的对照

同一探针额外打开一个**故意不安全**的页面，把
`<img src=x onerror="window.__xssExecuted.push('control')">` 直接写入
`innerHTML`。结果同时观察到：

```text
control: executedExists=true, injectedNodeExists=true
```

这说明测试浏览器中的事件执行和注入节点观测都能正常工作；上面的安全路径结果
不是由于探针永远看不到 XSS 而出现的假通过。

## 5. 实际执行记录

行为探针（临时文件 `C:\Users\22421\xss-check\XssBehaviorCheck.java`）输出：

```text
widget: 12/12 safe
/cs: 12/12 safe
control: executedExists=true, injectedNodeExists=true
```

静态守卫复跑：

```bash
wsl.exe -- bash -lc 'cd /mnt/d/GitHub/xiangmu/newbee-mall-ai && bash ops/mvn.sh -q -Dtest=CsFrontendGuardTest,CsWidgetGuardTest test'
```

退出码 `0`，无失败输出。

## 5b. 固化为仓库内回归测试（2026-09-19）

上面那份探针是**仓库外的一次性文件**（`C:\Users\22421\xss-check\XssBehaviorCheck.java`）——
clone 之后跑不了，结论无法复现。本轮已把它固化为仓库内测试：

`mall-backend/src/test/java/ltd/newbee/mall/controller/mall/CsXssBrowserIT.java`

与一次性探针相比，固化版有三处**实质改进**：

| 项 | 一次性探针 | `CsXssBrowserIT` |
|---|---|---|
| 阳性对照 | 打印结果，人看 | **硬断言** —— 对照检不出注入就判测试失败（否则前面的 safe 结论就是假通过） |
| 进程/临时目录清理 | 只 `destroyForcibly()` 父进程 → 会留下孤儿 Chrome 进程与临时 profile | 杀**整棵进程树**（`process.descendants()`）+ 递归删临时 profile |
| 无 Chrome 的机器 | 直接崩 | `Assumptions` **跳过**（不误报红灯），可用 `CS_CHROME_PATH` 指定浏览器 |

跑法（`*IT` 结尾，Surefire 默认不跑；本机约 27s）：

```bash
cd /mnt/d/GitHub/xiangmu/newbee-mall-ai
bash ops/mvn.sh test -Dtest=CsXssBrowserIT
```

### 实测证据

| 步骤 | 命令/操作 | 结果 |
|---|---|---|
| 固化版实跑 | `mvn test -Dtest=CsXssBrowserIT` | ✅ `Tests run: 3, Failures: 0`（15.27s） |
| **阴性对照** | 临时把 `cs-widget.js` 的 `botText.textContent = botText.textContent + text` 改成 `botText.innerHTML = ...` | ✅ **如期变红**：widget 路径检出 **24 个问题**（含 `#3 payload 被执行`、`#6 出现 javascript: 属性 2 个`、`#11 新增 on* 事件属性 2 个`），`/cs` 路径不受影响 |
| 还原 | `git checkout -- cs-widget.js` | ✅ md5 与改动前一致（`06f59aff…`），复跑 3/3 绿 |

阴性对照的意义：它证明这套断言**真的能抓到 XSS 回归**，而不是“永远绿”——
与 §4 的阳性对照互为两个方向（一个证探针看得见注入，一个证断言会因回归而失败）。

## 6. 局限与结论

- 行为验证使用 mock SSE，不是启动完整 Spring 应用后打真实模型；模型文本进入
  `delta` 后所经过的前端渲染路径是真实源码。
- 浮窗路径使用最小宿主页加载真实 `cs-widget.js`；`/cs` 路径使用真实模板内联脚本，
  仅把 Thymeleaf 的 `th:src` 和静态 `th:attr` 替换成浏览器可读属性。
- 本轮只覆盖列表中的 12 条 payload 和这两条客服渲染路径，不代表全站所有输入面
  已完成同样强度的行为级验证。
- 结论以当前源码为准：这两条路径目前未观察到 XSS；后续若引入模板字符串拼接、
  `innerHTML` 或动态 URL 渲染，`CsXssBrowserIT` 就是该回归的入口（§5b 已验证它抓得到这类回归）。
- 行为验证已入库（§5b）。~~但**不强制**在 CI 跑：拉 Chrome 需要额外的 CI 镜像条件~~
  → **P3 更新（2026-09-19）：已接进 CI**，`.github/workflows/ci.yml` 新增独立 job
  `xss-browser-it`（与主构建分开，**不配 MySQL/Redis** —— 该 IT 用进程内 HttpServer 夹具，
  本机实测不导出 `.env` 也能 3/3 通过、约 8.7s）。Chrome 由 `ubuntu-latest` 镜像自带，
  未额外安装；若某天镜像不再自带，IT 按既有约定 **skip 而非 fail**，
  由 job 末尾一步用 `::warning::` 把"本次 CI 其实没验证 XSS"喊出来，避免静默空跑。
  ⚠️ 该 job **尚未在真实 GitHub Actions 上跑过**（本机跑不了 Actions）——
  首次运行的结论请当作上面"Chrome 自带"这条事实的实证。
