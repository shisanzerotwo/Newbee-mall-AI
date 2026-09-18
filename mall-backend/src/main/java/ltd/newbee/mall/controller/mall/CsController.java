package ltd.newbee.mall.controller.mall;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.controller.vo.NewBeeMallUserVO;
import ltd.newbee.mall.service.agent.CsStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客服接口（M2-5）：SSE 流式问答。
 *
 * <p>职责刻意很薄 —— 解析入参、拿会话身份、创建 {@link SseEmitter}，
 * 然后把整条流交给 {@link CsStreamService}（时序与事件协议都在那里 + {@link CsSseWriter}）。
 *
 * <h3>为什么用虚拟线程跑</h3>
 * {@code stream(...)} 会阻塞到「回答 + 质检」全部结束（audit 下还包括等到 review）。
 * Servlet 线程只应负责创建 emitter 并立刻返回（Servlet 异步语义），
 * 因此把同步等待放到虚拟线程上 —— 这也是 DoD「并发 10 不阻塞」的前提。
 *
 * <h3>身份：不信任前端传的 userId</h3>
 * 登录用户从 session 取（与 {@code GoodsReviewController} 同口径）；
 * 未登录一律用前端持久化的 {@code conversationId}。
 * <b>刻意不启用 Spring Session、也不用 sessionId</b> —— 见 DESIGN §7.4。
 * 匿名请求用 {@code getSession(false)}，不为聊天凭空建 session。
 *
 * <p>⚠️ 本接口<b>匿名可达且暂无鉴权/限流</b>（决策 #40 的限流属 M3）——
 * 这是与设计一致的阶段性状态，不是遗漏。
 */
@Controller
@RequestMapping("/api/cs")
public class CsController {

    private static final Logger log = LoggerFactory.getLogger(CsController.class);

    private final CsStreamService csStreamService;

    /** 单连接上限（DESIGN 决策 #40 与 DoD：SSE 连接 120s 自动收尾） */
    private final long emitterTimeoutMs;

    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CsController(CsStreamService csStreamService,
                        @Value("${cs.stream.emitter-timeout-ms:120000}") long emitterTimeoutMs) {
        this.csStreamService = csStreamService;
        this.emitterTimeoutMs = Math.max(1000, emitterTimeoutMs);
    }

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdown();
    }

    /**
     * 流式问答。
     *
     * <p>返回 {@code text/event-stream}，事件见 DESIGN §4.2
     * （{@code stage} / {@code tool} / {@code delta} / {@code review} / {@code done} / {@code error}）。
     *
     * <p><b>入参不合法也走事件流</b>：{@code EventSource} 在非 2xx 时拿不到响应体，
     * 与其让前端「连接失败」一无所知，不如用 {@code error} 事件把原因说清楚。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody(required = false) CsChatRequest body,
                           HttpServletRequest request) {
        CsSseWriter writer = CsSseWriter.create(new SseEmitter(emitterTimeoutMs), emitterTimeoutMs);

        String question = (body == null) ? null : body.question();
        if (question == null || question.isBlank()) {
            streamExecutor.execute(() -> {
                writer.onError("问题不能为空");
                writer.onClose();
            });
            return writer.emitter();
        }

        Long userId = currentUserId(request.getSession(false));
        CsStreamService.CsStreamRequest streamRequest = new CsStreamService.CsStreamRequest(
                question, body.conversationId(), userId, body.goodsId(), body.orderNo());

        streamExecutor.execute(() -> {
            try {
                csStreamService.stream(streamRequest, writer);
            } catch (Throwable t) {
                // stream() 内部已兜底；这里是最后一道网：万一逃出，也要让客户端收到收尾事件，
                // 而不是干等到 120s 传输层超时。
                log.warn("流式问答出现未预期异常，已兜底收尾：{}", t.toString());
                writer.onError("当前咨询较多，请稍后再试");
                writer.onClose();
            }
        });
        return writer.emitter();
    }

    /**
     * 轻量健康检查：确认流式编排已装配、以及当前质检模式。
     *
     * <p>存在的意义：SSE 接口不便用普通 curl 一眼看出配置是否生效（要读事件流），
     * 这个接口让「装配对不对」可以一步验证。不含任何密钥。
     */
    @GetMapping("/health")
    @ResponseBody
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("qaMode", csStreamService.qaModeName());
        body.put("sseTimeoutMs", emitterTimeoutMs);
        return body;
    }

    /** 登录用户 id；匿名返回 {@code null}（绝不从请求体取 userId —— 那是可伪造的） */
    private static Long currentUserId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object attribute = session.getAttribute(Constants.MALL_USER_SESSION_KEY);
        if (attribute instanceof NewBeeMallUserVO user) {
            return user.getUserId();
        }
        return null;
    }

    /**
     * 请求体。
     *
     * @param question       用户问题（必填）
     * @param conversationId 匿名会话标识，前端 localStorage 生成、随请求携带（记忆维度）
     * @param goodsId        可选：当前正在看的商品（DESIGN §4.3，入口由 M3 前端提供）
     * @param orderNo        可选：当前正在看的订单（同上）
     */
    public record CsChatRequest(String question, String conversationId, Integer goodsId, String orderNo) {
    }
}
