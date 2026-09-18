package ltd.newbee.mall.controller.mall;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import ltd.newbee.mall.service.agent.RagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * M2-5 端到端验证：在**真实 HTTP** 上跑 {@code POST /api/cs/chat} 的 SSE 流。
 *
 * <p>为什么单有必要（其余测试各自只覆盖一半）：
 * <ul>
 *   <li>{@code CsStreamServiceTest} 只到「编排层发出的事件顺序」，看不见 HTTP</li>
 *   <li>{@code CsSseWriterTest} 只到「适配器对 emitter 的调用」，也没有真的 socket</li>
 *   <li>本测试把两个半拉子接起来，验证三件只有真 HTTP 才暴露的事：
 *       <b>① 响应确实是 {@code text/event-stream} 且请求会被受理</b>（映射/拦截器/内容协商）；
 *       <b>② delta 是<b>增量</b>到达的</b>（不是最后一次性喷出）——
 *       这正是 DoD「回答为流式逐字输出（首字 ≠ 整段）」；
 *       <b>③ {@code done} 之后 {@code review} 仍能送达且连接会正常收尾</b>（不挂死）</li>
 * </ul>
 *
 * <p>模型、质检模型与 RAG 全部用 mock：本测试验证**传输与协议**，不依赖任何外部服务，
 * 所以放在 {@code *Test} 里随 {@code mvn test} 一起跑。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "cs.rag.enabled=false",   // 不必加载 90MB 嵌入模型
                "cs.qa.mode=audit"        // 显式钉住默认模式（红线所在）
        })
class CsStreamEndpointTest {

    /** 每段 delta 之间人为停顿，用来判定「增量到达」而不是「最后一坨」 */
    private static final long DELTA_GAP_MS = 400L;

    @LocalServerPort
    private int port;

    @MockitoBean
    private StreamingChatModel streamingChatModel;

    /** 质检用的是非流式 Bean（@Qualifier("csChatModel")） */
    @MockitoBean
    private ChatModel csChatModel;

    @MockitoBean
    private RagService ragService;

    // ------------------------------------------------------------------
    // 测试
    // ------------------------------------------------------------------

    @Test
    @DisplayName("端到端：delta 增量到达（首字 ≠ 整段）；done 之后再 review；连接正常收尾")
    void shouldStreamIncrementallyAndDeliverReviewAfterDone() throws Exception {
        stubStreamingDeltas(List.of("「无印良品", "高保湿化妆水」", "在售，库存 1000 件。"), DELTA_GAP_MS);
        when(csChatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("合格\n价格与工具结果一致")).build());
        when(ragService.asPrompt(anyString(), anyInt())).thenReturn("");

        SseResponse response = postSse("{\"question\":\"化妆水有货吗\",\"conversationId\":\"it-sse-1\"}");

        assertEquals(200, response.status());
        assertTrue(response.contentType().startsWith("text/event-stream"),
                "必须是 SSE 内容类型，实际：" + response.contentType());

        assertTrue(response.contains("event:delta"), "应有 delta 事件：\n" + response.dump());
        assertTrue(response.contains("event:done"), "应有 done 事件：\n" + response.dump());
        assertTrue(response.contains("event:review"), "应有 review 事件：\n" + response.dump());

        // ① 增量到达：3 段 delta，每段间隔 400ms → 首段与末事件至少差 300ms。
        //    若服务端把整段回答攒完再发（假流式），这些行会在几毫秒内一起到达。
        long firstDeltaAt = response.firstTimestampOf("event:delta");
        long lastAt = response.lastTimestamp();
        assertTrue(lastAt - firstDeltaAt >= 300,
                "delta 必须是增量到达（首字 ≠ 整段）：首段 " + firstDeltaAt + "，末事件 " + lastAt);
        assertEquals(3, response.countOf("event:delta"), "应逐段推送 3 次 delta：\n" + response.dump());

        // ② 🔴 时序红线：audit 模式下 review 在 done 之后 —— 且确实送达了（服务端没提前 complete）
        int doneAt = response.firstIndexOf("event:done");
        int reviewAt = response.firstIndexOf("event:review");
        assertTrue(doneAt >= 0 && reviewAt >= 0 && doneAt < reviewAt,
                "audit 的 review 必须在 done 之后且仍能送达：\n" + response.dump());

