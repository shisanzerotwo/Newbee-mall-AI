package ltd.newbee.mall.controller.mall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【补盲区】客服浮窗资源是否**真的**被注入到渲染后的页面里。
 *
 * <h3>为什么要单独立这个测试</h3>
 * M3-B 的 {@code CsWidgetGuardTest} 用的是 {@code readClasspath("/templates/mall/footer.html")}
 * 再断言 {@code html.contains("cs-widget.js")} —— 那只证明"**文件里有**"，
 * <b>不证明"渲染时会带上"</b>。
 *
 * <p>真机实测就栽在这里：页面用的是 {@code th:replace="mall/footer::footer-fragment"}，
 * Thymeleaf <b>只提取 fragment 范围内的节点</b>；而当时的 {@code <link>/<script>} 被写在
 * fragment <b>外面</b>（{@code </html>} 之前）→ 被<b>静默丢弃</b>，
 * 表现是**浮窗在任何页面都不出现**，而静态断言全绿（典型假绿）。
 *
 * <p>所以这里改成<b>端到端</b>：真的起服务、真的请求页面、断言<b>响应体</b>里含浮窗资源。
 * 这类"文件里有但模板机制会吃掉"的坑，只有端到端能拦住。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsWidgetInjectionTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String get(String path) throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(20))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), path + " 应返回 200");
        return resp.body();
    }

    /**
     * 数一个脚本/样式被引入的次数。
     *
     * <p>⚠️ <b>不能用精确字符串</b>：Thymeleaf 的 {@code @{...}} 在**首次请求（无 cookie）**时会
     * 输出 URL 重写形式 {@code src="/mall/js/cs-widget.js;jsessionid=XXXX"} ——
     * 写死 {@code src="/mall/js/cs-widget.js"} 会匹配不上而误报“没注入”（我第一版就是这么错的）。
     * 所以用前缀正则，容忍 {@code ;jsessionid=...} 与查询串。
     *
     * <p>{@code src}/{@code href} 都匹配 —— 这样 CSS（{@code href}）也能纳入「恰好一次」检查。
     */
    private static int countAssetRefs(String html, String assetName) {
        Matcher m = Pattern.compile("(?:src|href)=\"[^\"]*" + Pattern.quote(assetName))
                .matcher(html);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static int indexOfAssetRef(String html, String assetName) {
        Matcher m = Pattern.compile("(?:src|href)=\"[^\"]*" + Pattern.quote(assetName)).matcher(html);
        return m.find() ? m.start() : -1;
    }

    @Test
    @DisplayName("首页渲染后必须真的带上浮窗资源（fragment 外的写法会在这里被抓住）")
    void homePageActuallyShipsWidgetAssets() throws Exception {
        String html = get("/");

        // 注意断言的是【渲染后的响应体】，不是模板文件
        assertTrue(html.contains("cs-widget.js"),
                "首页响应体必须包含 cs-widget.js —— 若只写在 footer fragment 之外，"
                        + "Thymeleaf 会静默丢弃，浮窗永远不会出现（M3-B 真机踩过）");
        assertTrue(html.contains("cs-core.js"), "首页应带上共用核心 cs-core.js");
        assertTrue(html.contains("cs-widget.css"), "首页应带上浮窗样式 cs-widget.css");
    }

    @Test
    @DisplayName("浮窗资源每页只注入一次（footer 只被 include 一次，避免双份入口）")
    void widgetAssetsAreInjectedExactlyOnce() throws Exception {
        String html = get("/");

        // 注意两件事（都踩过）：
        //   1. 要数【script 标签】而不是纯字符串 —— 注释里提到文件名也会被算进去
        //   2. 不能要求精确 URL —— 首次请求会带 ;jsessionid=...（URL 重写）
        assertEquals(1, countAssetRefs(html, "cs-widget.js"),
                "cs-widget.js 应恰好被引入 1 次；>1 说明 footer 被重复 include，会出现两个入口");
        assertEquals(1, countAssetRefs(html, "cs-core.js"), "cs-core.js 应恰好被引入 1 次");
        // CSS 也纳入「恰好一次」（claude 指出：只对 JS 做、CSS 只 contains 的话，
        // 重复引入或在注释里出现都会漏过）
        assertEquals(1, countAssetRefs(html, "cs-widget.css"), "cs-widget.css 应恰好被引入 1 次");
    }

    @Test
    @DisplayName("详情页模板含「问客服」入口（静态断言；该页需登录，端到端留待人工验证）")
    void detailTemplateHasAskCsEntry() throws Exception {
        // ⚠️ 为什么这里只能做静态断言：实测 /goods/detail/{id} 会被登录拦截器 302 到 /login，
        //    而备选路径 /goods/goodsDetail/{id} 返回 HTTP 200 但内容是「系统异常」页
        //   （正好印证本项目已记录的坑：错误页也会返回 200，**必须断言内容而不是状态码**）。
        //    本项目登录带验证码，自动化成本高 → 入口的有无用模板断言守住，
        //    而「Footer 注入是否真的生效」由本类的其它用例以真实 HTTP 端到端拦住（那才是本次的重点）。
        String html = readClasspath("/templates/mall/detail.html");
        assertTrue(html.contains("问客服"), "详情页必须有「问客服」入口");
        assertTrue(html.contains("/cs(goodsId="), "入口必须把 goodsId 带到 /cs（DESIGN 4.3 上下文带入）");
    }

    private static String readClasspath(String path) throws Exception {
        try (var in = CsWidgetInjectionTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "类路径上找不到 " + path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("/cs 完整页同样带上资源（由 cs-widget.js 运行时自行判断是否渲染浮窗，避免双份入口）")
    void fullPageAlsoHasAssetsButWidgetSkipsItself() throws Exception {
        String html = get("/cs?goodsId=10085");
        assertTrue(html.contains("cs-core.js"),
                "/cs 页必须带共用核心（它自己就用它发 SSE）");
        // 浮窗脚本也会被引入（footer 共用），但运行时自行跳过 —— 属预期，不在此断言
    }

    @Test
    @DisplayName("cs-core.js 必须先于 cs-widget.js 加载（浮窗依赖 Cs 命名空间）")
    void coreLoadsBeforeWidget() throws Exception {
        String html = get("/");
        int coreIdx = indexOfAssetRef(html, "cs-core.js");
        int widgetIdx = indexOfAssetRef(html, "cs-widget.js");
        assertTrue(coreIdx > 0 && widgetIdx > 0, "两个脚本都应被引入");
        assertTrue(coreIdx < widgetIdx,
                "cs-core.js 必须先于 cs-widget.js 加载（浮窗依赖 Cs 命名空间）");
    }
}
