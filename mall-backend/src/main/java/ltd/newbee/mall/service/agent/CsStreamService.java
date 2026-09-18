package ltd.newbee.mall.service.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客服**流式**编排（M2-5）—— 在 M2-4 的「RAG + 工具循环 + 质检」之上，把回答改成
 * **逐字推送**，并把对话写进会话记忆（{@link CsChatMemoryService}）。
 *
 * <p>与 {@link CsAgentService}（非流式）的分工：两者共用同一份人设
 * （{@link CsAgentService#CS_PROMPT}）、同一份工具分发表（{@link MallToolInvoker}）
 * 与同一个质检员（{@link QaReviewer}）；差别只在「模型调用方式」与「事件出口」。
 *
 * <h3>🔴 SSE 时序（DESIGN §4.2，本模块最容易踩的地方）</h3>
 * <ul>
 *   <li><b>{@code audit}（默认）</b>：先 {@code done}、后 {@code review} ——
 *       所以 <b>{@code done} 不是终止事件</b>，实现方（{@code CsSseWriter}）
 *       <b>绝不能</b>在发完 {@code done} 后立刻 {@code complete()}，
 *       否则异步 {@code review} 到达时 {@code send()} 会抛 {@code IllegalStateException}。
 *       本类只在最后调一次 {@link CsStreamListener#onClose()} 来表达「真正结束」。</li>
 *   <li><b>{@code gate}</b>：质检同步完成，{@code review} 必然先于 {@code done}。</li>
 *   <li><b>终止</b>：收到 {@code review} 即结束；质检兜底超时
 *       {@code cs.stream.review-timeout-ms}（默认 3000ms）后发
 *       {@code review\{qualified:null, reason:"质检超时"\}} 再结束。</li>
 * </ul>
 *
 * <h3>gate 模式为什么不逐字推送</h3>
 * gate 的语义是「质检通过前不给用户看」，而流式一旦推出去就<b>收不回来</b>
 * （不合格要重答时，用户已经看过第一版）。所以 gate 用
 * {@link DiscardingDeltaSink} 丢弃逐字 delta，等质检结论出来后再<b>一次性</b>送达最终回答。
 * 这与 DESIGN §7.3「gate 会显著拉长响应」的取舍一致；audit（默认）才是真流式。
 *
 * <h3>为什么不重试「已推过内容的失败」</h3>
 * 流式模型<b>没有</b>内置重试（{@code OpenAiStreamingChatModel} 的 Builder 上不存在
 * {@code maxRetries}，也没有任何重试逻辑），所以重试只能自己做。但一旦有 delta 到了
 * 用户眼前，重试就会造成<b>重复/错乱</b>的文本 —— 因此只对「首字之前」的失败重试；
 * 已推送过内容的失败直接上报 {@code error}。
 */
@Service
public class CsStreamService {

    private static final Logger log = LoggerFactory.getLogger(CsStreamService.class);

    /** stage 标签：语义是「该阶段已完成，elapsed 是它自身的耗时」（对齐 DESIGN §4.2 的示例 0.31s） */
    static final String STAGE_RAG = "检索知识库";
    static final String STAGE_GENERATE = "生成回答";

    /** 质检兜底超时时的 review 理由（DESIGN §4.2 指定文案） */
    static final String REVIEW_TIMEOUT_REASON = "质检超时";

    /** 退避基数：与 CsAgentService 同口径（必须 > 上游 429 自述的 3s 重置窗口） */
    private static final long BACKOFF_BASE_MS = 4000L;

    /**
     * SSE 事件出口。
     *
     * <p>编排层只认这个接口，不认识 {@code SseEmitter} —— 这样时序（谁先谁后、什么时候算结束）
     * 可以脱离 HTTP 层被单独测试，HTTP 适配只在 {@code CsSseWriter} 里。
     */
    public interface CsStreamListener {

        /** 某阶段完成（{@code elapsedMs} 是该阶段自身耗时） */
        void onStage(String stage, long elapsedMs);

        /** 一次工具调用（{@code ok=false} 表示参数解析/执行失败或工具名未知） */
        void onTool(String name, String args, long ms, boolean ok);

        /** 回答的一段文本（逐字/逐块） */
        void onDelta(String text);

        /**
         * 质检结论。{@code qualified} 为 {@code null} 表示<b>没能得出</b>结论（质检超时）——
         * 客户端据此展示「质检超时」而不是「不合格」。
         */
        void onReview(Boolean qualified, String reason);

        /** 回答生成完成（audit 下此事件之后仍会有 {@code review}） */
        void onDone(long totalMs, long firstTokenMs);

        /** 失败：{@code message} 是**给用户看的**话术，真实原因在服务端日志里 */
        void onError(String message);

        /** 流真正结束（实现方在此 {@code complete()}，且必须只执行一次） */
        void onClose();
    }

    /**
     * 一次流式问答的入参。
     *
     * @param question       用户问题
     * @param conversationId 匿名会话标识（前端 localStorage 生成、随请求携带）
     * @param userId         登录用户 id；<b>必须由服务端从 session 取</b>，不信任前端传值
     * @param goodsId        可选的「当前正在看的商品」（DESIGN §4.3，入口由 M3 的前端提供）
     * @param orderNo        可选的「当前正在看的订单」（同上）
     */
    public record CsStreamRequest(String question, String conversationId, Long userId,
                                  Integer goodsId, String orderNo) {
    }

    /** 一次流式问答的结果（供调用方/测试断言；SSE 客户端本身只消费事件） */
    public record CsStreamResult(String answer,
                                 List<CsAgentService.ToolCall> toolCalls,
                                 QaReviewer.QaMode qaMode,
                                 QaReviewer.QaResult review,
                                 boolean completed) {
    }

    private final StreamingChatModel streamingChatModel;
    private final MallToolInvoker toolInvoker;
    private final RagService ragService;
    private final QaReviewer qaReviewer;
    private final CsChatMemoryService memoryService;

    /** 工具规格从类上取（而非实例）：即使 MallTools 将来被代理包装，规格也不会丢 */
    private final List<ToolSpecification> toolSpecifications;

    private final int maxToolRounds;
    private final int ragTopK;
    private final int maxModelRetries;
    private final long reviewTimeoutMs;
    private final long roundTimeoutMs;
    private final QaReviewer.QaMode qaMode;

    /** 质检等待用的执行器（要带超时等待，必须有独立线程去跑那次阻塞调用） */
    private final ExecutorService reviewExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CsStreamService(@Qualifier("csStreamingChatModel") StreamingChatModel streamingChatModel,
                           MallTools mallTools,
                           RagService ragService,
                           QaReviewer qaReviewer,
                           CsChatMemoryService memoryService,
                           @Value("${cs.agent.max-tool-rounds:5}") int maxToolRounds,
                           @Value("${cs.agent.top-k:3}") int ragTopK,
                           @Value("${cs.agent.max-model-retries:2}") int maxModelRetries,
                           @Value("${cs.stream.review-timeout-ms:3000}") long reviewTimeoutMs,
                           @Value("${cs.stream.round-timeout-ms:60000}") long roundTimeoutMs,
                           @Value("${cs.qa.mode:audit}") String qaMode) {
        this.streamingChatModel = streamingChatModel;
        this.toolInvoker = new MallToolInvoker(mallTools);
        this.ragService = ragService;
        this.qaReviewer = qaReviewer;
        this.memoryService = memoryService;
        this.maxToolRounds = Math.max(1, maxToolRounds);
        this.ragTopK = Math.max(1, ragTopK);
        this.maxModelRetries = Math.max(1, maxModelRetries);
        this.reviewTimeoutMs = Math.max(1, reviewTimeoutMs);
        this.roundTimeoutMs = Math.max(1, roundTimeoutMs);
        this.qaMode = QaReviewer.QaMode.from(qaMode);
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(MallTools.class);
        log.info("流式客服编排就绪：qaMode={}，maxToolRounds={}，ragTopK={}，质检兜底超时={}ms，单轮上限={}ms",
                this.qaMode, this.maxToolRounds, this.ragTopK, this.reviewTimeoutMs, this.roundTimeoutMs);
    }

    @PreDestroy
    public void shutdown() {
        reviewExecutor.shutdown();
    }

    /** 当前质检模式（小写名，如 {@code audit} / {@code gate}），供健康检查展示 */
    public String qaModeName() {
        return qaMode.name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    /**
     * 端到端跑一次流式问答，全程把事件推给 {@code listener}。
     *
     * <p>本方法<b>阻塞</b>直到整条流真正结束（含 review），调用方应在独立线程里执行
     * （见 {@code CsController}：Servlet 线程只负责创建 emitter 并立即返回）。
     */
    public CsStreamResult stream(CsStreamRequest request, CsStreamListener listener) {
        long startMs = System.currentTimeMillis();
        FirstToken firstToken = new FirstToken();
        List<CsAgentService.ToolCall> toolCalls = new ArrayList<>();
        String conversationId = request.conversationId();
        Long userId = request.userId();

        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(CsAgentService.CS_PROMPT));
            messages.addAll(memoryService.loadHistory(conversationId, userId));

            long ragStart = System.currentTimeMillis();
            String ragContext = safeRagPrompt(request.question());
            listener.onStage(STAGE_RAG, System.currentTimeMillis() - ragStart);

            messages.add(UserMessage.from(buildUserText(request, ragContext)));

            // audit 真流式；gate 丢弃 delta，等质检后再一次性送达（见类注释）
            DeltaSink sink = (qaMode == QaReviewer.QaMode.GATE)
                    ? new DiscardingDeltaSink()
                    : new LiveDeltaSink(listener);

            long generateStart = System.currentTimeMillis();
            Generation generation = generate(messages, sink, firstToken, toolCalls, listener, startMs);
            listener.onStage(STAGE_GENERATE, System.currentTimeMillis() - generateStart);

            QaReviewer.QaResult review;
            if (qaMode == QaReviewer.QaMode.GATE) {
                review = reviewSafely(request.question(), generation, toolCalls);
                if (!review.qualified()) {
                    log.info("质检不合格（gate 模式），打回重答一次");
                    generation = regenerate(generation, review, sink, firstToken, listener, startMs);
                }
                // gate：质检通过后才送达回答；review 必须先于 done（DESIGN §4.2）
                if (!generation.answer().isBlank()) {
                    listener.onDelta(generation.answer());
                }
                listener.onReview(review.qualified(), review.review());
                listener.onDone(System.currentTimeMillis() - startMs, firstToken.elapsedMs());
            } else {
                // audit：done 先发，review 后到 —— done 不是终止事件（红线）
                listener.onDone(System.currentTimeMillis() - startMs, firstToken.elapsedMs());
                review = awaitReview(request.question(), generation, toolCalls);
                listener.onReview(review == null ? null : review.qualified(),
                        review == null ? REVIEW_TIMEOUT_REASON : review.review());
            }

            // 每轮对话结束后异步落库，不阻塞流式响应（DESIGN §7.4）
            memoryService.appendTurnAsync(conversationId, userId, request.question(), generation.answer());

            listener.onClose();
            return new CsStreamResult(generation.answer(), List.copyOf(toolCalls), qaMode, review, true);
        } catch (Throwable t) {
            log.warn("流式问答失败：question={}，原因={}", abbreviate(request.question()), oneLine(t));
            String message = friendlyMessage(t);
            // 事件出口本身也可能已经断了（客户端断开）——上报失败不该再抛出
            quietly(() -> listener.onError(message));
            quietly(listener::onClose);
            return new CsStreamResult("", List.copyOf(toolCalls), qaMode, null, false);
        }
    }

    // ------------------------------------------------------------------
    // 生成：流式工具循环
    // ------------------------------------------------------------------

    private Generation generate(List<ChatMessage> messages, DeltaSink sink, FirstToken firstToken,
                                List<CsAgentService.ToolCall> toolCalls, CsStreamListener listener,
                                long startMs) {
        AiMessage ai = streamRoundWithRetry(messages, sink, firstToken, startMs);
        // ⚠️ 与 M2-4 同理：带 tool_calls 的 assistant 消息必须进历史，
        // OpenAI 协议要求 tool 消息紧跟其 assistant(tool_calls)，否则下一轮 400。
        messages.add(ai);

        int rounds = 0;
        boolean truncated = false;
        // 「最后一轮流式出来的文本从哪个下标开始」——用来判定回答是否真的推给了客户端
        int markBeforeRound = 0;
        while (ai.hasToolExecutionRequests()) {
            if (rounds >= maxToolRounds) {
                truncated = true;
                log.warn("工具循环达到上限（{} 轮）已强制停止，避免无限循环。已执行的工具调用：{} 次",
                        maxToolRounds, toolCalls.size());
                // 为待办但未执行的工具调用补占位结果，保持对话格式合法
                for (ToolExecutionRequest pending : ai.toolExecutionRequests()) {
                    messages.add(ToolExecutionResultMessage.from(pending, CsAgentService.TOOL_LIMIT_SKIP_NOTE));
                }
                break;
            }
            rounds++;
            for (ToolExecutionRequest toolRequest : ai.toolExecutionRequests()) {
                long toolStart = System.currentTimeMillis();
                MallToolInvoker.ToolInvocation invocation =
                        toolInvoker.invoke(toolRequest.name(), toolRequest.arguments());
                long costMs = System.currentTimeMillis() - toolStart;
                listener.onTool(toolRequest.name(), toolRequest.arguments(), costMs, invocation.ok());
                toolCalls.add(new CsAgentService.ToolCall(toolRequest.name(), toolRequest.arguments(),
                        invocation.result(), costMs));
                messages.add(ToolExecutionResultMessage.from(toolRequest, invocation.result()));
            }
            markBeforeRound = sink.streamedLength();
            ai = streamRoundWithRetry(messages, sink, firstToken, startMs);
            messages.add(ai);
        }

        String answer = ai.text();
        if (isBlank(answer)) {
            if (!truncated) {
                log.warn("模型返回了空文本（且无工具调用请求）");
            }
            answer = truncated ? CsAgentService.TOOL_LIMIT_ANSWER : CsAgentService.EMPTY_ANSWER;
        }

        // ⭐ 补发：模型没把回答流式吐出来时，客户端会一个字都收不到。
        // 真机实测（agnes 经 OmniRoute）确实碰到两种情形：
        //   ① 回答是兜底话术（模型本来就什么都没给）
        //   ② 网关把可见回答放到最后一次性返回（content 为空、只给 reasoning_content，
        //      实测该通道确实如此）
        // 判据用「最后一轮流出来的文本是否为空」而不是「整条流发过东西没」——
        // 因为工具轮可能已经推过旁白，不能拿它冒充回答。
        if (isBlank(sink.streamedTextSince(markBeforeRound)) && !isBlank(answer)) {
            log.info("模型未流式返回回答文本（最终回答 {} 字符），补发一次以免客户端收到空回答",
                    answer.length());
            firstToken.mark(startMs);   // 用户此刻才看到首字，不假装首字很早
            sink.accept(answer);
        }
        return new Generation(answer, messages);
    }

    /**
     * 流式调一轮模型，带重试。
     *
     * <p><b>重试边界</b>：只在「本轮还没有任何 delta 送达客户端」时重试。
     * 一旦送出去了（audit 模式下的 live sink），重试会造成重复文本 —— 直接抛出。
     * gate 模式用丢弃式 sink，{@code delivered()} 恒为 0，所以永远允许重试（用户什么都还没看到）。
     */
    private AiMessage streamRoundWithRetry(List<ChatMessage> messages, DeltaSink sink,
                                           FirstToken firstToken, long startMs) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxModelRetries; attempt++) {
            int deliveredBefore = sink.delivered();
            try {
                return streamOnce(messages, sink, firstToken, startMs);
            } catch (NonRetriableException e) {
                // 400 InvalidRequest / 401、403 Authentication / 模型不可用：重试纯属浪费
                throw e;
            } catch (RuntimeException e) {
                if (sink.delivered() > deliveredBefore) {
                    log.warn("模型调用失败，但本轮已向客户端推送过内容 → 不重试（重试会造成重复/错乱）：{}",
                            oneLine(e));
                    throw e;
                }
                last = e;
                log.warn("模型调用失败（第 {}/{} 次，可重试）：{}", attempt, maxModelRetries, oneLine(e));
                if (attempt < maxModelRetries) {
                    // 退避必须严格大于上游 429 自述的 3s 重置窗口（与 CsAgentService 同口径）
                    sleepQuietly(BACKOFF_BASE_MS * attempt);
                }
            }
        }
        throw last;
    }

    /**
     * 真正发起一次流式调用，并在返回时给出这一轮完整的 {@link AiMessage}
     * （文本 + 可能的工具调用请求）。
     *
     * <p>用 {@link CompletableFuture} 把 LangChain4j 的回调式 API 收敛成一个阻塞调用，
     * 这样工具循环可以写成直白的顺序代码。等待有上限
     * （{@code cs.stream.round-timeout-ms}）：模型若不吐数据也不报错，不能让线程永久挂住。
     */
    private AiMessage streamOnce(List<ChatMessage> messages, DeltaSink sink, FirstToken firstToken, long startMs) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        streamingChatModel.chat(
                ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(toolSpecifications)
                        .build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (partialResponse == null || partialResponse.isEmpty()) {
                            return;
                        }
                        // 空白不当作首字：实测模型在「想一下再调工具」时会先吐几个换行
                        //（真机见到 delta:"\n\n" 与 delta:"\n"），直接推给用户就是几个空事件，
                        // 也会把 firstTokenMs 记成无意义的值。首个非空内容出现后，
                        // 空白照常转发（否则会粘词）。
                        if (firstToken.notYetSeen() && partialResponse.isBlank()) {
                            log.debug("跳过首个非空内容之前的空白 delta（{} 字符）", partialResponse.length());
                            return;
                        }
                        // firstToken 记的是「模型产出首字」的时刻，与是否真的送达客户端无关
                        // （gate 模式会先丢弃，客户端看到得更晚）
                        firstToken.mark(startMs);
                        sink.accept(partialResponse);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        future.complete(completeResponse);
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                });

        ChatResponse response;
        try {
            response = future.get(roundTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待模型流式响应时被中断", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("模型流式调用超过单轮上限 " + roundTimeoutMs + "ms 仍未结束", e);
        } catch (ExecutionException e) {
            throw asRuntimeException(e.getCause());
        }

        if (response == null || response.aiMessage() == null) {
            log.warn("模型返回空响应（response 或 aiMessage 为 null），本轮按「无工具、无文本」处理");
            return AiMessage.from("");
        }
        return response.aiMessage();
    }

    // ------------------------------------------------------------------
    // 质检
    // ------------------------------------------------------------------

    /**
     * audit：等异步质检结论，最多 {@code cs.stream.review-timeout-ms}。
     *
     * @return 质检结论；超时返回 {@code null}（调用方发 {@code review\{qualified:null\}}）
     */
    private QaReviewer.QaResult awaitReview(String question, Generation generation,
                                            List<CsAgentService.ToolCall> toolCalls) {
        String toolSummary = CsAgentService.toolSummary(toolCalls);
        CompletableFuture<QaReviewer.QaResult> future = CompletableFuture.supplyAsync(
                () -> qaReviewer.review(question, generation.answer(), toolSummary), reviewExecutor);
        try {
            return future.get(reviewTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("质检未在 {}ms 内返回，按「未得出结论」收尾（qualified=null）", reviewTimeoutMs);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待质检结论时被中断");
            return null;
        } catch (Exception e) {
            // 质检故障不吞回答：放行 + WARN（与 M2-4 的 audit 口径一致）
            log.warn("异步质检失败（audit 模式，不影响回答）：{}", oneLine(e));
            return new QaReviewer.QaResult(true, "（质检失败，已放行：" + oneLine(e) + "）");
        }
    }

    /** gate：同步质检；质检自身故障时放行并留痕（与 M2-4 一致） */
    private QaReviewer.QaResult reviewSafely(String question, Generation generation,
                                             List<CsAgentService.ToolCall> toolCalls) {
        try {
            return qaReviewer.review(question, generation.answer(), CsAgentService.toolSummary(toolCalls));
        } catch (Exception e) {
            log.warn("gate 模式质检调用失败，放行本次回答（不打回）：{}", oneLine(e));
            return new QaReviewer.QaResult(true, "（质检失败，已放行：" + oneLine(e) + "）");
        }
    }

    /** gate 打回：把质检意见追加进对话后重写一次（只一次，不循环、不再复核 —— 对齐 M2-4） */
    private Generation regenerate(Generation previous, QaReviewer.QaResult review, DeltaSink sink,
                                  FirstToken firstToken, CsStreamListener listener, long startMs) {
        List<ChatMessage> messages = new ArrayList<>(previous.messages());
        messages.add(UserMessage.from("质检员反馈：" + review.review()
                + "\n请根据反馈修正你的回答。注意：直接用自然、有温度的口吻重新回答，"
                + "不要以「您说得对」之类的道歉/检讨式句子开头。"));
        // 丢弃式 sink（gate）：这里不会有人看到任何 delta，重试/重答都安全
        AiMessage ai = streamRoundWithRetry(messages, sink, firstToken, startMs);
        String answer = ai.text();
        if (isBlank(answer)) {
            log.warn("打回重答后模型仍未返回文本，保留上一版回答");
            answer = previous.answer();
        }
        return new Generation(answer, messages);
    }

    // ------------------------------------------------------------------
    // 上下文与降级
    // ------------------------------------------------------------------

    /**
     * 组装喂给模型的用户消息：问题本身 + 可选的「当前咨询对象」+ RAG 片段。
     *
     * <p>带上 goodsId / orderNo 是为了让「这个多少钱」这类指代能落到具体商品
     * （DESIGN §4.3）；但<b>价格/库存仍然只能来自工具结果</b>（数据铁律）。
     */
    static String buildUserText(CsStreamRequest request, String ragContext) {
        StringBuilder sb = new StringBuilder(request.question());
        if (request.goodsId() != null) {
            sb.append("\n\n【当前咨询商品】goodsId=").append(request.goodsId())
                    .append("（用户正在看这个商品，若问题里的「这个」「它」指代不明，优先理解为该商品；"
                            + "价格/库存仍必须用工具查询确认）");
        }
        if (!isBlank(request.orderNo())) {
            sb.append("\n\n【当前咨询订单】orderNo=").append(request.orderNo());
        }
        if (!isBlank(ragContext)) {
            sb.append("\n\n").append(ragContext);
        }
        return sb.toString();
    }

    private String safeRagPrompt(String question) {
        try {
            return ragService.asPrompt(question, ragTopK);
        } catch (Exception e) {
            log.warn("RAG 上下文获取异常，本次降级为『仅工具、无 RAG』：{}", e.toString());
            return "";
        }
    }

    /**
     * 失败时给用户的**可读话术**（原始异常只进日志）。
     *
     * <p>不把上游的 {@code 429 ... reset after 3s} 这类报文甩给用户。
     */
    static String friendlyMessage(Throwable t) {
        if (t instanceof NonRetriableException) {
            // 400/401/403/模型不可用：重试无用，属于服务端配置问题
            return "模型调用失败，请稍后再试";
        }
        return "当前咨询较多，请稍后再试";
    }

    private static RuntimeException asRuntimeException(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(
                cause == null ? "模型流式调用失败（无异常详情）" : cause.getMessage(), cause);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("SSE 事件投递失败（客户端可能已断开）：{}", e.toString());
        }
    }

    private static String oneLine(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        String flat = msg.replaceAll("\\s+", " ").trim();
        return t.getClass().getSimpleName() + ": "
                + (flat.length() > 200 ? flat.substring(0, 200) + "…" : flat);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------
    // 内部小件
    // ------------------------------------------------------------------

    /** 已产出的回答 + 当时的对话历史（gate 打回时要续写） */
    private record Generation(String answer, List<ChatMessage> messages) {
    }

    /** 首个 delta 的时刻（相对请求开始的毫秒）；从未收到则为 -1 */
    private static final class FirstToken {

        private final AtomicLong elapsedMs = new AtomicLong(-1);

        void mark(long startMs) {
            elapsedMs.compareAndSet(-1, System.currentTimeMillis() - startMs);
        }

        long elapsedMs() {
            return elapsedMs.get();
        }

        /** 还没有任何真实内容（首字）被送出 */
        boolean notYetSeen() {
            return elapsedMs.get() < 0;
        }
    }

    /**
     * delta 的出口。两种实现的差别正是 audit 与 gate 的差别（见类注释）。
     */
    private interface DeltaSink {

        void accept(String text);

        /** 已真正送达客户端的段数 —— 用于判断「这一轮还能不能重试」 */
        int delivered();

        /** 已送达文本的总长度（用来量出「最后一轮到底流出了什么」） */
        int streamedLength();

        /** 下标 {@code from} 之后已送达的文本 */
        String streamedTextSince(int from);
    }

    /** audit：立即转发（真流式） */
    private static final class LiveDeltaSink implements DeltaSink {

        private final CsStreamListener listener;
        private final StringBuilder streamed = new StringBuilder();
        private int delivered;

        LiveDeltaSink(CsStreamListener listener) {
            this.listener = listener;
        }

        @Override
        public void accept(String text) {
            listener.onDelta(text);
            streamed.append(text);
            delivered++;
        }

        @Override
        public int delivered() {
            return delivered;
        }

        @Override
        public int streamedLength() {
            return streamed.length();
        }

        @Override
        public String streamedTextSince(int from) {
            return from >= streamed.length() ? "" : streamed.substring(Math.max(0, from));
        }
    }

    /**
     * gate：丢弃逐字 delta。
     *
     * <p>{@code delivered()} 恒为 0 是<b>有意</b>的：用户什么都还没看到，
     * 所以重试与「打回重答」都不会造成重复文本。
     */
    private static final class DiscardingDeltaSink implements DeltaSink {

        @Override
        public void accept(String text) {
            // 故意丢弃：等质检结论后再一次性送达（见类注释）
        }

        @Override
        public int delivered() {
            return 0;
        }

        @Override
        public int streamedLength() {
            return 0;
        }

        @Override
        public String streamedTextSince(int from) {
            return "";
        }
    }
}
