package ltd.newbee.mall.config;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【事实订正 + 防回归】验证 LangChain4j 1.20.0 的「内置重试」确实存在、且可从 Builder 关闭。
 *
 * <h3>为什么专门写这个测试（2026-09-18）</h3>
 * M2-4 初版注释与文档里写着「{@code OpenAiChatModel.Builder} <b>没有</b> {@code maxRetries}，
 * 内置重试<b>无法从 Builder 关闭</b>」，并称「用 javap 核实过 Builder 的全部方法」。
 * <p>claude 复审时质疑该结论，我重新核实 —— <b>原结论是错的</b>，根因是当时 javap 输出
 * 用的过滤正则是 {@code retry|Retry|timeout|Timeout}，而实际方法名是
 * <b>{@code maxRetries}</b>（含 {@code Retries}，不是 {@code Retry}）→ <b>正则漏匹配</b>，
 * 于是"没搜到"被误当成"不存在"，错误结论还扩散进了 4 处注释/文档。
 *
 * <p>javap 反编译的真实事实（{@code OpenAiChatModel} 构造器字节码）：
 * <pre>
 *   this.maxRetries = Utils.getOrDefault(builder.maxRetries, 2);   // 默认值 = 2
 *   RetryUtils.withRetryMappingExceptions(callable, maxRetries.intValue());
 * </pre>
 * 即：<b>默认重试 2 次（单次调用最多 3 个 HTTP 请求）</b>，且<b>可以通过
 * {@code .maxRetries(0)} 关闭</b>。正因为可关闭，才必须关闭 —— 否则它会与本项目
 * 自实现的线性退避重试<b>叠加</b>（3 × 3 = 最多 9 个请求）。
 *
 * <p>本测试用 JDK 内置 {@code HttpServer} 扮演一个「永远 429」的上游，直接
 * <b>数出真实发出的 HTTP 请求数</b>，用可观测的行为把上述事实钉死。
 * 若将来升级 LangChain4j 导致该行为变化，这里会立刻失败（而不是静默叠加）。
 */
class OpenAiRetryBehaviorTest {

    /** OpenAI 兼容的 429 错误体（LangChain4j 的 ExceptionMapper 依赖它映射成 RateLimitException）。 */
    private static final String RATE_LIMIT_BODY =
            "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_exceeded\","
                    + "\"code\":\"rate_limit_exceeded\"}}";

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个「永远返回 429」的假上游，返回它的 baseUrl（/v1 结尾）。 */
    private String startAlwaysRateLimitedServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            byte[] bytes = RATE_LIMIT_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private OpenAiChatModel model(String baseUrl, Integer maxRetries, int timeoutSeconds) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey("test-key")
                .modelName("test-model")
                .temperature(0.0)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .httpClientBuilder(new OkHttpClientBuilder());
        if (maxRetries != null) {
            builder.maxRetries(maxRetries);
        }
        return builder.build();
    }

    private void callAndExpectFailure(OpenAiChatModel chatModel) {
        assertThrows(RuntimeException.class,
                () -> chatModel.chat("hello"),
                "上游恒 429，调用必须失败（失败类型见 log/异常链）");
    }

    @Test
    @DisplayName("默认配置：内置重试确实存在 —— 1 次初始 + 2 次重试 = 3 个 HTTP 请求")
    void defaultRetriesAreTwo() throws Exception {
        String baseUrl = startAlwaysRateLimitedServer();
        // 不调用 maxRetries(...) -> 走默认（javap 实测为 2）
        callAndExpectFailure(model(baseUrl, null, 30));

        assertEquals(3, requestCount.get(),
                "默认应为「1 次初始 + 2 次重试」= 3 个请求；"
                        + "若变成 1 说明 LangChain4j 改了默认值（注释与 DESIGN 需同步订正）");
    }

    @Test
    @DisplayName("maxRetries(0)：内置重试可被真正关闭 —— 只发 1 个 HTTP 请求（本项目采用此配置）")
    void maxRetriesZeroDisablesBuiltInRetry() throws Exception {
        String baseUrl = startAlwaysRateLimitedServer();
        callAndExpectFailure(model(baseUrl, 0, 30));

        assertEquals(1, requestCount.get(),
                "maxRetries(0) 应完全关闭内置重试（只发 1 个请求）。"
                        + "本项目依赖此行为，避免内置重试与 CsAgentService 自实现的线性退避重试叠加"
                        + "（叠加后最坏 3 x 3 = 9 个请求）");
    }

    @Test
    @DisplayName("maxRetries(1)：中间值按「重试次数」语义生效 —— 1 次初始 + 1 次重试 = 2 个请求")
    void maxRetriesOneMeansOneRetry() throws Exception {
        String baseUrl = startAlwaysRateLimitedServer();
        callAndExpectFailure(model(baseUrl, 1, 30));

        assertEquals(2, requestCount.get(),
                "maxRetries 是「重试次数」（不含首次），故 1 -> 共 2 个请求；"
                        + "这条同时排除了 maxRetries(0) 是\"一次都不调用\"的误读");
    }

    @Test
    @DisplayName("每次请求都真实落到上游（排除\"假服务器压根没被访问\"导致的假绿）")
    void serverActuallyReceivesRequests() throws Exception {
        String baseUrl = startAlwaysRateLimitedServer();
        callAndExpectFailure(model(baseUrl, 0, 30));

        assertTrue(requestCount.get() >= 1,
                "假服务器必须至少收到 1 个请求，否则上一条断言的\"请求数=1\"没有意义");
    }
}
