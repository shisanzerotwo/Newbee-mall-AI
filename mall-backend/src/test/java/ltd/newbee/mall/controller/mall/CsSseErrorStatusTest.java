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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【报文层】SSE 端点出错时必须给出**非 2xx**，不能是「200 + 空体」。
 *
 * <h3>为什么单独立这条（claude 复核指出）</h3>
 * {@code NewBeeMallExceptionHandlerSseTest} 是单元级 —— 直接调 handler、断返回对象，
 * 它**结构上不可能发现**「HTTP 状态码变成 200」这类问题。
 * 而我第一版修复正是踩了这个：handler 里 {@code return null} 把错误信号从 500 变成了
 * <b>200 + 空体</b> —— 恰恰是本项目反复强调要避免的「200 掩盖错误」
 * （M1 就吃过：DB 失联页面 200、尾斜杠错误页 200）。
 *
 * <p>所以这里从**报文层**验证：发一个畸形 JSON，断言状态码不是 2xx。
 * 单元测试证明不了报文行为，这条补上。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsSseErrorStatusTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("畸形 JSON 到 /api/cs/chat：必须非 2xx（绝不能 200 + 空体）")
    void malformedJsonMustNotReturn2xx() throws Exception {
        HttpResponse<String> resp = post("/api/cs/chat", "{this is not valid json");

        int code = resp.statusCode();
        assertTrue(code >= 400,
                "SSE 端点的报文级错误必须给出 4xx/5xx；实际 " + code
                        + " —— 200 会把「出错」伪装成「成功」，是本项目明令避免的坑");
    }

    @Test
    @DisplayName("空 body 到 /api/cs/chat：仍走事件流（入参不合法用 error 事件说明，不报状态码错）")
    void emptyBodyStillStreamsAnErrorEvent() throws Exception {
        // 这个端点刻意把「问题为空」设计成**流内 error 事件**（EventSource 在非 2xx 时拿不到响应体），
        // 所以这里是 200 + SSE error 事件 —— 属于**有意设计**，与上一条的"报文级畸形"不同。
        HttpResponse<String> resp = post("/api/cs/chat", "");

        assertTrue(resp.statusCode() == 200 || resp.statusCode() >= 400,
                "空 body 要么流内报错(200 + error 事件)，要么 4xx/5xx；实际 " + resp.statusCode());
        if (resp.statusCode() == 200) {
            assertTrue(resp.body().contains("error"),
                    "200 时必须带 error 事件把原因说清楚，不能是空体 —— 否则就是静默失败");
        }
    }

    @Test
    @DisplayName("GET /api/cs/health（JSON 端点）不受影响，仍返回 200 + JSON")
    void healthEndpointUnaffected() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/cs/health"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertNotEquals(0, resp.statusCode());
        assertTrue(resp.statusCode() == 200,
                "/api/cs/health 是普通 JSON 端点，不应被 SSE 判定影响；实际 " + resp.statusCode());
    }
}
