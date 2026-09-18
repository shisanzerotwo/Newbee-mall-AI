package ltd.newbee.mall.controller.common;

import ltd.newbee.mall.common.NewBeeMallException;
import ltd.newbee.mall.util.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 【回归】全局异常处理器必须放过 SSE 请求。
 *
 * <h3>为什么（M3 并发压测时真实踩到）</h3>
 * SSE 端点的响应头已被固定为 {@code text/event-stream}。而当时的处理器会看请求的
 * {@code Content-Type: application/json}（SSE 客户端的正常写法）→ 判为 ajax → 返回 {@link Result}，
 * 但没有任何 {@code HttpMessageConverter} 能把 {@code Result} 写成 {@code text/event-stream}：
 * <pre>
 *   Failure in @ExceptionHandler ... NewBeeMallExceptionHandler
 *   org.springframework.http.converter.HttpMessageNotWritableException:
 *     No converter for [class ltd.newbee.mall.util.Result] with preset Content-Type 'text/event-stream'
 * </pre>
 * 结果是「异常处理器自身失败」—— 二次异常，且堆栈指向处理器而不是真因，极难排查。
 *
 * <p>此用例同时守住两条：① SSE 请求不再写响应体；② <b>普通请求行为不变</b>
 * （不能为了修 SSE 把既有的 ajax / 错误页语义改坏）。
 */
class NewBeeMallExceptionHandlerSseTest {

    private final NewBeeMallExceptionHandler handler = new NewBeeMallExceptionHandler();

    private static MockHttpServletRequest req(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        return r;
    }

    @Test
    @DisplayName("SSE 请求：不得返回 Result/ModelAndView（否则写不出去 → 二次异常）")
    void sseRequestMustNotProduceResponseBody() {
        MockHttpServletRequest req = req("POST", "/api/cs/chat");
        req.addHeader("Content-Type", "application/json");   // ← 正是当初被误判为 ajax 的组合
        req.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        Object out = handler.handleException(new RuntimeException("上游崩了"), req, resp);

        assertNull(out, "SSE 请求的异常不得返回任何响应体 —— 响应头已是 text/event-stream，"
                + "返回 Result(JSON) 或 ModelAndView(HTML) 都会让异常处理器自身失败");
        // ⭐ 必须置 500：返回 null 时容器默认会当「已处理」→ 200 + 空体，
        //    那就把“出错”伪装成“成功”，正是本项目反复强调要避免的「200 掩盖错误」
        //   （claude 复核指出：我第一版只 return null，把错误信号从 500 变成了 200）
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getStatus(),
                "SSE 请求出错必须显式置 500，不能让客户端看到 200 + 空体");
    }

    @Test
    @DisplayName("SSE 请求：只看 Content-Type=json 也不能漏判（当初就是这个组合踩的坑）")
    void sseRequestDetectedByUriEvenWithoutAcceptHeader() {
        MockHttpServletRequest req = req("POST", "/api/cs/chat");
        req.addHeader("Content-Type", "application/json");   // 没有 Accept 头
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertNull(handler.handleException(new RuntimeException("x"), req, resp),
                "路径是 /api/cs/chat 就应判为 SSE —— 仅靠 Accept 判不可靠（curl 默认发 */*）");
        assertEquals(500, resp.getStatus(), "同样要置 500");
    }

    @Test
    @DisplayName("/api/cs/health 不是 SSE（JSON 端点），异常仍应返回 Result（判定不能过宽）")
    void healthEndpointIsNotTreatedAsSse() {
        MockHttpServletRequest req = req("GET", "/api/cs/health");
        req.addHeader("Content-Type", "application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        Object out = handler.handleException(new RuntimeException("x"), req, resp);

        assertInstanceOf(Result.class, out,
                "/api/cs/health 是普通 JSON 端点，不能被当成 SSE 而丢掉响应体"
                        + "（claude 指出：URI 判据应收到 /api/cs/chat，而非整个 /api/cs/ 前缀）");
    }

    @Test
    @DisplayName("普通 ajax 请求：行为不变，仍返回 Result（不能改坏既有语义）")
    void normalAjaxRequestStillReturnsResult() {
        MockHttpServletRequest req = req("GET", "/admin/goods/list");
        req.addHeader("Content-Type", "application/json");

        Object out = handler.handleException(new RuntimeException("x"), req, new MockHttpServletResponse());

        assertInstanceOf(Result.class, out, "普通 ajax 请求必须仍返回 Result —— 修 SSE 不能改坏既有行为");
    }

    @Test
    @DisplayName("普通页面请求：行为不变，仍返回 error 视图")
    void normalPageRequestStillReturnsErrorView() {
        MockHttpServletRequest req = req("GET", "/goods/detail/1");

        Object out = handler.handleException(new NewBeeMallException("参数不对"), req,
                new MockHttpServletResponse());

        assertInstanceOf(ModelAndView.class, out, "普通页面请求应仍返回 error 视图");
    }
}
