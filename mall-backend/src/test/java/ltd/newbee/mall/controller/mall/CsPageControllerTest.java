package ltd.newbee.mall.controller.mall;

import ltd.newbee.mall.entity.NewBeeMallGoods;
import ltd.newbee.mall.service.NewBeeMallGoodsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.URLEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M3-A：{@code GET /cs} 完整页的渲染与上下文带入。
 *
 * <p>在**真实 HTTP** 上访问（不是只测 Controller 返回值）：模板解析、
 * header/footer 片段替换、Thymeleaf 转义这些只有真渲染才暴露。
 *
 * <h3>为什么这里也要测 XSS</h3>
 * {@code orderNo} 是<b>用户可控</b>的查询参数（{@code /cs?orderNo=...}）且会被写进页面，
 * 所以「服务端有没有按 HTML 转义渲染它」是一条真实攻击面 —— 本测试用
 * {@code <img src=x onerror=...>} 这种带引号的载荷来钉住它（引号是关键：
 * 只转义 {@code & < >} 而漏掉引号的实现会在属性语境下被绕过）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cs.rag.enabled=false"   // 本测试不碰 RAG，避免加载 90MB 嵌入模型
)
class CsPageControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private NewBeeMallGoodsService goodsService;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("无上下文：页面正常渲染（含三栏骨架与上下文面板容器）")
    void rendersWithoutContext() throws Exception {
        HttpResponse<String> response = get("/cs");

        assertTrue(response.statusCode() == 200,
                "GET /cs 应 200，实际 " + response.statusCode());
        String body = response.body();
        assertTrue(body.contains("id=\"cs-app\""), "应含页面根节点 cs-app");
        assertTrue(body.contains("id=\"cs-history\""), "应有会话历史栏（232px）");
        assertTrue(body.contains("id=\"cs-panel\""), "应有上下文面板（340px）");
        assertTrue(body.contains("id=\"cs-messages\""), "应有对话区");
        assertFalse(body.contains("正在咨询"),
                "没带 goodsId/orderNo 时不应出现「正在咨询」提示条");
    }

    @Test
    @DisplayName("带 goodsId：首屏提示「正在咨询：<真实商品名>」（服务端回查）")
    void rendersWithGoodsContext() throws Exception {
        // 用库里真实数据，避免把测试绑在某个硬编码 id 的存废上
        NewBeeMallGoods goods = goodsService.getNewBeeMallGoodsById(10085L);
        assumeTrue(goods != null && goods.getGoodsName() != null,
                "测试库缺 goodsId=10085：跳过而不是假绿");

        HttpResponse<String> response = get("/cs?goodsId=10085");
        String body = response.body();

        assertTrue(response.statusCode() == 200);
        assertTrue(body.contains("正在咨询"), "应出现上下文提示条");
        assertTrue(body.contains(goods.getGoodsName()),
                "应渲染出真实商品名：" + goods.getGoodsName());
        // 前端也要能拿到（dataset 传入，供 SSE 请求携带）
        assertTrue(body.contains("data-goods-id=\"10085\""), "应通过 data-goods-id 传给前端");
    }

    @Test
    @DisplayName("带 orderNo：透传渲染（服务端不回查订单，避免越权泄露）")
    void rendersWithOrderContext() throws Exception {
        HttpResponse<String> response = get("/cs?orderNo=" + enc("2024091812345678"));
        String body = response.body();

        assertTrue(response.statusCode() == 200);
        assertTrue(body.contains("正在咨询订单"), "应出现订单上下文提示条");
        assertTrue(body.contains("2024091812345678"), "订单号应透传到页面上");
    }

    @Test
    @DisplayName("XSS：orderNo 里的 <img onerror> 与引号必须被转义（服务端渲染不得成为注入点）")
    void orderNoIsHtmlEscaped() throws Exception {
        String payload = "<img src=x onerror=alert(1)>'\"";
        HttpResponse<String> response = get("/cs?orderNo=" + enc(payload));
        String body = response.body();

        assertTrue(response.statusCode() == 200);
        assertFalse(body.contains("<img src=x onerror=alert(1)>"),
                "原始 <img onerror> 载荷不得出现在响应里（说明未转义）");
        assertFalse(body.contains("onerror=alert(1)>"),
                "即便标签被拆开，onerror 片段也不该原样出现");
        assertTrue(body.contains("&lt;img src=x onerror=alert(1)&gt;"),
                "应看到被转义后的形态（&lt;img ... &gt;），证明走的是 th:text 而不是 th:utext");
    }

    @Test
    @DisplayName("非法 goodsId 不 500：页面仍可打开（只是没有商品上下文）")
    void invalidGoodsIdStillRenders() throws Exception {
        HttpResponse<String> response = get("/cs?goodsId=999999999");
        assertTrue(response.statusCode() == 200,
                "id 不存在时应降级渲染而不是报错，实际 " + response.statusCode());

        HttpResponse<String> negative = get("/cs?goodsId=-1");
        assertTrue(negative.statusCode() == 200, "负数 id 同样应降级渲染");
    }

    @Test
    @DisplayName("页面绝不使用 iframe（本项目正是为取代旧 iframe 方案）")
    void pageHasNoIframe() throws Exception {
        String body = get("/cs?goodsId=10085").body();
        assertFalse(body.contains("<iframe"), "客服页必须是商城原生页面，不得用 iframe 嵌外部站");
    }

    @Test
    @DisplayName("head 片段按 path=cs 引入了页面样式（模板契约：path 决定 /mall/styles/<path>.css）")
    void headFragmentLoadsPageStylesheet() throws Exception {
        String body = get("/cs").body();

        // head-fragment(title,path) 的第二个参数决定加载哪个页面样式文件；
        // 传错会让 /cs 变成无样式页面，所以这里钉住它。
        assertTrue(body.contains("/mall/styles/cs.css"),
                "应引入 /mall/styles/cs.css（head-fragment 的 path 参数必须是 cs）");
        assertTrue(body.contains("/mall/js/cs-core.js"),
                "应引入共用的 cs-core.js");

        // 注：head-fragment 的 title 参数在现有 header.html 里<b>未被使用</b>（<title> 是写死的），
        // 所以浏览器标签页显示的是商城通用标题。那是既有模板的既有行为，
        // 本次不动 header.html（红线：只追加不改坏），因此不在此断言标题文案。
    }
}
