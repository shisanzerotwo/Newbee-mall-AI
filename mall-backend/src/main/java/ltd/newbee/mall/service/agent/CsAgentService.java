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
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客服编排（M2-4）—— 把「RAG 检索 + 工具循环 + 质检」串成一次完整问答。
 *
 * <p>职责边界：
 * <ul>
 *   <li>本类只管<b>编排</b>：决定何时检索、何时调工具、何时质检、是否打回</li>
 *   <li>工具能力在 {@link MallTools}（6 个只读工具，直调 Mapper）</li>
 *   <li>检索能力在 {@link RagService}（混合检索 RRF）</li>
 *   <li>质检在 {@link QaReviewer}（独立第二角色）</li>
 * </ul>
 * 本类不直接访问数据库、不写 HTTP 接口（SSE 层在 M2-5）。
 *
 * <h3>三条不变量</h3>
 * <ol>
   *   <li><b>数据铁律</b>：价格 / 库存 / 订单状态只来自工具结果；RAG 片段仅作语义参考
   *       （知识库语料在 {@link KnowledgeBuilder} 里已剔除价格/库存字段，见其语料红线）。
   *       出口门禁为「意图 + 内容」双判定：本轮没调工具时，回答含价格/库存特征
   *       或问题命中商城意图（如"有哪些分类"）都会触发一次带工具提醒的重问</li>
 *   <li><b>工具循环有上限</b>：{@code cs.agent.max-tool-rounds}（默认 6，M3-C 按 DESIGN §7.2 对齐；原为 5）。Python 教学版的
 *       {@code while response.tool_calls:} 没有上限，模型一旦反复调工具就会无限烧钱；
 *       这里超限即停并<b>打 WARN</b>，绝不静默死循环。</li>
 *   <li><b>质检双模式</b>：默认 {@code audit}（旁路：不阻塞、不打回，结论经 future 回给调用方）；
 *       {@code cs.qa.mode=gate} 时同步把关，不合格把质检意见追加给客服<b>重答一次</b>（只一次，不循环）。</li>
 * </ol>
 */
@Service
public class CsAgentService {

    private static final Logger log = LoggerFactory.getLogger(CsAgentService.class);