        // ③ done 里带上首字耗时（-1 表示从未收到 token）
        String doneData = response.dataLineAfter("event:done");
        assertTrue(doneData.contains("\"firstTokenMs\""), doneData);
        assertFalse(doneData.contains("\"firstTokenMs\":-1"), "确实有 delta，首字耗时不应是 -1：" + doneData);
    }

    @Test
    @DisplayName("端到端：问题为空 → 走 error 事件（SSE 客户端拿不到非 2xx 的响应体，走事件更可靠）")
    void blankQuestionShouldReturnErrorEvent() throws Exception {
        SseResponse response = postSse("{\"question\":\"   \",\"conversationId\":\"it-sse-2\"}");

        assertEquals(200, response.status());
        assertTrue(response.contains("event:error"), "应发 error 事件：\n" + response.dump());
        assertTrue(response.dataAfter("event:error").contains("问题不能为空"), response.dump());
        assertFalse(response.contains("event:done"), "没答就不该发 done：\n" + response.dump());
        // 空问题不应触发任何模型调用
        org.mockito.Mockito.verifyNoInteractions(streamingChatModel);
    }

    @Test
    @DisplayName("端到端：工具事件与 stage 事件都出现在流里（客户端可据此渲染工具轨迹与耗时）")
    void shouldEmitStageAndToolEvents() throws Exception {
        // 第一轮：模型请求工具；第二轮：给出回答
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .toolExecutionRequests(List.of(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                    .id("call_1")
                                    .name("checkStock")
                                    .arguments("{\"goodsId\":10003}")
                                    .build()))
                            .build())
                    .build());
            return null;
        }).doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("有的~库存 1000 件。");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("有的~库存 1000 件。"))
                    .build());
            return null;
        }).when(streamingChatModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(csChatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("合格")).build());
        when(ragService.asPrompt(anyString(), anyInt())).thenReturn("");

        SseResponse response = postSse("{\"question\":\"10003 有货吗\",\"conversationId\":\"it-sse-3\"}");

        assertTrue(response.contains("event:stage"), "应有 stage 事件：\n" + response.dump());
        assertTrue(response.contains("event:tool"), "应有 tool 事件：\n" + response.dump());
        String tool = response.dataAfter("event:tool");
        assertTrue(tool.contains("\"name\":\"checkStock\""), tool);
        assertTrue(tool.contains("\"ok\":true"), tool);
        assertTrue(tool.contains("\"args\":{\"goodsId\":10003}"), "args 应是 JSON 对象：" + tool);
        assertTrue(tool.contains("\"ms\""), tool);

        // stage 的 elapsed 单位是秒（DESIGN §4.2 示例为 0.31）
        String stage = response.dataAfter("event:stage");
        assertTrue(stage.contains("\"stage\""), stage);
        assertTrue(stage.contains("\"elapsed\""), stage);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void stubStreamingDeltas(List<String> deltas, long gapMs) {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            for (String delta : deltas) {
                handler.onPartialResponse(delta);
                if (gapMs > 0) {
                    Thread.sleep(gapMs);
                }
            }
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(String.join("", deltas)))
                    .build());
            return null;
        }).when(streamingChatModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    private SseResponse postSse(String jsonBody) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/cs/chat"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<Stream<String>> response =
                client.send(request, HttpResponse.BodyHandlers.ofLines());

        List<TimedLine> collected = new ArrayList<>();
        try (Stream<String> body = response.body()) {
            // ofLines() 是增量消费：每读到一行就回调，这里顺手打上到达时间
            body.forEach(line -> collected.add(new TimedLine(line, System.currentTimeMillis())));
        }
        return new SseResponse(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""), collected);
    }

    private record TimedLine(String text, long arrivedAt) {
    }

    /** 一屏 SSE 响应：行 + 每行到达时刻（用来判定是不是真流式） */
    private record SseResponse(int status, String contentType, List<TimedLine> collected) {

        boolean contains(String fragment) {
            return collected.stream().anyMatch(l -> l.text().contains(fragment));
        }

        List<String> lines() {
            return collected.stream().map(TimedLine::text).toList();
        }

        /** 断言失败时打印整条流（比只报一个 fragment 好排查得多） */
        String dump() {
            return String.join("\n", lines());
        }

        int countOf(String fragment) {
            return (int) collected.stream().filter(l -> l.text().contains(fragment)).count();
        }

        int firstIndexOf(String fragment) {
            for (int i = 0; i < collected.size(); i++) {
                if (collected.get(i).text().contains(fragment)) {
                    return i;
                }
            }
            return -1;
        }

        long firstTimestampOf(String fragment) {
            return collected.stream().filter(l -> l.text().contains(fragment))
                    .mapToLong(TimedLine::arrivedAt).findFirst().orElse(-1);
        }

        long lastTimestamp() {
            return collected.isEmpty() ? -1 : collected.get(collected.size() - 1).arrivedAt();
        }

        /** 取某事件之后的第一个 data 行（SSE 里 data 紧跟 event 行） */
        String dataAfter(String eventFragment) {
            int index = firstIndexOf(eventFragment);
            if (index < 0) {
                return "";
            }
            for (int i = index + 1; i < collected.size(); i++) {
                if (collected.get(i).text().startsWith("data:")) {
                    return collected.get(i).text();
                }
            }
            return "";
        }

        String dataLineAfter(String eventFragment) {
            return dataAfter(eventFragment);
        }
    }
}
