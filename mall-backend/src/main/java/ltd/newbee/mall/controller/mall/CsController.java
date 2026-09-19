package ltd.newbee.mall.controller.mall;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.controller.vo.NewBeeMallUserVO;
import ltd.newbee.mall.service.agent.CsRateLimiter;
import ltd.newbee.mall.service.agent.CsStreamService;
import ltd.newbee.mall.service.agent.CsUsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * 客服接口（M2-5 建立，M3-C 加接入面防护）：SSE 流式问答。
 *
 * <p>职责刻意很薄 —— 解析入参、拿会话身份、<b>过一遍限流</b>、创建 {@link SseEmitter}，
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
 * <h3>M3-C 接入面防护（DESIGN §7.2）</h3>
 * 限流键：{@code u:<userId>} → {@code c:<conversationId>} → 兜底 {@code ip:<客户端IP>}。
 * <ul>
 *   <li><b>超限返回 429</b>（不是 200，也不用 error 事件糊弄）——
 *       限流是明确的拒绝，必须用状态码表达，前端才能区分"被限流"与"模型答不出来"。
 *       429 的响应体仍是 <b>SSE error 帧</b>（{@code event:error}），这样即使客户端
 *       按事件流解析也能读到原因。</li>
 *   <li><b>限流检查在"入参不合法"之前</b>：无论载荷长什么样，刷接口都会先被限流拦住。</li>
 *   <li><b>名额必须在所有结束路径释放</b>（正常/异常/超时/校验失败）——
 *       漏一条该会话就永久锁死。</li>
 * </ul>
 */
@Controller
@RequestMapping("/api/cs")
public class CsController {

    private static final Logger log = LoggerFactory.getLogger(CsController.class);

    private final CsStreamService csStreamService;
    private final CsRateLimiter rateLimiter;
    private final CsUsageMeter usageMeter;

    /**
     * <b>第二条限流维度：客户端 IP</b>（容量更大，不限 in-flight）。
     *
     * <h3>为什么必须有这一条</h3>
     * 会话维度的键是 {@code c:<conversationId>}，而 anonymous 客户端可以**自己生成** conversationId
     * —— 每换一个就是新桶，于是**轮换 conversationId 即可绕过限流**（claude 复核指出）。
     * IP 维度的容量设得比会话维度大（默认 60/分钟 vs 10/分钟），
     * 这样既堵住“换个 id 继续刷”，又不会误伤同一出口 IP 下的正常多用户。
     *
     * <p>不限 in-flight：那是会话维度的职责（同一会话同时只允许 1 个在途）。
     */
    private final CsRateLimiter ipLimiter;

    /** 单连接上限（DESIGN 决策 #40 与 DoD：SSE 连接 120s 自动收尾） */
    private final long emitterTimeoutMs;