    /** 客服人设（从 Python 教学版 {@code 12_agent_cs/cs_agent.py} 的 CS_PROMPT 移植，工具名改为 Java 侧 camelCase） */
    public static final String CS_PROMPT = """
            你是「新蜂商城」的客服「小蜂」，一个亲切、有耐心的真人客服。

            【说话要有"人味"】
            - 语气自然口语化，像真人聊天：可以用"您""您看""要不我帮您""这个挺适合您的"这类表达
            - 有温度：先回应感受（"好的~""这个我帮您看看"），再给信息
            - 永远不要以"您说得对，我来重新组织回答"这类道歉式、检讨式的句子开头
            - 一次回答不要堆太长表格，用自然语言讲重点；先说结论，再给细节

            【推荐产品（重要能力）】
            - 用户求推荐时，用 recommendGoods 工具查候选（可尝试 price_asc / price_desc 排序）
            - 推荐话术：给出 2-3 款候选，逐款讲"特点+价格+库存"，最后给一个明确建议
              （"如果想要性价比，我建议这款…；如果追求品质，可以看这款…"）
            - 推荐时可以说"您更看重性价比还是品质？"引导用户明确需求
            - 只有明确在售的商品才推荐；下架商品如实告知，不推荐

            【处理模糊问题（重要能力）】
            - 用户问题信息不全时（缺预算/品类/偏好/用途），先判断模糊程度，再决定策略：
              · 轻模糊（品类明确，缺偏好）：先调工具拿到真实候选（searchGoods / searchByCategory / recommendGoods），
                再基于**工具返回的商品**给“多范围解答”——按价位/类型分 2~3 档，每档推荐一款候选（如“经济款…主流款…品质款…”），
                最后轻轻问一句偏好（“您更看重性价比、品质，还是便携？”）
              · 重模糊（完全没有方向，如“有什么好东西推荐吗”）：先给出 2~3 个常见方向的选择题式引导
                （“您是想买护肤、数码还是家居类？大概什么预算？”），并同时给一两个示例候选，让用户有东西可以回应
            - ⚠️ 模糊策略只决定**交互方式**，不豁免【数据铁律】：凡是提到具体商品名/价格/库存，必须来自工具结果；
              拿不到工具结果就说“我帮您查一下”，绝不凭记忆报商品和价格
            - 永远不要用"您能具体说说吗"这种单句反问打发用户——先给有价值的多范围信息，
              再引导确认。宁可多给一点，不让用户空手而归

            【数据铁律】
            1. 涉及商品、库存、订单的任何信息，必须先调用工具获取真实结果，禁止编造价格/库存/订单状态
            2. 商品详情字段若工具未返回或显示占位（如"商品介绍加载中"），用简介/规格/标签介绍，
               绝不编造详情内容
            3. 知识库检索结果仅供参考；价格、库存、订单状态一律以工具查询结果为准
            4. 不确定的信息先调用工具核实；确实查不到的，如实说明，而不是猜测
            5. 禁止用通用知识回答商城问题：分类、在售商品、价格、库存、订单都必须来自工具结果。
               例：问"化妆品有哪些分类"必须调 searchByCategory 查本店真实分类，
               不能背"一般化妆品分为护肤/彩妆/香水…"
            6. 禁止"只说不做"：不要说"我先帮您查一下"却不调用工具——
               要么立刻调用工具，要么如实说明查不到
            7. 查到即总结：一轮问答的工具调用次数有限，拿到工具结果、信息足够时
               就直接基于结果给出回答，不要为同一个问题反复发起相似查询
            """;

    /** 工具循环超限且模型没给出任何文本时的兜底答复（客服口吻，且给出可操作的下一步）；流式编排共用 */
    static final String TOOL_LIMIT_ANSWER =
            "抱歉，这次我查到的东西比较多，一时没能整理出完整答复。您可以把问题说得更具体一些（"
                    + "例如具体商品名或订单号），我再帮您查一次。";

    /** 模型正常结束但没给出文本时的兜底（属于模型异常，日志里会 WARN）；流式编排共用 */
    static final String EMPTY_ANSWER =
            "抱歉，这次我没能组织好答复。您可以再问一次，或换个说法告诉我需求。";

    /**
     * 上游把整轮时间耗完（没吐任何文本就撞上单轮上限）时的兜底：对用户而言这是
     * 「服务忙/慢」而非「模型坏了」，话术要给出可操作的下一步且不要暴露内部细节。
     *
     * <p>真机背景（2026-09-18）：agnes 免费额度受限时 OmniRoute 只回 keepalive 心跳
     * （{@code id=chatcmpl-keepalive}、{@code delta:{}}），整轮 40~50s 一个字都不给，
     * 最后撞上 {@code cs.stream.round-timeout-ms}(45s)。此时甩 EMPTY_ANSWER 会让用户
     * 以为是自己的提问有问题，与实际原因不符。
     */
    static final String BUSY_ANSWER =
            "当前咨询的人有点多，我这边响应慢了些，没能及时给您答复。麻烦您稍后再试一次～";

    /**
     * 工具循环超限时，为「未执行」的待办工具调用补的占位结果。
     *
     * <p>不加它的话，对话会以一条「带 tool_calls 的 AI 消息」结尾；gate 模式打回时
     * 会在这条后面接 user 消息，OpenAI 兼容接口会直接报 400
     * （{@code tool_calls must be followed by tool messages}）。
     *
     * <p>包级可见：M2-5 的流式编排（{@link CsStreamService}）复用同一条对话格式约束。
     */
    static final String TOOL_LIMIT_SKIP_NOTE = "（已达单次问答的工具调用上限，本次未执行）";

