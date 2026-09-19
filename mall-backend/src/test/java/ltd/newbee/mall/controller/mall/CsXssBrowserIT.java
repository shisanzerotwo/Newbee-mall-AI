package ltd.newbee.mall.controller.mall;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为级 XSS 回归：12 条 payload × 2 条客服渲染路径 + 阳性对照。
 *
 * <p>对应 {@code docs/XSS-VERIFICATION.md} 与 {@code docs/DESIGN.md} §8.4 的 DoD
 * （「12 条 XSS payload 在浮窗与 /cs 各跑一遍」）。本轮把原先仓库外的**一次性探针**
 * 固化成可复跑的测试 —— 结论不再依赖某个临时文件是否存在。
 *
 * <p>与 {@link CsFrontendGuardTest} / {@link CsWidgetGuardTest} 的分工：
 * <ul>
 *   <li>那两个是**静态断言**（读源码字符串）→ 证明「源码里没有危险写法」；</li>
 *   <li>本类是**行为验证**（真实 Chrome 渲染真实前端资源）→ 证明「浏览器真的没执行 payload」。</li>
 * </ul>
 * 两者互补，都不能单独宣称「XSS 已全量验证」。
 *
 * <p>验证手法：JDK 内置 {@code HttpServer} 只 mock {@code POST /api/cs/chat} 的 SSE 传输，
 * 前端 JS / 模板内联脚本都取自仓库真实文件；Chrome headless 逐条提交 payload，
 * 再把 DOM 层面的观测结果 POST 回 {@code /result}。**不依赖真实模型**，因此不烧额度。
 *
 * <p>命名以 {@code IT} 结尾 → Surefire 默认不跑（它要拉起 Chrome，几十秒，
 * 且并非每台机器都有 Chrome）。手动运行：
 * <pre>
 *   cd /mnt/d/GitHub/xiangmu/newbee-mall-ai
 *   bash ops/mvn.sh test -Dtest=CsXssBrowserIT
 * </pre>
 * Chrome 不在默认位置时可用 {@code CS_CHROME_PATH} 指定；两者都找不到则**跳过**（不误报红灯）。
 *
 * <p>阳性对照（{@link #positiveControlDetectsDeliberateInjection()}）是本类的关键：
 * 它打开一个**故意不安全**的页面（把 {@code <img onerror>} 直接写进 {@code innerHTML}）。
 * 若连它也检不出注入，说明探针本身失效，前两个「safe」结论就是假通过 —— 因此该用例是**硬断言**。
 */
class CsXssBrowserIT {

    private static final List<String> PAYLOADS = List.of(
            "<script>window.__xssExecuted.push('script')</script>",
            "<img src=x onerror=\"window.__xssExecuted.push('img')\">",
            "<svg onload=\"window.__xssExecuted.push('svg')\"></svg>",
            "\" onmouseover=\"window.__xssExecuted.push('double-attr')\" data-x=\"",
            "' onfocus='window.__xssExecuted.push(\"single-attr\")' autofocus data-x='",
            "<a href=\"javascript:window.__xssExecuted.push('js-url')\">click</a>",
            "<iframe srcdoc=\"<script>parent.__xssExecuted.push('iframe')</script>\"></iframe>",
            "</textarea><script>window.__xssExecuted.push('textarea')</script>",
            "`${window.__xssExecuted.push('template')}`",
            "\\u003cimg src=x onerror=\"window.__xssExecuted.push('unicode')\"\\u003e"
                    + "&#x3c;img src=x onerror=\"window.__xssExecuted.push('entity')\"&#x3e;",
            "<img src=x onerror=\"window.__xssExecuted.push('long')\">" + "A".repeat(4096),
            "\u0000<img src=x onerror=\"window.__xssExecuted.push('control')\">"
    );

    private static final int SAFE_TIMEOUT_SECONDS = 45;
    private static final int CONTROL_TIMEOUT_SECONDS = 20;

    private static HttpServer server;
    private static int port;
    private static String chromePath;
    private static Path projectRoot;
    private static String payloadData;
    private static final LinkedBlockingQueue<String> BROWSER_RESULTS = new LinkedBlockingQueue<>();

    @BeforeAll
    static void startFixture() throws Exception {
        Optional<String> chrome = locateChrome();
        Assumptions.assumeTrue(chrome.isPresent(),
                "未找到 Chrome/Chromium；如需运行本 IT，请设置 CS_CHROME_PATH 指向浏览器可执行文件");

        projectRoot = locateProjectRoot();
        chromePath = chrome.get();
        payloadData = Base64.getEncoder()
                .encodeToString(jsonArray(PAYLOADS).getBytes(StandardCharsets.UTF_8));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/widget", exchange -> respond(exchange, "text/html; charset=utf-8", widgetPage()));
        server.createContext("/cs", exchange -> respond(exchange, "text/html; charset=utf-8", csPage()));
        server.createContext("/control", exchange -> respond(exchange, "text/html; charset=utf-8", controlPage()));
        server.createContext("/result", exchange -> {
            String result = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            try {
                BROWSER_RESULTS.put(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/mall/js/cs-core.js", exchange -> respond(exchange,
                "text/javascript; charset=utf-8",
                Files.readString(projectRoot.resolve("mall-backend/src/main/resources/static/mall/js/cs-core.js"),
                        StandardCharsets.UTF_8)));
        server.createContext("/mall/js/cs-widget.js", exchange -> respond(exchange,
                "text/javascript; charset=utf-8",
                Files.readString(projectRoot.resolve("mall-backend/src/main/resources/static/mall/js/cs-widget.js"),
                        StandardCharsets.UTF_8)));
        server.start();
    }

    @AfterAll
    static void stopFixture() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("浮窗路径（cs-widget.js）：12 条 payload 均不得被解析、注册事件或执行")
    void widgetPathRejectsAllPayloads() throws Exception {
        Result result = runSafe("/widget", "widget");
        assertAllSafe("widget", result);
    }

    @Test
    @Timeout(180)
    @DisplayName("/cs 路径（cs.html 内联脚本）：12 条 payload 均不得被解析、注册事件或执行")
    void csPageRejectsAllPayloads() throws Exception {
        Result result = runSafe("/cs", "cs");
        assertAllSafe("/cs", result);
    }

    @Test
    @Timeout(120)
    @DisplayName("阳性对照：故意不安全的页面必须被检出（否则说明探针失效、上面的 safe 是假通过）")
    void positiveControlDetectsDeliberateInjection() throws Exception {
        Control control = runControl();
        assertTrue(control.executedExists(),
                "对照页面没有执行 window.__xssExecuted.push('control') —— 探针无法观测脚本执行，"
                        + "因此 widget//cs 的 safe 结论不可信");
        assertTrue(control.injectedNodeExists(),
                "对照页面没有观测到 innerHTML 注入的 IMG 节点 —— 探针无法观测 DOM 注入，"
                        + "因此 widget//cs 的 safe 结论不可信");
    }

    // ---------------------------------------------------------------- 运行与断言

    private static Result runSafe(String path, String resultName) throws Exception {
        return parseResult(decode(runChrome(path, resultName, SAFE_TIMEOUT_SECONDS)));
    }

    private static Control runControl() throws Exception {
        String json = decode(runChrome("/control", "control", CONTROL_TIMEOUT_SECONDS));
        return new Control(json.contains("\"executedExists\":true"), json.contains("\"injectedNodeExists\":true"));
    }

    private static String decode(String encoded) {
        String base64 = encoded.substring(encoded.indexOf('\n') + 1);
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private static void assertAllSafe(String path, Result result) {
        assertNotNull(result, path + ": 浏览器没有返回结果");
        assertTrue(result.cases().size() == PAYLOADS.size(),
                path + ": 期望 " + PAYLOADS.size() + " 条结果，实际 " + result.cases().size() + " 条");

        List<String> failures = new ArrayList<>();
        for (int i = 0; i < result.cases().size(); i++) {
            CaseResult c = result.cases().get(i);
            if (c.id() != i + 1) {
                failures.add("#" + (i + 1) + " id mismatch " + c.id());
            }
            if (!c.userTextOk()) {
                failures.add("#" + (i + 1) + " 用户气泡文本被改动");
            }
            if (!c.botTextOk()) {
                failures.add("#" + (i + 1) + " 机器流式气泡文本被改动");
            }
            if (c.executed()) {
                failures.add("#" + (i + 1) + " payload 被执行");
            }
            if (c.injectedNodeCount() != 0) {
                failures.add("#" + (i + 1) + " 注入 DOM 节点 " + c.injectedNodeCount() + " 个");
            }
            if (c.eventAttributeCount() != 0) {
                failures.add("#" + (i + 1) + " 新增 on* 事件属性 " + c.eventAttributeCount() + " 个");
            }
            if (c.javascriptAttributeCount() != 0) {
                failures.add("#" + (i + 1) + " 出现 javascript: 属性 " + c.javascriptAttributeCount() + " 个");
            }
            if ("/cs".equals(path) && !c.historyTextOk()) {
                failures.add("#" + (i + 1) + " 历史按钮文本/ title 被改动");
            }
        }
        assertTrue(failures.isEmpty(), path + " 检出 " + failures.size() + " 个问题：" + failures);
    }

    private static String runChrome(String path, String resultName, int timeoutSeconds) throws Exception {
        Path profile = Files.createTempDirectory("cs-xss-chrome-");
        Process process = null;
        try {
            process = new ProcessBuilder(
                    chromePath,
                    "--headless=new",
                    "--disable-gpu",
                    "--disable-extensions",
                    "--no-first-run",
                    "--disable-background-networking",
                    "--user-data-dir=" + profile,
                    "http://127.0.0.1:" + port + path)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String encoded = BROWSER_RESULTS.poll(timeoutSeconds, TimeUnit.SECONDS);
            if (encoded == null) {
                throw new IllegalStateException("Chrome 超时（" + timeoutSeconds + "s）未返回结果：" + resultName);
            }
            if (!encoded.startsWith(resultName + "\n")) {
                throw new IllegalStateException("浏览器结果信封不匹配：" + encoded.substring(0, Math.min(80, encoded.length())));
            }
            return encoded;
        } finally {
            killTree(process);
            deleteRecursively(profile);
        }
    }

    /**
     * Chrome 会派生 renderer/gpu 等子进程，只杀父进程会留下孤儿进程（上一轮一次性探针踩过）。
     */
    private static void killTree(Process process) {
        if (process == null) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时 profile 清理失败不影响测试结论（Windows 上偶有文件句柄延迟释放）
                }
            });
        } catch (IOException ignored) {
            // 同上：清理是尽力而为，不掩盖真正的断言失败
        }
    }

    // ---------------------------------------------------------------- 环境探测

    private static Optional<String> locateChrome() {
        String configured = System.getenv("CS_CHROME_PATH");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return Optional.of(configured);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> candidates = windows
                ? List.of(
                        "C:/Program Files/Google/Chrome/Application/chrome.exe",
                        "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
                        System.getenv("LOCALAPPDATA") + "/Google/Chrome/Application/chrome.exe",
                        System.getenv("PROGRAMFILES") + "/Microsoft/Edge/Application/msedge.exe")
                : List.of(
                        "/usr/bin/google-chrome",
                        "/usr/bin/google-chrome-stable",
                        "/usr/bin/chromium",
                        "/usr/bin/chromium-browser",
                        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        return candidates.stream()
                .filter(c -> c != null && !c.isBlank())
                .filter(c -> Files.isExecutable(Path.of(c)))
                .findFirst();
    }

    private static Path locateProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("mall-backend/src/main/resources/static/mall/js/cs-core.js"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到项目根（mall-backend/src/main/resources/static/mall/js/cs-core.js）");
    }

    // ---------------------------------------------------------------- 页面夹具

    private static String widgetPage() {
        return """
                <!doctype html><html><head><meta charset="utf-8"></head><body>
                <div id="cs-context" data-goods-id="10085" data-goods-name="安全上下文"></div>
                %s
                %s
                <script src="/mall/js/cs-core.js"></script>
                <script src="/mall/js/cs-widget.js"></script>
                %s
                </body></html>
                """.formatted(initScript("widget"), fetchMock(), widgetDriver());
    }

    private static String csPage() throws IOException {
        Path template = projectRoot.resolve("mall-backend/src/main/resources/templates/mall/cs.html");
        String html = Files.readString(template, StandardCharsets.UTF_8);
        html = html.replace(
                "<script th:src=\"@{/mall/js/cs-core.js}\"></script>",
                initScript("cs") + "\n" + fetchMock() + "\n" + "<script src=\"/mall/js/cs-core.js\"></script>");
        html = html.replace(
                "th:attr=\"data-goods-id=${goodsId},data-goods-name=${goodsName},data-order-no=${orderNo}\"",
                "data-goods-id=\"10085\" data-goods-name=\"安全上下文\" data-order-no=\"\"");
        return html.replace("</body>", pageDriver() + "\n</body>");
    }

    private static String controlPage() {
        return """
                <!doctype html><html><head><meta charset="utf-8"></head><body>
                <div id="control"></div>
                %s
                %s
                <script>
                const root = document.getElementById('control');
                root.innerHTML = '<img src=x onerror="window.__xssExecuted.push(\\'control\\')">';
                setTimeout(function () {
                    finish('control', {
                        executedExists: window.__xssExecuted.includes('control'),
                        injectedNodeExists: !!root.querySelector('img')
                    });
                }, 50);
                </script>
                </body></html>
                """.formatted(initScript("control"), commonDriver());
    }

    private static String initScript(String resultName) {
        return "<script>window.__xssExecuted = [];window.__xssResultName = '" + resultName
                + "';window.__xssPayloads = JSON.parse(new TextDecoder().decode("
                + "Uint8Array.from(atob('" + payloadData + "'), c => c.charCodeAt(0))));</script>";
    }

    private static String fetchMock() {
        return """
                <script>
                window.__fetchIndex = 0;
                window.fetch = function () {
                    const payload = window.__xssPayloads[window.__fetchIndex++];
                    const frame = 'event: delta\\ndata: ' + JSON.stringify({text: payload}) + '\\n\\n'
                        + 'event: review\\ndata: {"qualified":true,"reason":"ok"}\\n\\n';
                    const bytes = new TextEncoder().encode(frame);
                    const stream = new ReadableStream({
                        start(controller) {
                            controller.enqueue(bytes);
                            controller.close();
                        }
                    });
                    return Promise.resolve(new Response(stream, {
                        status: 200,
                        headers: {'Content-Type': 'text/event-stream'}
                    }));
                };
                </script>
                """;
    }

    private static String widgetDriver() {
        return commonDriver() + """
                <script>
                window.addEventListener('load', async function () {
                    const results = [];
                    try {
                        await waitUntil(function () { return !!window.CsWidget; }, 2000);
                        for (let i = 0; i < window.__xssPayloads.length; i++) {
                            const payload = window.__xssPayloads[i];
                            CsWidget.open();
                            CsWidget.ask(payload);
                            await waitUntil(function () {
                                return !document.getElementById('cs-float-send').disabled;
                            }, 3000);
                            const user = document.querySelectorAll('#cs-float-messages .cs-msg--user')[i];
                            const bots = document.querySelectorAll('#cs-float-messages .cs-msg--bot');
                            const bot = bots[bots.length - 1];
                            const botText = bot.querySelector('.cs-msg__text');
                            results.push(inspect(i + 1, payload, user, bot, botText, null));
                        }
                    } catch (e) {
                        results.push({id: -1, error: String(e && e.stack || e)});
                    }
                    finish('widget', results);
                });
                </script>
                """;
    }

    private static String pageDriver() {
        return commonDriver() + """
                <script>
                window.addEventListener('load', async function () {
                    const results = [];
                    try {
                        const form = document.getElementById('cs-form');
                        const input = document.getElementById('cs-input');
                        const send = document.getElementById('cs-send');
                        for (let i = 0; i < window.__xssPayloads.length; i++) {
                            const payload = window.__xssPayloads[i];
                            input.value = payload;
                            form.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
                            await waitUntil(function () { return !send.disabled; }, 3000);
                            const user = document.querySelectorAll('#cs-messages .cs-msg--user')[i];
                            const bots = document.querySelectorAll('#cs-messages .cs-msg--bot');
                            const bot = bots[bots.length - 1];
                            const botText = bot.querySelector('span');
                            const history = document.querySelectorAll('.cs-history__item');
                            results.push(inspect(i + 1, payload, user, bot, botText, history[history.length - 1]));
                        }
                    } catch (e) {
                        results.push({id: -1, error: String(e && e.stack || e)});
                    }
                    finish('cs', results);
                });
                </script>
                """;
    }

    private static String commonDriver() {
        return """
                <script>
                function waitUntil(predicate, timeoutMs) {
                    return new Promise(function (resolve, reject) {
                        const started = Date.now();
                        (function poll() {
                            let ok = false;
                            try { ok = predicate(); } catch (e) { reject(e); return; }
                            if (ok) { resolve(); return; }
                            if (Date.now() - started > timeoutMs) {
                                reject(new Error('waitUntil timeout'));
                                return;
                            }
                            setTimeout(poll, 1);
                        })();
                    });
                }

                function b64(value) {
                    const bytes = new TextEncoder().encode(value);
                    let binary = '';
                    for (const b of bytes) binary += String.fromCharCode(b);
                    return btoa(binary);
                }

                function inspect(id, payload, user, bot, botText, historyItem) {
                    const roots = [user, bot, botText, historyItem].filter(Boolean);
                    const all = [];
                    roots.forEach(function (root) {
                        all.push(root);
                        root.querySelectorAll('*').forEach(function (node) { all.push(node); });
                    });
                    let injectedNodeCount = 0;
                    let eventAttributeCount = 0;
                    let javascriptAttributeCount = 0;
                    all.forEach(function (node) {
                        if (/^(SCRIPT|IMG|SVG|IFRAME)$/.test(node.tagName)) {
                            injectedNodeCount++;
                        }
                        Array.from(node.attributes || []).forEach(function (attr) {
                            if (/^on/i.test(attr.name)) eventAttributeCount++;
                            if (/^(href|src|xlink:href)$/i.test(attr.name)
                                    && /^\\s*javascript:/i.test(attr.value)) {
                                javascriptAttributeCount++;
                            }
                        });
                    });
                    return {
                        id: id,
                        userTextOk: !!user && user.textContent === payload,
                        botTextOk: !!botText && botText.textContent === payload,
                        historyTextOk: !historyItem
                            || (historyItem.textContent === payload && historyItem.title === payload),
                        executed: window.__xssExecuted.length > 0,
                        injectedNodeCount: injectedNodeCount,
                        eventAttributeCount: eventAttributeCount,
                        javascriptAttributeCount: javascriptAttributeCount
                    };
                }

                function finish(name, value) {
                    const xhr = new XMLHttpRequest();
                    xhr.open('POST', '/result', true);
                    xhr.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
                    xhr.send(name + '\\n' + b64(JSON.stringify(value)));
                }
                </script>
                """;
    }

    private static void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ---------------------------------------------------------------- 结果解析

    private static Result parseResult(String json) {
        if (json.contains("\"id\":-1")) {
            throw new IllegalStateException("浏览器驱动报错：" + json);
        }
        List<CaseResult> cases = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = json.indexOf('{', from);
            if (start < 0) {
                break;
            }
            int end = json.indexOf('}', start);
            if (end < 0) {
                break;
            }
            String item = json.substring(start, end + 1);
            cases.add(new CaseResult(
                    intField(item, "id"),
                    boolField(item, "userTextOk"),
                    boolField(item, "botTextOk"),
                    boolField(item, "historyTextOk"),
                    boolField(item, "executed"),
                    intField(item, "injectedNodeCount"),
                    intField(item, "eventAttributeCount"),
                    intField(item, "javascriptAttributeCount")));
            from = end + 1;
        }
        return new Result(cases);
    }

    private static int intField(String json, String name) {
        String marker = "\"" + name + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            return -999;
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    private static boolean boolField(String json, String name) {
        return json.contains("\"" + name + "\":true");
    }

    private static String jsonArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(jsonString(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private record Result(List<CaseResult> cases) {
    }

    private record CaseResult(
            int id,
            boolean userTextOk,
            boolean botTextOk,
            boolean historyTextOk,
            boolean executed,
            int injectedNodeCount,
            int eventAttributeCount,
            int javascriptAttributeCount) {
    }

    private record Control(boolean executedExists, boolean injectedNodeExists) {
    }
}
