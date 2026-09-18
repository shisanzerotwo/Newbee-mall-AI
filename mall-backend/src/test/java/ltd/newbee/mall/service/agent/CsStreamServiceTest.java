package ltd.newbee.mall.service.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2-5 验收测试：流式编排（事件顺序 + **时序红线** + 工具循环 + 重试边界 + 记忆钩子）。
 *
 * <p><b>全程 Mock，不调真实模型 / 不碰 MySQL</b>：这里验证的是**编排与协议**，
 * 不是模型能力（后者由真实调用 IT 验证）。因此可在 CI 里稳定跑。
 *
 * <p>时序红线（DESIGN §4.2）在本类与 {@code CsSseWriterTest} 各守一半：
 * 本类断言<b>事件发出的先后</b>（audit: done → review；gate: review → done），
 * {@code CsSseWriterTest} 断言<b>complete 的时机与次数</b>（done 之后不能立刻 complete）。
 */
class CsStreamServiceTest {

    private static final int MAX_ROUNDS = 2;
    private static final long REVIEW_TIMEOUT_MS = 2000;

    private StreamingChatModel streamingModel;
    private MallTools mallTools;
    private RagService ragService;
    private QaReviewer qaReviewer;
    private CsChatMemoryService memoryService;
    private CsStreamService service;

    /** 脚本化的「模型每一轮怎么回应」 */
    private final Deque<Turn> turns = new ArrayDeque<>();

    private int chatCalls;

