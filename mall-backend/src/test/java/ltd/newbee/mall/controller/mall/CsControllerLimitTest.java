package ltd.newbee.mall.controller.mall;

import ltd.newbee.mall.service.agent.CsRateLimiter;
import ltd.newbee.mall.service.agent.CsStreamService;
import ltd.newbee.mall.service.agent.CsUsageMeter;
import ltd.newbee.mall.service.agent.QaReviewer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制器层的接入面防护测试（M3-C）。
 *
 * <p>与 {@code CsRateLimiterTest}（纯限流逻辑）互补：这里验证<b>接线是否正确</b>——
 * 超限真的返回 <b>429</b>（不是 200、不是 500）、键的选取符合 §7.4 的会话维度、
 * 以及在途名额被占用时不会把后续请求放进来。
 *
 * <p>用 {@code MockHttpServletRequest} + mock 的 {@link CsStreamService}，
 * 不起 Spring —— 这样能毫秒级、确定性地覆盖验收项 1~4。
 */
class CsControllerLimitTest {

    private static CsController controller(CsRateLimiter limiter, CsStreamService service) {
        return new CsController(service, limiter, new CsUsageMeter(1000),
                120_000L, 500);
    }

    private static CsController.CsChatRequest ask(String conversationId) {
        return new CsController.CsChatRequest("这个多少钱", conversationId, null, null);
    }

    /**
     * 调用 chat() 并把"被拒"转成 429 响应。
     *
     * <p>为什么这样测：{@code chat()} 的声明返回类型必须保持 {@link SseEmitter}
     * （否则 Spring 不再用异步处理器认领它 —— 见 CsController 里的长注释），
     * 所以拒绝是通过本控制器的 {@code @ExceptionHandler} 表达成 429 的。
     * 这里直接调用那个处理器，等价于容器会做的事。
     */
    private static ResponseEntity<String> rejected(CsController controller,
                                                   CsController.CsChatRequest body,
                                                   MockHttpServletRequest req) {
        try {
            controller.chat(body, req);
            throw new AssertionError("本应被拒绝，但 chat() 正常返回了");
        } catch (CsController.CsRequestRejectedException e) {
            return controller.handleRejected(e);
        }
    }

    /** stream() 立刻返回一个"已完成"的结果。 */
    private static CsStreamService instantService() {
        CsStreamService service = Mockito.mock(CsStreamService.class);
        CsStreamService.CsStreamResult result = new CsStreamService.CsStreamResult(
                "有的～", List.of(), QaReviewer.QaMode.AUDIT, null, true);
        Mockito.when(service.stream(Mockito.any(), Mockito.any())).thenReturn(result);
        return service;
    }

    // ------------------------------------------------------------------
    // 键的选取（§7.4 的会话维度）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("限流键：登录用 userId、匿名用 conversationId、都没有时兜底 IP")
    void limitKeyFollowsSessionDimension() {
        MockHttpServletRequest req = new MockHttpServletRequest();

        assertEquals("u:7", CsController.limitKey(7L, "ignored", req), "登录用户优先用 userId");
        assertEquals("c:abc", CsController.limitKey(null, "abc", req), "匿名用 conversationId");
        assertEquals("ip:127.0.0.1", CsController.limitKey(null, "  ", req),
                "两者都没有时必须兜底 IP —— 否则等于没限流");
    }