    /** 单个问题的字符上限（§7.2「单次请求 token 上限」的第一道闸；防超长正文烧 token）。 */
    private final int maxQuestionChars;

    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CsController(CsStreamService csStreamService,
                        CsRateLimiter rateLimiter,
                        CsUsageMeter usageMeter,
                        @Value("${cs.stream.emitter-timeout-ms:120000}") long emitterTimeoutMs,
                        @Value("${cs.limit.max-question-chars:500}") int maxQuestionChars,
                        @Value("${cs.limit.ip-requests-per-minute:60}") long ipRequestsPerMinute) {
        this.csStreamService = csStreamService;
        this.rateLimiter = rateLimiter;
        this.usageMeter = usageMeter;
        this.emitterTimeoutMs = Math.max(1000, emitterTimeoutMs);
        this.maxQuestionChars = Math.max(1, maxQuestionChars);
        // IP 维度：不限 in-flight（Long.MAX_VALUE），只卡每分钟配额
        this.ipLimiter = new CsRateLimiter(Math.max(1, ipRequestsPerMinute), Long.MAX_VALUE);
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
     * <p><b>入参不合法仍走事件流</b>（200 + {@code error} 事件）：与"问题不能为空"的既有约定保持一致，
     * 客户端能拿到可读原因。
     * <b>但被限流不走事件流</b> —— 那是明确拒绝，用 <b>429</b>（见类注释）。
     *
     * @return {@link SseEmitter}（被限流时不走这里 —— 抛 {@link CsRequestRejectedException}，
     * 由本类的 {@code @ExceptionHandler} 转成 429）
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody(required = false) CsChatRequest body,
                           HttpServletRequest request) {
        usageMeter.recordRequest();

        String question = (body == null) ? null : body.question();
        String conversationId = (body == null) ? null : body.conversationId();
        Long userId = currentUserId(request.getSession(false));

        String key = limitKey(userId, conversationId, request);

        // ① 先查 IP 维度（防“轮换 conversationId 绕过限流”）；② 再查会话维度
        String ipKey = "ip:" + clientIp(request);
        CsRateLimiter.Decision ipDecision = ipLimiter.tryAcquire(ipKey);
        if (!ipDecision.allowed()) {
            usageMeter.recordDenied(ipDecision.kind());
            log.warn("客服请求被拒绝（IP 维度）：kind={}，key={}", ipDecision.kind(), safeForLog(ipKey));
            throw new CsRequestRejectedException("这个网络问得太频繁了，请稍后再试");
        }

        CsRateLimiter.Decision decision = rateLimiter.tryAcquire(key);
        if (!decision.allowed()) {
            // 会话维度被拒 → 把刚占的 IP 名额还回去，避免双重扣费
            ipLimiter.release(ipKey);
            usageMeter.recordDenied(decision.kind());
            // 被限流必须留痕：线上只看到 429 却不知道是谁在刷，是没法处置的
            log.warn("客服请求被拒绝：kind={}，key={}", decision.kind(), safeForLog(key));
            throw new CsRequestRejectedException(decision.message());
        }

        try {
            CsSseWriter writer = CsSseWriter.create(new SseEmitter(emitterTimeoutMs), emitterTimeoutMs);

            if (question == null || question.isBlank()) {
                return errorThenClose(writer, key, ipKey, "问题不能为空");
            }
            if (question.length() > maxQuestionChars) {
                // §7.2 的「单次请求 token 上限」：先卡住超长正文，再谈模型调用
                return errorThenClose(writer, key, ipKey,
                        "问题太长了（最多 " + maxQuestionChars + " 字），请精简后再问");
            }

            CsStreamService.CsStreamRequest streamRequest = new CsStreamService.CsStreamRequest(
                    question, conversationId, userId, body.goodsId(), body.orderNo());
            streamExecutor.execute(() -> runStream(streamRequest, writer, key, ipKey));
            return writer.emitter();
        } catch (RuntimeException e) {
            // 例如 streamExecutor 拒绝执行：名额已经占了，必须还回去，否则该会话锁死
            rateLimiter.release(key);
            ipLimiter.release(ipKey);
            throw e;
        }
    }

    /** 跑完整条流；<b>无论成败都在 finally 释放名额</b>。 */
    private void runStream(CsStreamService.CsStreamRequest streamRequest, CsSseWriter writer,
                           String key, String ipKey) {
        try {
            CsStreamService.CsStreamResult result = csStreamService.stream(streamRequest, writer);
            usageMeter.recordToolCalls(result.toolCalls() == null ? 0 : result.toolCalls().size());
            if (result.completed()) {
                usageMeter.recordCompleted();
            } else {
                usageMeter.recordFailed();
            }
        } catch (Throwable t) {
            // stream() 内部已兜底；这里是最后一道网：万一逃出，也要让客户端收到收尾事件，
            // 而不是干等到 120s 传输层超时。
            usageMeter.recordFailed();
            log.warn("流式问答出现未预期异常，已兜底收尾：{}", t.toString());
            writer.onError("当前咨询较多，请稍后再试");
            writer.onClose();
        } finally {
            // ⭐ 唯一释放点：正常 / 异常 / 超时 都走这里
            rateLimiter.release(key);
        }
    }

    /** 入参不合法：仍用事件流把原因说清楚（与既有约定一致），并释放名额。 */
    private SseEmitter errorThenClose(CsSseWriter writer, String key, String ipKey, String message) {
        streamExecutor.execute(() -> {
            try {
                writer.onError(message);
                writer.onClose();
            } finally {
                rateLimiter.release(key);
                ipLimiter.release(ipKey);
            }
        });
        return writer.emitter();
    }