    /**
     * 数据铁律出口门禁（内容侧）：回答出现“数字紧贴价格/库存单位”、且本轮没有工具调用时触发。
     * 年份与订单号不命中，避免把普通日期和订单号误判为商品数据。
     */
    private static final Pattern DATA_RULE_PATTERN =
            Pattern.compile("[¥￥]\\s*\\d+|\\d+(?:\\.\\d+)?\\s*(?:元|台|件)");

    /**
     * 数据铁律出口门禁（意图侧）：问题命中商城意图词 → 视为「在问商城数据」，
     * 本轮却没有任何工具调用即违规。这是 #8「该查不查」幻觉变体的门禁：
     * 模型用通用知识答「化妆品一般分为护肤/彩妆/香水…」，回答里没有价格/库存数字，
     * 内容侧正则抓不到，只能靠问题意图兜住。
     *
     * <p>词表设计原则是<b>宁漏不误伤</b>：不收「有没有」（“你有没有听过…”这类闲聊会误伤，
     * 10 问回归的 #7「有没有扫地机器人？」因此是已知漏网，靠内容侧正则兜底）；
     * 退换货政策、天气、问候类必须不命中。
     */
    private static final Pattern MALL_INTENT_PATTERN =
            Pattern.compile("价格|售价|多少钱|库存|有货|在售|下架|分类|品类|订单|推荐|有什么|有哪些|型号|品牌");

    private static final String DATA_RULE_REMINDER =
            "本轮没有调用任何工具，但问题涉及商城数据（或回答中出现了价格/库存信息）。"
                    + "数据铁律要求：商城数据必须先调用工具获取，禁止用通用知识回答商城问题。"
                    + "请先调用合适的工具（searchGoods / getGoodsDetail / checkStock 等）核实，"
                    + "查到结果后直接基于结果总结回答（不要为同一个问题反复发起相似查询）。"
                    + "不要道歉式开头。";

    private final ChatModel chatModel;
    private final RagService ragService;
    private final QaReviewer qaReviewer;

    /**
     * 工具分发。M2-5 的流式编排（{@link CsStreamService}）也用它，
     * 保证「非流式」与「流式」两条路径执行的是同一套工具、同一份默认值。
     */
    private final MallToolInvoker toolInvoker;

    /** 工具规格从类上取（而非实例）：即使 MallTools 将来被代理包装，规格也不会丢 */
    private final List<ToolSpecification> toolSpecifications;

    private final int maxToolRounds;
    private final int ragTopK;
    private final int maxModelRetries;
    private final boolean dataRuleGuard;
    private final QaReviewer.QaMode qaMode;

    /** 质检旁路用的执行器：一次质检就是一次阻塞式 HTTP 调用，虚拟线程最合适（Java 21） */
    private final ExecutorService reviewExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CsAgentService(@Qualifier("csChatModel") ChatModel chatModel,
                          MallTools mallTools,
                          RagService ragService,
                          QaReviewer qaReviewer,
                          @Value("${cs.agent.max-tool-rounds:6}") int maxToolRounds,
                          @Value("${cs.agent.top-k:3}") int ragTopK,
                          @Value("${cs.agent.max-model-retries:3}") int maxModelRetries,
                          @Value("${cs.agent.data-rule-guard:true}") boolean dataRuleGuard,
                          @Value("${cs.qa.mode:audit}") String qaMode) {
        this.chatModel = chatModel;
        this.toolInvoker = new MallToolInvoker(mallTools);
        this.ragService = ragService;
        this.qaReviewer = qaReviewer;
        this.maxToolRounds = Math.max(1, maxToolRounds);
        this.ragTopK = Math.max(1, ragTopK);
        this.maxModelRetries = Math.max(1, maxModelRetries);
        this.dataRuleGuard = dataRuleGuard;
        this.qaMode = QaReviewer.QaMode.from(qaMode);
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(MallTools.class);
        log.info("客服编排就绪：qaMode={}，maxToolRounds={}，ragTopK={}，dataRuleGuard={}，工具数={}",
                this.qaMode, this.maxToolRounds, this.ragTopK, this.dataRuleGuard,
                this.toolSpecifications.size());
    }