    @BeforeEach
    void setUp() {
        streamingModel = mock(StreamingChatModel.class);
        mallTools = mock(MallTools.class);
        ragService = mock(RagService.class);
        qaReviewer = mock(QaReviewer.class);
        memoryService = mock(CsChatMemoryService.class);
        when(ragService.asPrompt(anyString(), anyInt())).thenReturn("");
        when(memoryService.loadHistory(any(), any())).thenReturn(List.of());
        when(qaReviewer.review(anyString(), anyString(), anyString()))
                .thenReturn(new QaReviewer.QaResult(true, "合格\n数据与工具结果一致"));
        stubModelScript();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private CsStreamService newService(String qaMode) {
        return newService(qaMode, 2, REVIEW_TIMEOUT_MS);
    }

    private CsStreamService newService(String qaMode, int maxModelRetries, long reviewTimeoutMs) {
        service = new CsStreamService(streamingModel, mallTools, ragService, qaReviewer, memoryService,
                MAX_ROUNDS, 3, maxModelRetries, reviewTimeoutMs, 30_000L, qaMode);
        return service;
    }

    private static CsStreamService.CsStreamRequest ask(String question) {
        return new CsStreamService.CsStreamRequest(question, "conv-1", null, null, null);
    }

    // ------------------------------------------------------------------
    // 模型脚本
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface Turn {
        void apply(StreamingChatResponseHandler handler);
    }

    private void stubModelScript() {
        doAnswer(invocation -> {
            chatCalls++;
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            Turn turn = turns.poll();
            if (turn == null) {
                throw new IllegalStateException("测试脚本没有更多轮次了：模型被多调了一次（chatCalls=" + chatCalls + "）");
            }
            turn.apply(handler);
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    private static Turn text(String... deltas) {
        return handler -> {
            for (String delta : deltas) {
                handler.onPartialResponse(delta);
            }
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(String.join("", deltas)))
                    .build());
        };
    }

    private static Turn toolCall(String name, String argsJson, String narration) {
        return handler -> {
            if (narration != null && !narration.isEmpty()) {
                handler.onPartialResponse(narration);
            }
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                    .id("call_" + name)
                                    .name(name)
                                    .arguments(argsJson)
                                    .build()))
                            .build())
                    .build());
        };
    }

    private static Turn failing(Throwable error) {
        return handler -> handler.onError(error);
    }

    private static Turn failingAfterDelta(String delta, Throwable error) {
        return handler -> {
            handler.onPartialResponse(delta);
            handler.onError(error);
        };
    }

    // ------------------------------------------------------------------
    // 事件记录
    // ------------------------------------------------------------------

    private static final class RecordingListener implements CsStreamService.CsStreamListener {

        final List<String> events = new ArrayList<>();
        long totalMs = -1;
        long firstTokenMs = -1;
        int closes;

        @Override
        public void onStage(String stage, long elapsedMs) {
            events.add("stage:" + stage);
        }

        @Override
        public void onTool(String name, String args, long ms, boolean ok) {
            events.add("tool:" + name + ":" + ok);
        }

        @Override
        public void onDelta(String delta) {
            events.add("delta:" + delta);
        }

        @Override
        public void onReview(Boolean qualified, String reason) {
            events.add("review:" + qualified + ":" + reason);
        }

        @Override
        public void onDone(long totalMs, long firstTokenMs) {
            this.totalMs = totalMs;
            this.firstTokenMs = firstTokenMs;
            events.add("done");
        }

        @Override
        public void onError(String message) {
            events.add("error:" + message);
        }

        @Override
        public void onClose() {
            closes++;
            events.add("close");
        }

        int indexOf(String event) {
            return events.indexOf(event);
        }

        String joined() {
            return String.join(" | ", events);
        }

        long deltas() {
            return events.stream().filter(e -> e.startsWith("delta:")).count();
        }
    }

    // ------------------------------------------------------------------
    // 🔴 时序红线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("audit：done 先发、review 后到；close 最后且只一次（done 不是终止事件）")
    void auditShouldEmitReviewAfterDoneAndCloseLast() {
        turns.add(text("「无印良品化妆水」在售，库存 1000 件。"));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit").stream(ask("化妆水有货吗"), listener);

        assertTrue(result.completed(), "应正常完成：" + listener.joined());
        int done = listener.indexOf("done");
        int review = listener.indexOf("review:true:合格\n数据与工具结果一致");
        assertTrue(done >= 0, "必须有 done：" + listener.joined());
        assertTrue(review > done, "audit 的 review 必须在 done 之后（红线）：" + listener.joined());
        assertEquals(1, listener.closes, "close 必须只发生一次：" + listener.joined());
        assertEquals("close", listener.events.get(listener.events.size() - 1), "close 必须是最后一个事件");
        assertTrue(listener.indexOf("delta:「无印良品化妆水」在售，库存 1000 件。") < done,
                "delta 必须在 done 之前：" + listener.joined());
    }

    @Test
    @DisplayName("audit：review 迟于 done 到达时仍能送达（红线：不能在 done 后就结束）")
    void auditShouldDeliverReviewThatArrivesLate() {
        turns.add(text("好的~"));
        // 质检慢 200ms：远晚于 done，但仍必须送达
        when(qaReviewer.review(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(200);
            return new QaReviewer.QaResult(true, "合格\n价格与工具一致");
        });
        RecordingListener listener = new RecordingListener();

        newService("audit").stream(ask("多少钱"), listener);

        assertTrue(listener.indexOf("review:true:合格\n价格与工具一致") > listener.indexOf("done"),
                "迟到的 review 必须仍被送达：" + listener.joined());
        assertEquals(1, listener.closes);
    }

    @Test
    @DisplayName("audit：质检兜底超时 → review{qualified=null, reason=\"质检超时\"} 仍在 done 之后送达")
    void auditShouldEmitNullQualifiedOnReviewTimeout() {
        turns.add(text("好的~"));
        when(qaReviewer.review(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(500);   // 超过 100ms 的兜底超时
            return new QaReviewer.QaResult(true, "合格");
        });
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit", 2, 100L).stream(ask("多少钱"), listener);

        assertTrue(result.completed());
        assertTrue(listener.events.contains("review:null:" + CsStreamService.REVIEW_TIMEOUT_REASON),
                "超时应发 qualified=null + 「质检超时」：" + listener.joined());
        assertTrue(listener.indexOf("review:null:" + CsStreamService.REVIEW_TIMEOUT_REASON)
                        > listener.indexOf("done"),
                "超时的 review 也必须在 done 之后：" + listener.joined());
        assertEquals(1, listener.closes);
    }

    @Test
    @DisplayName("gate：review 必然先于 done；回答在质检通过后一次性送达（不逐字推送）")
    void gateShouldEmitReviewBeforeDone() {
        turns.add(text("「化妆水」库存 1000 件。"));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("gate").stream(ask("有货吗"), listener);

        assertTrue(result.completed());
        int review = listener.indexOf("review:true:合格\n数据与工具结果一致");
        int done = listener.indexOf("done");
        assertTrue(review >= 0 && done >= 0 && review < done,
                "gate 的 review 必须先于 done：" + listener.joined());
        assertEquals(1, listener.deltas(), "gate 只应送达一次回答文本：" + listener.joined());
        assertEquals("delta:「化妆水」库存 1000 件。", listener.events.get(review - 1),
                "gate 的回答应在 review 之前一次性送达：" + listener.joined());
        assertEquals(1, listener.closes);
    }

    @Test
    @DisplayName("gate：质检不合格 → 打回重答一次，且不再复核（对齐 M2-4）")
    void gateShouldReAnswerOnceWhenUnqualified() {
        turns.add(text("库存 99999 件"));
        turns.add(text("抱歉，库存是 1000 件，我刚才说错了"));
        when(qaReviewer.review(anyString(), anyString(), anyString()))
                .thenReturn(new QaReviewer.QaResult(false, "不合格\n库存数字与工具结果不一致"));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("gate").stream(ask("有货吗"), listener);

        assertEquals(2, chatCalls, "应重答一次（共两次模型调用）");
        verify(qaReviewer, times(1)).review(anyString(), anyString(), anyString());   // 不再复核
        assertEquals("抱歉，库存是 1000 件，我刚才说错了", result.answer());
        assertEquals("delta:抱歉，库存是 1000 件，我刚才说错了",
                listener.events.stream().filter(e -> e.startsWith("delta:")).findFirst().orElse(""),
                "gate 只送达最终版回答（第一版不能泄漏）：" + listener.joined());
        assertFalse(listener.joined().contains("库存 99999 件"), "被否定的第一版不能出现在事件流里");
        assertTrue(listener.events.contains("review:false:不合格\n库存数字与工具结果不一致"));
    }

    // ------------------------------------------------------------------
    // 工具循环
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工具循环：先推 tool 事件，再进第二轮并推送回答")
    void shouldRunToolLoopThenStreamAnswer() {
        when(mallTools.checkStock(10003)).thenReturn("「化妆水」库存 1000 件，当前在售。");
        turns.add(toolCall("checkStock", "{\"goodsId\":10003}", null));
        turns.add(text("有的~", "库存 1000 件，在售中。"));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit").stream(ask("有货吗"), listener);

        assertTrue(listener.events.contains("tool:checkStock:true"), listener.joined());
        assertEquals("有的~库存 1000 件，在售中。", result.answer());
        assertEquals(1, result.toolCalls().size());
        assertTrue(result.toolCalls().get(0).result().contains("库存 1000"),
                "工具轨迹要保留真实结果（质检与可观测面板都要用）");
        // stage 事件是「该阶段完成」时发的（见 CsStreamService 的常量注释），
        // 所以顺序是：检索知识库 → tool → 生成回答
        assertTrue(listener.indexOf("stage:" + CsStreamService.STAGE_RAG)
                        < listener.indexOf("tool:checkStock:true"),
                "检索阶段应在工具调用之前：" + listener.joined());
        assertTrue(listener.indexOf("tool:checkStock:true")
                        < listener.indexOf("stage:" + CsStreamService.STAGE_GENERATE),
                "工具调用发生在生成阶段内（生成阶段事件在整轮生成结束时才发）：" + listener.joined());
    }

    @Test
    @DisplayName("未知工具名：报 tool 事件 ok=false，并把可用工具清单回传给模型（不是静默丢弃）")
    void unknownToolShouldBeReportedAsNotOk() {
        turns.add(toolCall("queryPrice", "{\"goodsId\":10003}", null));
        turns.add(text("抱歉，我查一下别的。"));
        RecordingListener listener = new RecordingListener();

        newService("audit").stream(ask("多少钱"), listener);

        assertTrue(listener.events.contains("tool:queryPrice:false"),
                "未知工具必须以 ok=false 上报：" + listener.joined());
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingModel, times(2)).chat(requests.capture(), any(StreamingChatResponseHandler.class));
        String secondRequest = requests.getAllValues().get(1).messages().toString();
        assertTrue(secondRequest.contains("未知工具") && secondRequest.contains("可用工具"),
                "第二轮请求里应带上「未知工具 + 可用清单」的 tool 结果");
    }

    @Test
    @DisplayName("工具循环上限：模型反复请求工具时，到 MAX 轮停下并给出兜底答复（不死循环）")
    void shouldStopToolLoopAtMaxRounds() {
        when(mallTools.checkStock(anyInt())).thenReturn("库存 1000 件");
        for (int i = 0; i < MAX_ROUNDS + 3; i++) {
            turns.add(toolCall("checkStock", "{\"goodsId\":10003}", null));
        }
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit").stream(ask("有货吗"), listener);

        assertEquals(MAX_ROUNDS + 1, chatCalls, "应为「MAX 轮工具调用 + 1 次收尾请求」：" + chatCalls);
        assertEquals(CsAgentService.TOOL_LIMIT_ANSWER, result.answer(),
                "超限且无文本时用兜底答复（而不是空回答）");
        verify(mallTools, times(MAX_ROUNDS)).checkStock(anyInt());
    }

    // ------------------------------------------------------------------
    // 重试边界（流式特有）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已推送过 delta 之后失败 → 绝不重试（重试会造成重复文本）")
    void shouldNotRetryAfterDeltaDelivered() {
        turns.add(failingAfterDelta("正在为您查询", new RuntimeException("boom")));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit").stream(ask("有货吗"), listener);

        assertEquals(1, chatCalls, "已经推送过内容，不能再重试");
        assertFalse(result.completed());
        assertTrue(listener.joined().contains("error:当前咨询较多，请稍后再试"),
                "失败的文案应是可读话术：" + listener.joined());
        assertEquals(1, listener.closes, "失败也必须收尾（否则客户端干等超时）");
        assertFalse(listener.events.contains("done"), "未完成不应发 done：" + listener.joined());
    }

    @Test
    @DisplayName("首字之前失败 → 可以重试；第二次成功（重试是流式路径唯一的重试机会）")
    void shouldRetryBeforeFirstToken() {
        turns.add(failing(new RuntimeException("Read timed out")));
        turns.add(text("重试后成功"));
        RecordingListener listener = new RecordingListener();

        // maxModelRetries=2 → 恰好一次退避（4s），测试耗时约 4s
        CsStreamService.CsStreamResult result = newService("audit", 2, REVIEW_TIMEOUT_MS)
                .stream(ask("有货吗"), listener);

        assertEquals(2, chatCalls, "首字之前失败应重试一次");
        assertEquals("重试后成功", result.answer());
        assertTrue(listener.events.contains("delta:重试后成功"));
    }

    @Test
    @DisplayName("不可重试异常（400/401/403）→ 立即失败，不做无谓重试")
    void shouldNotRetryNonRetriableException() {
        turns.add(failing(new NonRetriableException("model not available")));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit").stream(ask("有货吗"), listener);

        assertEquals(1, chatCalls, "NonRetriableException 必须立即抛出");
        assertFalse(result.completed());
        assertTrue(listener.joined().contains("error:模型调用失败，请稍后再试"),
                "不可重试失败是服务端配置问题，话术与限流区分开：" + listener.joined());
    }

    // ------------------------------------------------------------------
    // 记忆钩子与上下文
    // ------------------------------------------------------------------

    @Test
    @DisplayName("会话记忆：注入历史 + 回答完成后异步落库（同一 conversationId 两轮能带上文）")
    void shouldInjectHistoryAndPersistTurn() {
        List<ChatMessage> history = List.of(
                UserMessage.from("上一轮问题"),
                AiMessage.from("上一轮回答"));
        when(memoryService.loadHistory("conv-1", null)).thenReturn(history);
        turns.add(text("这一轮回答"));
        RecordingListener listener = new RecordingListener();

        newService("audit").stream(ask("这一轮问题"), listener);

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingModel).chat(requests.capture(), any(StreamingChatResponseHandler.class));
        String sent = requests.getValue().messages().toString();
        assertTrue(sent.contains("上一轮问题") && sent.contains("上一轮回答"),
                "历史必须注入到请求里（否则「第二轮」不可能带上文）");
        assertTrue(sent.contains("这一轮问题"));
        verify(memoryService).appendTurnAsync("conv-1", null, "这一轮问题", "这一轮回答");
    }

    @Test
    @DisplayName("首字之前的空白 delta 不推送（实测模型会先吐换行再请求工具）")
    void shouldNotEmitLeadingWhitespaceAsFirstDelta() {
        when(mallTools.checkStock(10003)).thenReturn("库存 1000 件");
        // 第一轮：先吐两个换行，再请求工具（真机就是这样：delta:"\n\n"、delta:"\n"）
        turns.add(handler -> {
            handler.onPartialResponse("\n\n");
            handler.onPartialResponse("\n");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                    .id("call_1").name("checkStock").arguments("{\"goodsId\":10003}")
                                    .build()))
                            .build())
                    .build());
        });
        turns.add(text("有的~库存 1000 件。"));
        RecordingListener listener = new RecordingListener();

        newService("audit").stream(ask("有货吗"), listener);

        assertFalse(listener.joined().contains("delta:\n"),
                "空白 delta 不应出现在事件流里（用户只会看到几个空事件）：" + listener.joined());
        assertTrue(listener.events.contains("delta:有的~库存 1000 件。"), listener.joined());
    }

    @Test
    @DisplayName("空白 delta 之后的内容不被粘词：真实内容开始后空白照常转发")
    void shouldForwardWhitespaceAfterFirstRealContent() {
        turns.add(text("有的", " ", "~库存 1000 件"));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit").stream(ask("有货吗"), listener);

        assertEquals("有的 ~库存 1000 件", result.answer());
        assertEquals("delta: ", listener.events.get(2), "内容之间的空白必须转发，否则会粘词：" + listener.joined());
    }

    @Test
    @DisplayName("模型没流式吐内容（只给最终文本）→ 补发一次，客户端不会收到空回答")
    void shouldResendAnswerWhenModelDidNotStreamContent() {
        // 真机实测：agnes 经 OmniRoute 时 content 可能为空、只在最后一帧给全文
        turns.add(handler -> handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("有的~库存 1000 件，在售中。"))
                .build()));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit").stream(ask("有货吗"), listener);

        assertTrue(listener.events.contains("delta:有的~库存 1000 件，在售中。"),
                "模型没流式吐字时必须补发，否则客户端一个字都收不到：" + listener.joined());
        assertEquals(1, listener.deltas(), "只能补发一次（不能重复）");
        assertTrue(listener.firstTokenMs >= 0, "补发的文本也算首字，不能报 -1");
        assertEquals("有的~库存 1000 件，在售中。", result.answer());
    }

    @Test
    @DisplayName("模型完全没给文本 → 兜底话术也要送达客户端（不能只写进 result）")
    void shouldDeliverFallbackAnswer() {
        turns.add(handler -> handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from(""))
                .build()));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit").stream(ask("有货吗"), listener);

        assertEquals(CsAgentService.EMPTY_ANSWER, result.answer());
        assertTrue(listener.events.contains("delta:" + CsAgentService.EMPTY_ANSWER),
                "兜底话术必须让用户看得到：" + listener.joined());
    }

    @Test
    @DisplayName("RAG 失败只降级（不中断问答），且 stage 事件仍上报")
    void ragFailureShouldDegradeGracefully() {
        when(ragService.asPrompt(anyString(), anyInt())).thenThrow(new RuntimeException("redis down"));
        turns.add(text("没有知识库也能答"));
        RecordingListener listener = new RecordingListener();

        CsStreamService.CsStreamResult result = newService("audit").stream(ask("有货吗"), listener);

        assertTrue(result.completed(), "RAG 故障不应让问答失败");
        assertTrue(listener.events.contains("stage:" + CsStreamService.STAGE_RAG),
                "降级也必须照发 stage 事件（不静默）：" + listener.joined());
    }

    @Test
    @DisplayName("上下文带入：goodsId / orderNo 会进模型请求（DESIGN §4.3 的后端半边）")
    void shouldInjectGoodsAndOrderContext() {
        turns.add(text("好的"));
        RecordingListener listener = new RecordingListener();
        CsStreamService.CsStreamRequest request =
                new CsStreamService.CsStreamRequest("这个多少钱", "conv-9", null, 10003, "202409180001");

        newService("audit").stream(request, listener);

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingModel).chat(requests.capture(), any(StreamingChatResponseHandler.class));
        String sent = requests.getValue().messages().toString();
        assertTrue(sent.contains("goodsId=10003"), "当前商品要带入：" + sent);
        assertTrue(sent.contains("202409180001"), "当前订单号要带入：" + sent);
    }

    @Test
    @DisplayName("firstTokenMs 记录「模型产出首字」的时刻；done.totalMs 不早于它")
    void shouldReportFirstTokenTiming() {
        turns.add(text("第一段", "第二段"));
        RecordingListener listener = new RecordingListener();

        newService("audit").stream(ask("有货吗"), listener);

        assertTrue(listener.firstTokenMs >= 0, "有 delta 时必须给出首字耗时");
        assertTrue(listener.totalMs >= listener.firstTokenMs,
                "totalMs(" + listener.totalMs + ") 不应小于 firstTokenMs(" + listener.firstTokenMs + ")");
    }

    @Test
    @DisplayName("无 conversationId 且未登录：仍能正常回答（记忆维度缺失只降级，不问断服务）")
    void shouldAnswerWithoutConversationId() {
        turns.add(text("可以的"));
        RecordingListener listener = new RecordingListener();
        CsStreamService.CsStreamRequest request =
                new CsStreamService.CsStreamRequest("你好", null, null, null, null);

        CsStreamService.CsStreamResult result = newService("audit").stream(request, listener);

        assertTrue(result.completed());
        assertEquals("可以的", result.answer());
        assertTrue(listener.events.contains("review:true:合格\n数据与工具结果一致"), listener.joined());
        verify(memoryService).loadHistory(null, null);
        verify(memoryService).appendTurnAsync(null, null, "你好", "可以的");
    }

    @Test
    @DisplayName("buildUserText：问题 + 可选上下文 + RAG 拼接（无多余空行）")
    void buildUserTextShouldJoinContext() {
        CsStreamService.CsStreamRequest bare =
                new CsStreamService.CsStreamRequest("Q", "c", null, null, null);
        assertEquals("Q", CsStreamService.buildUserText(bare, ""));
        assertEquals("Q\n\nRAG", CsStreamService.buildUserText(bare, "RAG"));

        CsStreamService.CsStreamRequest withGoods =
                new CsStreamService.CsStreamRequest("Q", "c", null, 7, null);
        String text = CsStreamService.buildUserText(withGoods, "RAG");
        assertTrue(text.startsWith("Q"));
        assertTrue(text.contains("goodsId=7"));
        assertTrue(text.endsWith("RAG"));
        assertFalse(text.contains("\n\n\n"), "不应出现连续空行");
    }

    @Test
    @DisplayName("失败话术：不可重试 → 「模型调用失败」；其余（限流等）→ 「当前咨询较多」")
    void friendlyMessagesShouldBeReadable() {
        assertEquals("模型调用失败，请稍后再试",
                CsStreamService.friendlyMessage(new NonRetriableException("400")));
        assertEquals("当前咨询较多，请稍后再试",
                CsStreamService.friendlyMessage(new RuntimeException("429 rate limit reset after 3s")));
        assertFalse(CsStreamService.friendlyMessage(new RuntimeException("429 reset after 3s")).contains("429"),
                "不能把上游报文甩给用户");
    }

    @Test
    @DisplayName("qaModeName 暴露当前模式（供健康检查）")
    void qaModeNameShouldReflectConfig() {
        assertEquals("audit", newService("audit").qaModeName());
        assertEquals("gate", newService("gate").qaModeName());
        assertEquals("audit", newService("bogus-value").qaModeName(), "无法识别的模式回退 audit");
    }

    @Test
    @DisplayName("工具轨迹摘要进质检 prompt（数字来源可核对）")
    void toolSummaryShouldBePassedToQa() {
        when(mallTools.checkStock(10003)).thenReturn("库存 1000 件");
        turns.add(toolCall("checkStock", "{\"goodsId\":10003}", null));
        turns.add(text("库存 1000 件"));
        RecordingListener listener = new RecordingListener();

        newService("audit").stream(ask("有货吗"), listener);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(qaReviewer).review(anyString(), anyString(), summary.capture());
        assertTrue(summary.getValue().contains("checkStock"), "质检要看到工具轨迹：" + summary.getValue());
    }
}
