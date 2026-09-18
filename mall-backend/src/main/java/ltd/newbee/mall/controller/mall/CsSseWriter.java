package ltd.newbee.mall.controller.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.newbee.mall.service.agent.CsStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 适配器（M2-5）：把 {@link CsStreamService.CsStreamListener} 的事件写成
 * {@code text/event-stream}，并<b>独占</b>「这条连接什么时候算结束」的判断。
 *
 * <h3>🔴 为什么必须由这里（而不是编排层）决定 complete 时机</h3>
 * {@code audit}（默认）模式下 {@code done} 先发、{@code review} 后到 ——
 * 也就是说 <b>{@code done} 不是终止事件</b>。若在 {@code done} 之后立刻
 * {@code complete()}，异步 {@code review} 到达时 {@code send()} 会抛
 * {@code IllegalStateException}（DESIGN §4.2 点名的坑）。
 * 所以这里只在编排层明确发出 {@link #onClose()} 时才 {@code complete()}。
 *
 * <h3>幂等</h3>
 * {@code complete()} 与「停止发送」都由同一个 {@link AtomicBoolean} 把关：
 * 无论是正常收尾、传输层超时（决策 #40 的 120s 上限）、还是客户端断开，
 * <b>complete 至多发生一次</b>；一旦置位，后续 {@code send} 静默丢弃
 * （客户端已经走了，再抛异常只会污染日志）。
 */
public class CsSseWriter implements CsStreamService.CsStreamListener {

    private static final Logger log = LoggerFactory.getLogger(CsSseWriter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SseEmitter emitter;

    /** 唯一的「这条流已结束」标志：既守 complete()，也守后续 send() */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public CsSseWriter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * 给一个 emitter 装上「传输层超时/出错只停止发送」的彫候。
     *
     * <p>emitter 由调用方传入（而不是在这里 new）：一是控制器能自己决定超时，
     * 二是测试可以传入 mock 来验证彫候真的被注册、以及被触发后不再发送。
     *
     * @param timeoutMs 单连接上限（DESIGN 决策 #40：120s）
     */
    public static CsSseWriter create(SseEmitter emitter, long timeoutMs) {
        CsSseWriter writer = new CsSseWriter(emitter);
        // 超时/出错都只「停止发送」：emitter 的生命周期由容器收尾，
        // 我们不在这里调 complete()（也不影响编排层继续把日志写完）。
        emitter.onTimeout(() -> writer.markClosed(
                "超过 SSE 单连接上限 " + timeoutMs + "ms，由容器自动收尾（决策 #40）"));
        emitter.onError(t -> writer.markClosed("SSE 连接出错：" + t));
        return writer;
    }

    public SseEmitter emitter() {
        return emitter;
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @Override
    public void onStage(String stage, long elapsedMs) {
        // DESIGN §4.2 的示例是 {"stage":"检索知识库","elapsed":0.31} —— elapsed 的单位是**秒**
        // （tool 事件的 ms、done 的 totalMs/firstTokenMs 才是毫秒）。这里按协议换算。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("elapsed", Math.round(elapsedMs / 10.0) / 100.0);
        send("stage", payload);
    }

    /**
     * RAG 引用来源（DESIGN §8.2「引用来源（含融合分）」区块）。
     *
     * <p>只发 {@code title / score / goodsId}，<b>不发 chunk 正文</b>：面板只需「来源 + 融合分」，
     * 把知识库原文整段推给浏览器既无必要也浪费带宽。
     *
     * <p>这是 M3-A 新增事件，老客户端忽略即可（向后兼容）。
     */
    @Override
    public void onRag(List<CsStreamService.RagSource> sources) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sources", sources);
        send("rag", payload);
    }

    @Override
    public void onTool(String name, String args, long ms, boolean ok) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("args", rawJsonOrString(args));
        payload.put("ms", ms);
        payload.put("ok", ok);
        send("tool", payload);
    }

    @Override
    public void onDelta(String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        send("delta", payload);
    }

    @Override
    public void onReview(Boolean qualified, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("qualified", qualified);   // null = 未得出结论（质检超时）
        payload.put("reason", reason);
        send("review", payload);
    }

    @Override
    public void onDone(long totalMs, long firstTokenMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalMs", totalMs);
        payload.put("firstTokenMs", firstTokenMs);   // -1 表示从未收到 delta
        send("done", payload);
    }

    @Override
    public void onError(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        send("error", payload);
    }

    /**
     * 流真正结束 —— 这里（且只有这里）{@code complete()}。
     *
     * <p>可能在 {@code audit} 的 {@code done} 之后很久才被调用，这正是设计意图。
     */
    @Override
    public void onClose() {
        if (closed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("SSE complete() 失败（客户端可能已断开）：{}", e.toString());
            }
        }
    }

    /**
     * 传输层已结束（超时 / 连接出错）：只置位，不再发送，也不再 complete。
     *
     * <p>幂等，供 {@code SseEmitter} 的回调调用。
     */
    public void markClosed(String reason) {
        if (closed.compareAndSet(false, true)) {
            log.warn("SSE 流已由传输层结束：{}", reason);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void send(String event, Object payload) {
        if (closed.get()) {
            // 已经结束（正常收尾 / 超时 / 客户端断开）：丢弃即可，不抛 IllegalStateException
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(payload);
            // 必须显式 TEXT_PLAIN：数据已经是 JSON 字符串，交给 JSON 转换器会被二次编码成
            // "\"{...}\""（SSE 的 data 行只需原样携带文本，由客户端解析 JSON）
            emitter.send(SseEmitter.event().name(event).data(json, MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            // 客户端断开 / emitter 已完成：这是**传输层**的结束，不是编排层的错误。
            // 置位以停止后续发送；编排层会自然跑完（其结果是日志与记忆，仍要写）。
            if (closed.compareAndSet(false, true)) {
                log.debug("发送 {} 事件失败，已停止推送（客户端可能已断开）：{}", event, e.toString());
            }
        }
    }

    /**
     * 工具参数是模型给的 JSON 字符串，尽量解析成对象再放进事件
     * （DESIGN 的示例是 {@code "args":{"goodsId":10003}}）；
     * 解析不了就原样作为字符串送出 —— 不上报比上报错更难排查。
     */
    private static Object rawJsonOrString(String args) {
        if (args == null || args.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readTree(args);
        } catch (Exception e) {
            return args;
        }
    }
}
