package ltd.newbee.mall.controller.mall;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * M2-5 验收测试：SSE 事件出口 —— <b>时序红线的另一半</b>。
 *
 * <p>{@code CsStreamServiceTest} 断言的是「事件发出的先后」，本类断言的是
 * <b>这条连接什么时候、以什么次数结束</b>。这正是 DESIGN §4.2 点名的坑：
 * {@code audit} 模式下 {@code done} 先发、{@code review} 后到 ——
 * 若在 {@code done} 之后立刻 {@code complete()}，
 * 异步 {@code review} 到达时 {@code send()} 会抛 {@code IllegalStateException}。
 *
 * <p>用 mock 的 {@link SseEmitter} 直接观察 {@code send}/{@code complete} 的调用，
 * 并利用 {@code SseEventBuilder.build()} 把真实要写出的 payload 取出来断言协议字段
 * （不需要起 HTTP 服务器）。
 */
class CsSseWriterTest {

    private SseEmitter emitter;
    private CsSseWriter writer;

    @BeforeEach
    void setUp() {
        emitter = mock(SseEmitter.class);
        writer = new CsSseWriter(emitter);
    }

    /** 取出所有已 send 的事件（顺序即发送顺序） */
    private List<SseEmitter.SseEventBuilder> sentEvents() throws Exception {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    /** 某个事件里事件名 + 数据（SSE 的 name 与 data 都通过 SseEventBuilder 承载） */
    private static String payloadOf(SseEmitter.SseEventBuilder builder) {
        Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
        StringBuilder sb = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : parts) {
            sb.append(part.getData());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 🔴 红线：done 不是终止事件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("audit 红线：done 之后 review 仍能送达，且 complete 直到 onClose 才发生一次")
    void doneIsNotTerminalEvent() throws Exception {
        writer.onDelta("库存 1000 件");
        writer.onDone(4200, 1180);

        // done 已发，但绝不能已经 complete（否则异步 review 会被 IllegalStateException 打掉）
        verify(emitter, never()).complete();
        assertDoesNotThrow(() -> writer.onReview(true, "价格与工具结果一致"),
                "done 之后的 review 必须仍能送达");
        verify(emitter, never()).complete();

        writer.onClose();
        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("complete 至多一次：onClose 重复调用不会第二次 complete")
    void completeHappensAtMostOnce() {
        writer.onClose();
        writer.onClose();
        writer.onClose();
        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("已结束（超时/断开）后：再发事件不抛异常，也不真的发送")
    void sendAfterCloseIsDropped() throws Exception {
        writer.markClosed("超过 SSE 单连接上限 120000ms，由容器自动收尾（决策 #40）");

        assertDoesNotThrow(() -> writer.onDelta("这句不该发出去"));
        assertDoesNotThrow(() -> writer.onDone(1, 1));
        assertDoesNotThrow(writer::onClose);

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();   // 传输层已收尾，不再由我们 complete
    }

    @Test
    @DisplayName("send 抛异常（客户端已断开）→ 静默停止推送，不把异常抛给编排层")
    void sendFailureShouldNotPropagate() throws Exception {
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertDoesNotThrow(() -> writer.onDelta("第一段"));
        assertDoesNotThrow(() -> writer.onDelta("第二段"));

        // 断开后应停止尝试（第二次不再 send）——只尝试过一次
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ------------------------------------------------------------------
    // 传输层兜底
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超时写明的兜底：到达单连接上限后停止发送（决策 #40 / DoD）")
    void timeoutCallbackShouldStopSending() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        CsSseWriter writerWithTimeout = CsSseWriter.create(mockEmitter, 120000L);

        // 验证回调用的是「传入的 emitter」，且超时后不再发送
        ArgumentCaptor<Runnable> onTimeout = ArgumentCaptor.forClass(Runnable.class);
        verify(mockEmitter).onTimeout(onTimeout.capture());
        onTimeout.getValue().run();

        assertDoesNotThrow(() -> writerWithTimeout.onDelta("超时后不该发"));
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("传输层出错回调同样只停止发送（不 complete，交给容器）")
    void errorCallbackShouldStopSending() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        CsSseWriter writerWithTimeout = CsSseWriter.create(mockEmitter, 120000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Consumer<Throwable>> onError =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(mockEmitter).onError(onError.capture());
        onError.getValue().accept(new IllegalStateException("broken pipe"));

        assertDoesNotThrow(() -> writerWithTimeout.onError("这条不该发"));
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(mockEmitter, never()).complete();
    }

    // ------------------------------------------------------------------
    // 协议字段（对齐 DESIGN §4.2 的示例）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("协议：stage 的 elapsed 是**秒**（对齐 DESIGN 的 0.31），其余时间字段是毫秒")
    void stageElapsedShouldBeSeconds() throws Exception {
        writer.onStage("检索知识库", 310);
        writer.onTool("check_stock", "{\"goodsId\":10003}", 42, true);
        writer.onDone(4210, 1180);

        List<SseEmitter.SseEventBuilder> events = sentEvents();
        String stage = payloadOf(events.get(0));
        assertTrue(stage.contains("\"stage\":\"检索知识库\""), stage);
        assertTrue(stage.contains("\"elapsed\":0.31"), "elapsed 单位应是秒：" + stage);

        String tool = payloadOf(events.get(1));
        assertTrue(tool.contains("\"name\":\"check_stock\""), tool);
        assertTrue(tool.contains("\"ms\":42"), "tool 的耗时单位应是毫秒：" + tool);
        assertTrue(tool.contains("\"ok\":true"), tool);

        String done = payloadOf(events.get(2));
        assertTrue(done.contains("\"totalMs\":4210"), done);
        assertTrue(done.contains("\"firstTokenMs\":1180"), done);
    }

    @Test
    @DisplayName("协议：tool 的 args 解析成 JSON 对象；解析不了时原样送出（不上报比上报错更难排查）")
    void toolArgsShouldBeParsedAsJsonObject() throws Exception {
        writer.onTool("checkStock", "{\"goodsId\":10003}", 5, true);
        writer.onTool("checkStock", "not-json", 5, false);
        writer.onTool("checkStock", "", 5, true);

        List<SseEmitter.SseEventBuilder> events = sentEvents();
        assertTrue(payloadOf(events.get(0)).contains("\"args\":{\"goodsId\":10003}"),
                payloadOf(events.get(0)));
        assertTrue(payloadOf(events.get(1)).contains("\"args\":\"not-json\""),
                payloadOf(events.get(1)));
        assertTrue(payloadOf(events.get(2)).contains("\"args\":{}"), payloadOf(events.get(2)));
    }

    @Test
    @DisplayName("协议：review 的 qualified 允许 null（质检超时 = 未得出结论，不能伪装成不合格）")
    void reviewQualifiedMayBeNull() throws Exception {
        writer.onReview(null, "质检超时");
        writer.onReview(true, "合格");

        List<SseEmitter.SseEventBuilder> events = sentEvents();
        String timeout = payloadOf(events.get(0));
        assertTrue(timeout.contains("\"qualified\":null"), timeout);
        assertTrue(timeout.contains("\"reason\":\"质检超时\""), timeout);
        assertTrue(payloadOf(events.get(1)).contains("\"qualified\":true"), payloadOf(events.get(1)));
    }

    @Test
    @DisplayName("协议：数据是原样文本（不能被二次 JSON 编码成 \"\\\"{...}\\\"\"）")
    void dataShouldNotBeDoubleEncoded() throws Exception {
        writer.onDelta("「无印良品化妆水」");
        List<SseEmitter.SseEventBuilder> events = sentEvents();

        Set<ResponseBodyEmitter.DataWithMediaType> parts = events.get(0).build();
        ResponseBodyEmitter.DataWithMediaType dataPart = parts.stream()
                .filter(p -> p.getData() instanceof String s && s.startsWith("{"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应有一个 JSON 文本部件，实际：" + parts));
        // 用 TEXT_PLAIN 送出，数据必须是原始 JSON 文本（不是被 JSON 转义过的字符串）
        assertEquals(MediaType.TEXT_PLAIN, dataPart.getMediaType(), "应以 TEXT_PLAIN 原样送出");
        String json = (String) dataPart.getData();
        assertTrue(json.startsWith("{\"text\":"), json);
        assertFalse(json.startsWith("\"{"),
                "若出现双重编码（外层再套引号），前端 JSON.parse 会拿到字符串而不是对象：" + json);
    }

    @Test
    @DisplayName("error 事件带可读话术（不含上游原始报文）")
    void errorEventShouldCarryReadableMessage() throws Exception {
        writer.onError("当前咨询较多，请稍后再试");
        assertTrue(payloadOf(sentEvents().get(0)).contains("\"message\":\"当前咨询较多，请稍后再试\""));
    }
}