    @PreDestroy
    public void shutdown() {
        reviewExecutor.shutdown();
    }

    /**
     * 回答一个问题。行为由 {@code cs.qa.mode} 决定：
     * <ul>
     *   <li>{@code audit}（默认）：返回时回答已就绪；质检在后台跑，结论经
     *       {@link CsAnswer#pendingReview()} 回调（不阻塞、不打回）</li>
     *   <li>{@code gate}：返回时质检已完成；不合格则已重答一次（结论在
     *       {@link CsAnswer#review()}）</li>
     * </ul>
     */
    public CsAnswer answer(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        Generated generated = enforceDataRule(question, generate(question));

        if (qaMode == QaReviewer.QaMode.GATE) {
            QaReviewer.QaResult review;
            try {
                review = qaReviewer.review(question, generated.answer(), toolSummary(generated.toolCalls()));
            } catch (Exception e) {
                // 质检失败（如上游 429 / 超时）不应让用户拿不到回答 → 放行 + WARN
                log.warn("gate 模式质检调用失败，放行本次回答（不打回）。question={}，原因={}",
                        question, e.toString());
                return new CsAnswer(generated.answer(), generated.toolCalls(), qaMode, null, null);
            }
            if (review.qualified()) {
                return new CsAnswer(generated.answer(), generated.toolCalls(), qaMode, review, null);
            }
            log.info("质检不合格（gate 模式），打回重答一次。question={}", question);
            Generated retried = retryOnce(generated, review);
            // 工具轨迹保留「生成阶段」的：那才是回答里的数据来源证据
            return new CsAnswer(retried.answer(), generated.toolCalls(), qaMode, review, null);
        }

        // audit（默认）：旁路质检，不阻塞回答、不打回
        return new CsAnswer(generated.answer(), generated.toolCalls(), qaMode, null,
                reviewAsync(question, generated));
    }

    // ------------------------------------------------------------------
    // 生成：RAG 上下文 + 工具循环
    // ------------------------------------------------------------------

