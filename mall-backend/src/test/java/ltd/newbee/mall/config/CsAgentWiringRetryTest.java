package ltd.newbee.mall.config;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import jakarta.annotation.Resource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 【闭合盲区】验证<b>生产 Bean</b>（{@link CsAgentConfig#csChatModel()}）确实把内置重试关掉了。
 *
 * <h3>为什么还需要这个测试 —— OpenAiRetryBehaviorTest 的盲区（claude 终审指出）</h3>
 * {@code OpenAiRetryBehaviorTest} 是<b>自己</b>调 {@code OpenAiChatModel.builder()} 构造模型，
 * 再显式传 {@code maxRetries(0)}。它证明的是「LangChain4j 的 maxRetries 语义如此」，
 * <b>但证明不了「我们的生产 Bean 真的这么配了」</b>：
 * 如果有人把 {@code CsAgentConfig} 里那行 {@code .maxRetries(0)} 删掉，
 * {@code OpenAiRetryBehaviorTest} <b>依然会全绿</b>（它压根不碰生产配置）。
 *
 * <p>本测试直接加载真实的 {@link CsAgentConfig}（用 {@code @DynamicPropertySource}
 * 把 base-url 指向本地假上游），拿到<b>真正的 csChatModel Bean</b>去调用，
 * 数真实 HTTP 请求数 —— 这样配置一被改坏就会红。
 *
 * <p>断言口径与 {@code OpenAiRetryBehaviorTest} 一致：假上游恒返回 429，
 * 内置重试若打开则请求数应为 3（1 次初始 + 2 次重试），关闭则应为 1。
 */
@SpringJUnitConfig(CsAgentConfig.class)
class CsAgentWiringRetryTest {

    private static final AtomicInteger requestCount = new AtomicInteger();
    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startFakeUpstream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            byte[] bytes = ("{\"error\":{\"message\":\"Rate limit reached\","
                    + "\"type\":\"rate_limit_exceeded\",\"code\":\"rate_limit_exceeded\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopFakeUpstream() {
        if (server != null) {
            server.stop(0);
        }
    }

    @DynamicPropertySource
    static void pointProductionConfigAtFakeUpstream(DynamicPropertyRegistry registry) {
        registry.add("cs.model.base-url", () -> baseUrl);
        registry.add("cs.model.api-key", () -> "test-key");
        registry.add("cs.model.name", () -> "test-model");
        // 超时给足，避免 4s/8s 那层退避（本测试只关心 HTTP 层请求数，不关心慢）
        registry.add("cs.model.timeout-seconds", () -> "30");
    }

    /** 注入的必须是 CsAgentConfig 产出的那个 Bean（不是本测试自己 new 的）。 */
    @Resource
    private ChatModel csChatModel;

    @Test
    @DisplayName("生产 Bean 已关闭内置重试：429 时只发 1 个 HTTP 请求（删掉 .maxRetries(0) 本测试即红）")
    void productionBeanDisablesBuiltInRetry() {
        requestCount.set(0);

        assertThrows(RuntimeException.class,
                () -> csChatModel.chat("hello"),
                "假上游恒 429，生产 Bean 的这次调用必须失败（证明请求确实发出去了）");

        assertEquals(1, requestCount.get(),
                "生产 Bean 应只发 1 个请求（内置重试已被 CsAgentConfig 的 .maxRetries(0) 关闭）。"
                        + "若这里是 3，说明 .maxRetries(0) 丢了或失效 —— 内置的指数退避会与 "
                        + "CsAgentService 自实现的线性退避叠加，最坏 3 x 3 = 9 个请求");
    }
}