    @Test
    @DisplayName("限流键：有 X-Forwarded-For 时取其第一段（反向代理后的真实来源）")
    void limitKeyUsesFirstForwardedForEntry() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        assertEquals("ip:203.0.113.9", CsController.limitKey(null, null, req));
    }

    // ------------------------------------------------------------------
    // 验收项 1：限流触发 → 429
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超限返回 429（不是 200/500），响应体是 SSE error 帧且文案可读")
    void overLimitReturns429() {
        CsRateLimiter limiter = new CsRateLimiter(3, 100);   // 在途放宽 → 本用例只测限流
        CsController controller = controller(limiter, instantService());
        MockHttpServletRequest req = new MockHttpServletRequest();

        for (int i = 1; i <= 3; i++) {
            assertInstanceOf(SseEmitter.class, controller.chat(ask("conv-429"), req),
                    "第 " + i + " 次应正常开流");
        }

        ResponseEntity<String> resp = rejected(controller, ask("conv-429"), req);
        assertEquals(429, resp.getStatusCode().value(),
                "超限必须是 429（不能 200，本项目反复踩过『200 掩盖错误』）");
        String body = String.valueOf(resp.getBody());
        assertTrue(body.contains("event:error"), "响应体仍是 SSE error 帧，客户端能读到原因。实际=" + body);
        assertTrue(body.contains("每分钟最多 3 次"), "文案要说清限制。实际=" + body);
    }

    @Test
    @DisplayName("不同会话互不影响：A 被限流后 B 仍能请求")
    void otherConversationIsNotAffected() {
        CsRateLimiter limiter = new CsRateLimiter(1, 100);
        CsController controller = controller(limiter, instantService());
        MockHttpServletRequest req = new MockHttpServletRequest();

        assertInstanceOf(SseEmitter.class, controller.chat(ask("conv-A"), req));
        assertEquals(429, rejected(controller, ask("conv-A"), req).getStatusCode().value(), "A 应被限流");
        assertInstanceOf(SseEmitter.class, controller.chat(ask("conv-B"), req), "B 不应被误伤");
    }

    // ------------------------------------------------------------------
    // 验收项 2/4：in-flight 去重 + 释放正确
    // ------------------------------------------------------------------

    @Test
    @DisplayName("in-flight：上一条未答完时同一会话被拒；答完后立刻可再请求（不会永久锁死）")
    void inFlightBlocksThenReleases() throws Exception {
        CsRateLimiter limiter = new CsRateLimiter(100, 1);
        CountDownLatch gate = new CountDownLatch(1);

        CsStreamService service = Mockito.mock(CsStreamService.class);
        CsStreamService.CsStreamResult result = new CsStreamService.CsStreamResult(
                "有的～", List.of(), QaReviewer.QaMode.AUDIT, null, true);
        Mockito.when(service.stream(Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            gate.await(10, TimeUnit.SECONDS);     // 模拟"还在回答中"
            return result;
        });

        CsController controller = controller(limiter, service);
        MockHttpServletRequest req = new MockHttpServletRequest();

        assertInstanceOf(SseEmitter.class, controller.chat(ask("conv-lock"), req), "第一个应放行");

        ResponseEntity<String> resp = rejected(controller, ask("conv-lock"), req);
        assertEquals(429, resp.getStatusCode().value(), "上一条还在回答中，第二个必须被拒");
        assertTrue(String.valueOf(resp.getBody()).contains("还在回答中"),
                "要说清原因。实际=" + resp.getBody());

        // 放开那个"在答题的请求" → 名额必须被释放
        gate.countDown();

        boolean acceptedAgain = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !acceptedAgain) {
            try {
                controller.chat(ask("conv-lock"), req);
                acceptedAgain = true;
            } catch (CsController.CsRequestRejectedException stillLocked) {
                Thread.sleep(50);      // 名额还没释放（异步任务在跑），继续等
            }
        }
        assertTrue(acceptedAgain,
                "请求结束后同一会话必须能再次提问 —— 否则这条会话被永久锁死（漏释放名额）");
    }

    // ------------------------------------------------------------------
    // 长度上限（§7.2 的 token 闸门）与既有约定不冲突
    // ------------------------------------------------------------------

    @Test
    @DisplayName("问题超长：仍走事件流（200 + error 事件）而不是 429 —— 与「入参不合法走事件流」的既有约定一致")
    void tooLongQuestionStillStreamsAnError() {
        CsRateLimiter limiter = new CsRateLimiter(100, 100);
        CsController controller = controller(limiter, instantService());
        MockHttpServletRequest req = new MockHttpServletRequest();

        String tooLong = "很".repeat(501);      // 上限 500
        Object out = controller.chat(new CsController.CsChatRequest(tooLong, "conv-long", null, null), req);

        assertInstanceOf(SseEmitter.class, out,
                "超长属『入参不合法』，按既有约定用事件流说明原因（不是限流那种明确拒绝）");
    }

    @Test
    @DisplayName("空问题：沿用既有行为（仍走事件流），且名额会被释放（不会把后续请求卡住）")
    void blankQuestionReleasesSlot() throws Exception {
        CsRateLimiter limiter = new CsRateLimiter(100, 1);
        CsController controller = controller(limiter, instantService());
        MockHttpServletRequest req = new MockHttpServletRequest();

        for (int i = 1; i <= 5; i++) {
            // ⚠️ 校验失败路径的 release 发生在**异步任务**里（errorThenClose 内部的 finally），
            //   所以不能发完就立刻发下一条 —— 那一刻名额可能还占着。这里轮询等它释放。
            boolean accepted = false;
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && !accepted) {
                try {
                    controller.chat(new CsController.CsChatRequest("  ", "conv-blank", null, null), req);
                    accepted = true;
                } catch (CsController.CsRequestRejectedException notYetReleased) {
                    Thread.sleep(20);      // 校验失败路径的释放是异步的，等它
                }
            }
            assertTrue(accepted, "第 " + i + " 次：校验失败后名额必须被释放，不该被 in-flight 永久卡住");
        }
    }
}