    private Generated generate(String question) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(CS_PROMPT));
        String ragContext = safeRagPrompt(question);
        messages.add(UserMessage.from(isBlank(ragContext) ? question : question + "\n\n" + ragContext));
        return generateFrom(messages, question);
    }

    /**
     * 数据门禁触发后的重问：保留原消息，只追加一次工具提醒，随后继续同一套工具循环。
     * 不抛异常；第二次仍违规时由调用方放行并留 ERROR。
     */
    private Generated regenerateWithToolReminder(Generated generated, String question) {
        List<ChatMessage> messages = new ArrayList<>(generated.messages());
        messages.add(UserMessage.from(DATA_RULE_REMINDER));
        return generateFrom(messages, question);
    }

    private Generated generateFrom(List<ChatMessage> messages, String question) {
        List<ToolCall> toolCalls = new ArrayList<>();
        int rounds = 0;
        boolean truncated = false;
        AiMessage ai = chat(messages);
        // ⚠️ 必须把带 tool_calls 的 assistant 消息也放进历史：
        // OpenAI 协议要求 tool 消息必须紧跟其对应的 assistant(tool_calls)，
        // 否则下一轮请求会 400（tool_calls must be followed by tool messages）。
        // 这条在 Mock 测试里看不见（Mock 不校验报文），只能靠真实调用发现。
        messages.add(ai);

        while (ai.hasToolExecutionRequests()) {
            if (rounds >= maxToolRounds) {
                truncated = true;
                log.warn("工具循环达到上限（{} 轮）已强制停止，避免无限循环。question={}，"
                        + "已执行的工具调用：{} 次", maxToolRounds, question, toolCalls.size());
                // 为待办但未执行的工具调用补上占位结果，保持对话格式合法（见 TOOL_LIMIT_SKIP_NOTE）
                for (ToolExecutionRequest pending : ai.toolExecutionRequests()) {
                    messages.add(ToolExecutionResultMessage.from(pending, TOOL_LIMIT_SKIP_NOTE));
                }
                break;
            }
            rounds++;
            for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                long start = System.currentTimeMillis();
                String result = invokeTool(request.name(), request.arguments());
                long costMs = System.currentTimeMillis() - start;
                toolCalls.add(new ToolCall(request.name(), request.arguments(), result, costMs));
                messages.add(ToolExecutionResultMessage.from(request, result));
            }
            ai = chat(messages);
            messages.add(ai);   // 同上：本轮 assistant 消息（含最终回答）也要进历史，
                                //         否则 gate 打回时对话里会缺 AI 的回答
        }

        String answer = ai.text();
        if (isBlank(answer)) {
            if (!truncated) {
                log.warn("模型返回了空文本（且无工具调用请求）。question={}", question);
            }
            answer = truncated ? TOOL_LIMIT_ANSWER : EMPTY_ANSWER;
        }
        return new Generated(answer, List.copyOf(toolCalls), List.copyOf(messages));
    }

    /**
     * A2 数据铁律门禁。返回的“事实轨迹”保留首次生成的轨迹：
     * 首次无工具是触发原因的客观记录，不把重问过程伪装成首次回答的数据来源。
     */
    private Generated enforceDataRule(String question, Generated generated) {
        if (!dataRuleGuard || !violatesDataRule(question, generated.answer(), generated.toolCalls())) {
            return generated;
        }
        log.warn("数据铁律门禁触发（本轮未调用工具）：回答含价格/库存特征={}，问题命中商城意图={}。question={}",
                DATA_RULE_PATTERN.matcher(generated.answer()).find(), needsMallData(question), question);
        Generated regenerated = regenerateWithToolReminder(generated, question);
        if (violatesDataRule(question, regenerated.answer(), regenerated.toolCalls())) {
            log.error("数据铁律门禁重答后仍违规，按设计放行（用户不能空手）。question={}", question);
        }
        return new Generated(regenerated.answer(), generated.toolCalls(), regenerated.messages());
    }

    /** 纯函数：回答含价格/库存特征，且本轮没有工具调用。 */
    static boolean violatesDataRule(String answer, List<ToolCall> toolCalls) {
        return !isBlank(answer)
                && (toolCalls == null || toolCalls.isEmpty())
                && DATA_RULE_PATTERN.matcher(answer).find();
    }

    /** 纯函数：问题是否在问商城数据（关键词启发式，宁漏不误伤，见 {@link #MALL_INTENT_PATTERN}）。 */
    static boolean needsMallData(String question) {
        return question != null && MALL_INTENT_PATTERN.matcher(question).find();
    }

    /**
     * 意图 + 内容双判定：工具轨迹非空 → 直接放行（调过工具即有数据来源，不做内容审查）；
     * 否则「回答命中价格/库存特征」或「问题命中商城意图」→ 违规。
     * 2 参版本 {@link #violatesDataRule(String, List)} 保留不删（既有单测直测它）。
     */
    static boolean violatesDataRule(String question, String answer, List<ToolCall> toolCalls) {
        if (isBlank(answer) || (toolCalls != null && !toolCalls.isEmpty())) {
            return false;
        }
        return DATA_RULE_PATTERN.matcher(answer).find() || needsMallData(question);
    }

    /** gate 模式打回：把质检意见追加进对话后让模型重写一次（只一次，不循环） */
    private Generated retryOnce(Generated generated, QaReviewer.QaResult review) {
        List<ChatMessage> messages = new ArrayList<>(generated.messages());
        messages.add(UserMessage.from("质检员反馈：" + review.review()
                + "\n请根据反馈修正你的回答。注意：直接用自然、有温度的口吻重新回答，"
                + "不要以「您说得对」之类的道歉/检讨式句子开头。"));
        AiMessage ai = chat(messages);
        String answer = ai.text();
        if (isBlank(answer)) {
            log.warn("打回重答后模型仍未返回文本，保留上一版回答");
            answer = generated.answer();
        }
        return new Generated(answer, generated.toolCalls(), messages);
    }

    /**
     * 调一次模型，内置**重试**。
     *
     * <p>为何自实现：本项目的模型通道（OmniRoute → agnes 免费额度）会**高频 429**
     * （提示 {@code reset after 3s}）；一次 429 就让用户拿不到回答是不可接受的。
     * 而 LangChain4j 内置的 HTTP 层重试是**指数退避、不可控**，不适配这个 3 秒窗口，
     * 所以这里用线性退避（1000ms × 第几次）自实现，并在 {@code CsAgentConfig}
     * 里把 Builder 的 {@code maxRetries} 设为 <b>0</b> 关掉内置那层，避免两层叠加。
     *
     * <p>⚠️ <b>事实订正（2026-09-18）</b>：本注释初版写着「Builder <b>没有</b> maxRetries、
     * 内置重试<b>无法从 Builder 关闭</b>」，并称"用 javap 核实过全部方法"—— <b>那是错的</b>。
     * 根因：当时 javap 输出用的过滤正则是 {@code retry|Retry|timeout|Timeout}，
     * 而真实方法名是 {@code maxRetries}（含 {@code Retries}，不是 {@code Retry}）→ <b>漏匹配</b>，
     * "没搜到"被当成了"不存在"，还扩散进了另外 3 处注释/文档。
     * 实际（javap 反编译 {@code OpenAiChatModel} 构造器）：
     * {@code Utils.getOrDefault(builder.maxRetries, 2)} —— <b>有</b>该方法，默认 <b>2</b>，
     * 且<b>可以设 0 真正关闭</b>。行为证据：{@code OpenAiRetryBehaviorTest} 用假上游
     * 实测 HTTP 请求数 —— 默认 3 / {@code maxRetries(0)} 为 1 / {@code maxRetries(1)} 为 2。
     * 📌 教训：<b>用过滤器"没搜到"时先质疑过滤器，别急着当结论</b>（与探针要先确认
     * "观察手段真能看到信号"是同一类错误）。
     *
     * <p>本方法刻意<b>只重试可重试异常</b>：以
     * {@link NonRetriableException}（400/401/403/模型不可用）为黑名单立即抛出，
     * 其余（429 RateLimit、5xx、网络 IO 包装）才重试。
     *
     * <p>退避：{@code 4000ms × 第几次}（4s / 8s…）。
     * <b>为何不是 1s/2s</b>：agnes 的 429 响应明确写 {@code reset after 3s}，
     * 而每一次尝试都会刷新这个窗口 → 退避必须严格大于 3s 才能跨过去；
     * 初版的 1s/2s 实测跨不过（3 次尝试全部 429 失败，而上游同参数 curl 单发正常）。
     */
    private AiMessage chat(List<ChatMessage> messages) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxModelRetries; attempt++) {
            try {
                ChatResponse response = chatModel.chat(ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(toolSpecifications)
                        .build());
                if (response == null || response.aiMessage() == null) {
                    log.warn("模型返回空响应（response 或 aiMessage 为 null），本轮按「无工具、无文本」处理");
                    return AiMessage.from("");
                }
                if (attempt > 1) {
                    log.info("模型调用在第 {} 次尝试成功", attempt);
                }
                return response.aiMessage();
            } catch (NonRetriableException e) {
                // 400 InvalidRequest / 401、403 Authentication / 模型不可用：
                // 重试它们纯属浪费（实测 12 次重试全部命中这类），立即向外抛。
                throw e;
            } catch (RuntimeException e) {
                // 其余一律视为可重试：RetriableException（429 RateLimit / 5xx）
                // 以及被 LangChain4j 包装成 RuntimeException 的网络 IO 异常
                // （实测形态：java.lang.RuntimeException: java.io.IOException: ...；
                //   因此不能写 catch (IOException) —— 那是 unreachable 的编译错误）
                last = e;
                log.warn("模型调用失败（第 {}/{} 次，可重试）：{}", attempt, maxModelRetries,
                        oneLine(e));
                if (attempt < maxModelRetries) {
                    // 退避必须**严格大于上游的重置窗口**：agnes 的 429 明说 "reset after 3s"，
                    // 而**每一次尝试都会刷新该窗口**，所以 1s / 2s 这类短退避实测跨不过去
                    //（CsAgentRealCallIT 实测：1s/2s/3s 退避下 3 次尝试全部 429；
                    //  上游本身正常 —— 同参数 curl 单发可 200）。旧值 1000L * attempt 见 git 历史。
                    sleepQuietly(4000L * attempt);   // 4s / 8s
                }
            }
        }
        throw last;
    }

    /** 日志用：把异常压成一行，避免网关那沚 ~1KB 的 diagnostics JSON 整段打进日志 */
    private static String oneLine(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        String flat = msg.replaceAll("\\s+", " ").trim();
        return t.getClass().getSimpleName() + ": "
                + (flat.length() > 200 ? flat.substring(0, 200) + "…" : flat);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeRagPrompt(String question) {
        try {
            return ragService.asPrompt(question, ragTopK);
        } catch (Exception e) {
            // RagService 内部已 catch 并 WARN；这里再兜一层，保证检索异常不会让整次问答失败
            log.warn("RAG 上下文获取异常，本次降级为『仅工具、无 RAG』：{}", e.toString());
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 质检：旁路（audit）
    // ------------------------------------------------------------------

    private CompletableFuture<QaReviewer.QaResult> reviewAsync(String question, Generated generated) {
        String toolSummary = toolSummary(generated.toolCalls());
        return CompletableFuture
                .supplyAsync(() -> qaReviewer.review(question, generated.answer(), toolSummary),
                        reviewExecutor)
                .exceptionally(ex -> {
                    // 质检失败不改变已给出的回答；但必须留痕，且结论文本里写明失败
                    log.warn("异步质检失败（audit 模式，不影响回答）：{}", ex.toString());
                    return new QaReviewer.QaResult(true, "（质检失败，已放行：" + ex.getMessage() + "）");
                });
    }

    // ------------------------------------------------------------------
    // 工具分发
    // ------------------------------------------------------------------

    /**
     * 按工具名分发。实现（含失败处理与「未知工具回传清单」）在 {@link MallToolInvoker} ——
     * 与 M2-5 的流式编排共用同一份分发表。
     */
    private String invokeTool(String name, String argumentsJson) {
        return toolInvoker.invoke(name, argumentsJson).result();
    }

    /**
     * 工具调用摘要（进质检 prompt，供核对数字来源）——对应 Python 版的 tool_summary。
     * 包级可见：M2-5 的流式编排复用同一份摘要口径。
     */
    static String toolSummary(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolCall call : toolCalls) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("工具 ").append(call.name()).append('(').append(call.args()).append(')');
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------
    // 对外结果类型
    // ------------------------------------------------------------------

    /** 一次工具调用的记录（供质检核对数字来源，也供 M2-5 的「工具调用」面板展示） */
    public record ToolCall(String name, String args, String result, long ms) {
    }

    /** 问答结果。{@code review} 与 {@code pendingReview} 二者只会有一个非 null，取决于质检模式 */
    public record CsAnswer(String answer,
                           List<ToolCall> toolCalls,
                           QaReviewer.QaMode qaMode,
                           QaReviewer.QaResult review,
                           CompletableFuture<QaReviewer.QaResult> pendingReview) {
    }

    /** 生成阶段内部结果（含对话历史，供 gate 模式打回时续写） */
    private record Generated(String answer, List<ToolCall> toolCalls, List<ChatMessage> messages) {
    }
}