    /**
     * 轻量健康检查：确认流式编排已装配、当前质检模式、限流配置与用量快照。
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

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("requestsPerMinute", rateLimiter.capacityPerMinute());
        limits.put("maxQuestionChars", maxQuestionChars);
        body.put("limits", limits);

        body.put("usage", usageMeter.summaryMap());
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
     * 限流键：登录用户 → conversationId → 客户端 IP（兜底）。
     *
     * <p>兜底这一步很关键：两个身份都没有时若返回空键，等于<b>所有匿名裸请求共用一个桶</b>
     * 或者干脆不限流 —— 前者会误伤、后者等于没防护。
     */
    static String limitKey(Long userId, String conversationId, HttpServletRequest request) {
        if (userId != null) {
            return "u:" + userId;
        }
        if (conversationId != null && !conversationId.isBlank()) {
            return "c:" + conversationId.trim();
        }
        return "ip:" + clientIp(request);
    }

    /**
     * 取客户端 IP。
     *
     * <p>先看 {@code X-Forwarded-For}（部署在反向代理后时才有意义）——
     * 注意该头<b>可被客户端伪造</b>，所以它只用于"兜底限流"这种弱场景，不作为安全边界。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }

    /**
     * 被限流 → <b>429</b> + SSE error 帧。
     *
     * <h3>⚠️ 为什么用异常而不是直接返回 ResponseEntity（踩过）</h3>
     * 一开始我把 {@code chat()} 的返回类型改成 {@code Object}（正常返回 SseEmitter、超限返回
     * ResponseEntity）。结果 <b>3 个既有的端到端 SSE 测试全挂</b>，报
     * {@code chunked transfer encoding, state: READING_LENGTH} / {@code EOF reached while reading}。
     * 根因：Spring 选择返回值处理器时看的是<b>方法的声明返回类型</b>，
     * 声明成 {@code Object} 后 {@code ResponseBodyEmitterReturnValueHandler} 不再认领
     * （它要求声明类型是 {@code ResponseBodyEmitter} 的子类），于是 SseEmitter 被当成普通 body
     * 交给消息转换器 → 响应体不是合法的 SSE → 客户端解析失败。
     *
     * <p>所以这里保持声明类型为 {@link SseEmitter}，把"拒绝"表达成控制器内的异常，
     * 由下面的 {@code @ExceptionHandler} 转成 429（本类的处理器优先于全局 {@code @RestControllerAdvice}）。
     */
    @ExceptionHandler(CsRequestRejectedException.class)
    public ResponseEntity<String> handleRejected(CsRequestRejectedException e) {
        String frame = "event:error\ndata:" + errorJson(e.getMessage()) + "\n\n";
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(frame);
    }

    /** 接入面防护拒绝（限流 / 单会话在途超限）——仅用于把"拒绝"从业务方法传到 429 处理器。 */
    static class CsRequestRejectedException extends RuntimeException {
        CsRequestRejectedException(String message) {
            super(message);
        }
    }

    /** 只用于我们自己写死的提示文案（仍做转义，避免日后换成动态文案时漏掉）。 */
    private static String errorJson(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"message\":\"" + escaped + "\"}";
    }

    /** 日志安全：键来自客户端（conversationId / X-Forwarded-For），去掉换行并截断，防日志注入。 */
    private static String safeForLog(String key) {
        String flat = key.replaceAll("[\\r\\n\\t]", "_");
        return flat.length() > 48 ? flat.substring(0, 48) + "…" : flat;
    }

    /**
     * 请求体。
     *
     * @param question       用户问题（必填）
     * @param conversationId 匿名会话标识，前端 localStorage 生成、随请求携带（记忆与限流维度）
     * @param goodsId        可选：当前正在看的商品（DESIGN §4.3，入口由 M3 前端提供）
     * @param orderNo        可选：当前正在看的订单（同上）
     */
    public record CsChatRequest(String question, String conversationId, Integer goodsId, String orderNo) {
    }
}
