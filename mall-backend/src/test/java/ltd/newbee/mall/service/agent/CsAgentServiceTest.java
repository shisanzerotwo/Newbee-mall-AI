package ltd.newbee.mall.service.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2-4 验收测试：客服编排（RAG 上下文 + 工具循环 + 质检双模式）。
 *
 * <p><b>全部用 Mock，不调真实模型</b>：这里要验证的是<b>编排逻辑</b>
 * （工具循环、轮次上限、打回与否），不是模型能力 —— 后者由 function calling spike 单独验证。
 * 因此不依赖 MySQL / Redis / OmniRoute，可在 CI 里稳定跑。
 *
 * <p>质检双模式（audit 不打回 / gate 打回一次）的用例放在本类：模式开关与打回逻辑
 * 都在 {@link CsAgentService} 里（对话历史由它持有），质检员本身只负责"出结论"。
 */
class CsAgentServiceTest {

    private static final int MAX_ROUNDS = 3;

    private ChatModel csModel;
    private MallTools mallTools;
    private RagService ragService;
    private QaReviewer qaReviewer;
    private CsAgentService service;

    @BeforeEach
    void setUp() {
        csModel = mock(ChatModel.class);
        mallTools = mock(MallTools.class);
        ragService = mock(RagService.class);
        qaReviewer = mock(QaReviewer.class);
        when(ragService.asPrompt(anyString(), anyInt())).thenReturn("");
        when(qaReviewer.review(anyString(), anyString(), anyString()))
                .thenReturn(new QaReviewer.QaResult(true, "合格\n数据与工具结果一致"));
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();   // 关掉质检旁路用的虚拟线程执行器，避免测试间残留
        }
    }

    private CsAgentService newService(String qaMode) {
        return newService(qaMode, true);
    }

    private CsAgentService newService(String qaMode, boolean dataRuleGuard) {
        service = new CsAgentService(csModel, mallTools, ragService, qaReviewer,
                MAX_ROUNDS,   // maxToolRounds
                3,            // ragTopK
                3,            // maxModelRetries（模型调用重试次数，见 cs.agent.max-model-retries）
                dataRuleGuard,
                qaMode);
        return service;
    }

    private static ChatResponse response(AiMessage aiMessage) {
        return ChatResponse.builder().aiMessage(aiMessage).build();
    }

    private static AiMessage toolCallMessage(String name, String argumentsJson) {
        return AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("call_" + name)
                        .name(name)
                        .arguments(argumentsJson)
                        .build()))
                .build();
    }

    // ------------------------------------------------------------------
    // 工具循环
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工具循环：模型先请求工具，拿到真实结果后再产出回答")
    void shouldRunToolLoopThenReturnModelText() {
        when(mallTools.searchGoods(eq("化妆水"), eq(3)))
                .thenReturn("找到 1 个相关商品：[10003] 无印良品化妆水｜库存 1000");
        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                response(toolCallMessage("searchGoods", "{\"keyword\":\"化妆水\",\"limit\":3}")),
                response(AiMessage.from("「无印良品化妆水」在售，库存 1000 件。")));

        CsAgentService.CsAnswer answer = newService("audit").answer("化妆水有货吗？");

        verify(mallTools).searchGoods("化妆水", 3);
        assertEquals("「无印良品化妆水」在售，库存 1000 件。", answer.answer());
        assertEquals(1, answer.toolCalls().size(), "应记录 1 次工具调用");
        assertEquals("searchGoods", answer.toolCalls().get(0).name());
        assertTrue(answer.toolCalls().get(0).result().contains("库存 1000"),
                "工具轨迹要保留真实结果（质检与可观测面板都要用）");
        assertEquals(QaReviewer.QaMode.AUDIT, answer.qaMode());
    }

    @Test
    @DisplayName("工具循环上限：模型反复请求工具时，编排在 MAX 轮后停下并给出兜底答复（不死循环）")
    void shouldStopToolLoopAtMaxRounds() {
        when(mallTools.checkStock(anyInt())).thenReturn("「化妆水」库存 1000 件，当前在售。");
        // 模型每轮都要工具、永不产出文本 —— Python 教学版在这里会无限循环
        when(csModel.chat(any(ChatRequest.class)))
                .thenReturn(response(toolCallMessage("checkStock", "{\"goodsId\":10003}")));

        CsAgentService.CsAnswer answer = newService("audit").answer("这个有货吗");

        // 1 次首轮 + MAX_ROUNDS 轮工具循环，此后必须停手
        verify(csModel, times(MAX_ROUNDS + 1)).chat(any(ChatRequest.class));
        verify(mallTools, times(MAX_ROUNDS)).checkStock(anyInt());
        assertEquals(MAX_ROUNDS, answer.toolCalls().size(),
                "工具调用次数应被上限截断（而不是一直调下去）");
        assertFalse(answer.answer().isBlank(), "超限时应返回兜底答复，而不是空字符串");
    }

    @Test
    @DisplayName("工具循环超限后再被打回：对话仍合法（为未执行的 tool_calls 补了占位结果）")
    void truncationShouldKeepConversationValidForRetry() {
        when(mallTools.checkStock(anyInt())).thenReturn("「化妆水」库存 1000 件，当前在售。");
        // 模型永远只要工具 → 必然触发上限
        when(csModel.chat(any(ChatRequest.class)))
                .thenReturn(response(toolCallMessage("checkStock", "{\"goodsId\":10003}")));
        // 质检判不合格 → gate 模式会打回重答
        when(qaReviewer.review(anyString(), anyString(), anyString()))
                .thenReturn(new QaReviewer.QaResult(false, "不合格\n没答上问题"));

        newService("gate").answer("这个有货吗");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(csModel, times(MAX_ROUNDS + 2)).chat(captor.capture());
        String lastRequest = captor.getAllValues()
                .get(captor.getAllValues().size() - 1).messages().toString();
        assertTrue(lastRequest.contains("已达单次问答的工具调用上限"),
                "打回重答的对话里，未执行的 tool_calls 必须有对应占位结果；"
                        + "否则 OpenAI 兼容接口会报 400（tool_calls must be followed by tool messages）");
    }

    @Test
    @DisplayName("未知工具：把提示回传给模型，不静默丢弃、不崩溃")
    void shouldReportUnknownToolBackToModel() {        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                response(toolCallMessage("noSuchTool", "{}")),
                response(AiMessage.from("抱歉，我换个方式帮您查。")));

        CsAgentService.CsAnswer answer = newService("audit").answer("帮我查点东西");

        assertEquals(1, answer.toolCalls().size());
        assertTrue(answer.toolCalls().get(0).result().contains("未知工具"),
                "未知工具要把可用工具清单回传给模型，而不是返回空");
    }

    @Test
    @DisplayName("工具参数是 JSON 字符串：数字/字符串都能解析，缺省时用默认值")
    void shouldParseToolArguments() {
        when(mallTools.recommendGoods(eq("洗面奶"), eq("default"), eq(3)))
                .thenReturn("推荐「洗面奶」相关商品 1 款：...");
        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                // limit 传成字符串、sort 缺省 —— 都要能兜住
                response(toolCallMessage("recommendGoods", "{\"keyword\":\"洗面奶\",\"limit\":\"3\"}")),
                response(AiMessage.from("给您推荐这几款～")));

        newService("audit").answer("推荐个洗面奶");

        verify(mallTools).recommendGoods("洗面奶", "default", 3);
    }

    // ------------------------------------------------------------------
    // 质检双模式
    // ------------------------------------------------------------------

    @Test
    @DisplayName("gate：质检合格 → 不打回，结论同步返回")
    void gateShouldNotRetryWhenQualified() {
        when(csModel.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("原回答")));
        when(qaReviewer.review(anyString(), anyString(), anyString()))
                .thenReturn(new QaReviewer.QaResult(true, "合格\n数据与工具一致"));

        // 问题用中性文本（不命中商城意图词表）：本用例只验证 gate 质检行为，
        // 不能让问题本身触发数据门禁的额外重问
        CsAgentService.CsAnswer answer = newService("gate").answer("你叫什么名字");

        verify(csModel, times(1)).chat(any(ChatRequest.class));
        assertEquals("原回答", answer.answer());
        assertNotNull(answer.review(), "gate 模式应同步带回质检结论");
        assertTrue(answer.review().qualified());
        assertNull(answer.pendingReview(), "gate 模式不挂 future");
    }

    @Test
    @DisplayName("gate：质检不合格 → 追加质检意见给客服，重答一次（只一次）")
    void gateShouldRetryExactlyOnceWhenUnqualified() {
        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                response(AiMessage.from("第一版回答（推荐了已下架商品）")),
                response(AiMessage.from("修正后回答（只推荐在售商品）")));
        when(qaReviewer.review(anyString(), anyString(), anyString()))
                .thenReturn(new QaReviewer.QaResult(false, "不合格\n推荐了已下架商品"));

        // 中性问题文本：不让问题本身触发数据门禁（本用例只验证质检打回次数）
        CsAgentService.CsAnswer answer = newService("gate").answer("你叫什么名字");

        verify(csModel, times(2)).chat(any(ChatRequest.class));   // 生成 1 次 + 打回重答 1 次，不再循环
        assertEquals("修正后回答（只推荐在售商品）", answer.answer());
        assertNotNull(answer.review());
        assertFalse(answer.review().qualified());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(csModel, times(2)).chat(captor.capture());
        String secondRequest = captor.getAllValues().get(1).messages().toString();
        assertTrue(secondRequest.contains("质检员反馈"), "第二次请求要带上质检反馈");
        assertTrue(secondRequest.contains("推荐了已下架商品"), "反馈内容要真的进对话");
    }

    @Test
    @DisplayName("audit：质检不合格也不打回，结论经 future 异步返回（不阻塞回答）")
    void auditShouldNotRetryAndExposeReviewAsFuture() throws Exception {
        when(csModel.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("原回答")));
        when(qaReviewer.review(anyString(), anyString(), anyString()))
                .thenReturn(new QaReviewer.QaResult(false, "不合格\n推荐了已下架商品"));

        // 中性问题文本：不让问题本身触发数据门禁（本用例只验证 audit 不打回）
        CsAgentService.CsAnswer answer = newService("audit").answer("你叫什么名字");

        verify(csModel, times(1)).chat(any(ChatRequest.class));   // 不打回
        assertEquals("原回答", answer.answer());
        assertNull(answer.review(), "audit 模式返回时质检还没跑完（这正是「不阻塞」）");
        assertNotNull(answer.pendingReview(), "audit 模式结论经 future 回给调用方");
        assertFalse(answer.pendingReview().get(5, TimeUnit.SECONDS).qualified());
    }

    @Test
    @DisplayName("质检上下文：工具调用记录会传给质检员（供核对数字来源）")
    void toolSummaryShouldBePassedToQa() {
        when(mallTools.checkStock(anyInt())).thenReturn("「化妆水」库存 1000 件，当前在售。");
        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                response(toolCallMessage("checkStock", "{\"goodsId\":10003}")),
                response(AiMessage.from("有货，1000 件")));

        newService("gate").answer("有货吗");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(qaReviewer).review(anyString(), anyString(), summary.capture());
        assertTrue(summary.getValue().contains("checkStock"), "质检应看到调用过哪个工具");
        assertTrue(summary.getValue().contains("10003"), "质检应看到工具参数");
    }

    // ------------------------------------------------------------------
    // 降级路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RAG 抛异常时降级为「仅工具」，不拖垮整次问答（且不打回）")
    void ragFailureShouldDegradeToToolOnly() {
        when(ragService.asPrompt(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("redis down"));
        when(csModel.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("您好～我是小蜂")));

        CsAgentService.CsAnswer answer = newService("audit").answer("你好");

        assertEquals("您好～我是小蜂", answer.answer());
        assertTrue(answer.toolCalls().isEmpty());
    }

    @Test
    @DisplayName("模型返回空文本：给兜底答复，不留空字符串")
    void blankModelTextShouldFallBack() {
        when(csModel.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("")));

        CsAgentService.CsAnswer answer = newService("audit").answer("你好");

        assertFalse(answer.answer().isBlank(), "模型空文本时应给客服口吻的兜底答复");
    }

    @Test
    @DisplayName("空问题：直接拒绝，不发起任何模型调用")
    void blankQuestionShouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> newService("audit").answer("  "));
        verify(csModel, times(0)).chat(any(ChatRequest.class));
    }
    // ------------------------------------------------------------------
    // 数据铁律门禁
    // ------------------------------------------------------------------

    @Test
    @DisplayName("数据门禁正则：价格/库存单位命中，年份和订单号不误伤")
    void dataRulePatternShouldBeNarrow() {
        assertTrue(CsAgentService.violatesDataRule("这款只要 9 元", List.of()));
        assertTrue(CsAgentService.violatesDataRule("还有 435 台", List.of()));
        assertFalse(CsAgentService.violatesDataRule("2024 年", List.of()));
        assertFalse(CsAgentService.violatesDataRule("订单 2024051312345678", List.of()));
        assertFalse(CsAgentService.violatesDataRule("", List.of()));
    }

    @Test
    @DisplayName("数据门禁阳性：无工具却报价格/库存 → 追加提醒并重答")
    void dataRuleGuardShouldRegenerateWhenPriceAppearsWithoutToolCalls() {
        when(mallTools.getGoodsDetail(10003))
                .thenReturn("商品名：无印良品化妆水（在售）\n价格：899.00 元\n库存：435 件");
        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                response(AiMessage.from("红米7 ¥899，库存 435 台。")),
                response(toolCallMessage("getGoodsDetail", "{\"goodsId\":10003}")),
                response(AiMessage.from("我核实过啦，这款商品价格 899.00 元，库存 435 件。")));

        CsAgentService.CsAnswer answer = newService("audit").answer("推荐一款手机");

        verify(csModel, times(3)).chat(any(ChatRequest.class));
        verify(mallTools).getGoodsDetail(10003);
        assertEquals("我核实过啦，这款商品价格 899.00 元，库存 435 件。", answer.answer());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(csModel, times(3)).chat(captor.capture());
        String reminder = captor.getAllValues().get(1).messages().toString();
        assertTrue(reminder.contains("数据铁律"), "重问必须明确提醒数据铁律");
        assertTrue(reminder.contains("searchGoods") || reminder.contains("checkStock"),
                "重问提示应给出可调用工具示例");
    }

    @Test
    @DisplayName("数据门禁阴性 1：有工具轨迹时，价格/库存回答不触发重问")
    void dataRuleGuardShouldNotRegenerateWhenToolWasCalled() {
        when(mallTools.getGoodsDetail(10003))
                .thenReturn("商品名：无印良品化妆水（在售）\n价格：899.00 元\n库存：435 件");
        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                response(toolCallMessage("getGoodsDetail", "{\"goodsId\":10003}")),
                response(AiMessage.from("核实结果是 899.00 元，库存 435 件。")));

        CsAgentService.CsAnswer answer = newService("audit").answer("这个多少钱");

        verify(csModel, times(2)).chat(any(ChatRequest.class));
        assertEquals("核实结果是 899.00 元，库存 435 件。", answer.answer());
    }

    @Test
    @DisplayName("数据门禁阴性 2：无价格的纯话术问题不触发重问")
    void dataRuleGuardShouldNotRegenerateForPolicyOnlyAnswer() {
        when(csModel.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from("我们支持七天内退货，请保持商品完好。")));

        CsAgentService.CsAnswer answer = newService("audit").answer("你们支持退货吗？");

        verify(csModel, times(1)).chat(any(ChatRequest.class));
        assertEquals("我们支持七天内退货，请保持商品完好。", answer.answer());
    }

    @Test
    @DisplayName("数据门禁开关：关闭后不重问")
    void dataRuleGuardSwitchShouldDisableRegeneration() {
        when(csModel.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from("红米7 ¥899，库存 435 台。")));

        CsAgentService.CsAnswer answer = newService("audit", false).answer("推荐一款手机");

        verify(csModel, times(1)).chat(any(ChatRequest.class));
        assertEquals("红米7 ¥899，库存 435 台。", answer.answer());
    }

    @Test
    @DisplayName("数据门禁重答仍违规：放行第二次回答，不抛异常")
    void dataRuleGuardShouldReleaseWhenRegeneratedAnswerStillViolates() {
        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                response(AiMessage.from("红米7 ¥899，库存 435 台。")),
                response(AiMessage.from("这款还是 899 元、435 台。")));

        CsAgentService.CsAnswer answer = newService("audit").answer("推荐一款手机");

        verify(csModel, times(2)).chat(any(ChatRequest.class));
        assertEquals("这款还是 899 元、435 台。", answer.answer());
    }

    // ------------------------------------------------------------------
    // 意图门禁（TASK_GUARD2）：「该查不查」幻觉变体 —— 问题在问商城数据却零工具调用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("意图门禁阳性：问分类却不查库、用通用知识作答 → 追加提醒并重问（#8 变体）")
    void intentGuardShouldRegenerateWhenMallQuestionAnsweredFromGeneralKnowledge() {
        when(mallTools.searchByCategory(eq("化妆品"), anyInt()))
                .thenReturn("分类「化妆品」下找到 2 个商品：\n"
                        + "- [10006] 保湿化妆水｜88.00｜库存 100\n"
                        + "- [10007] 修护面霜｜129.00｜库存 50");
        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                // 第一次：通用知识作答（"护肤/彩妆/香水"），无价格数字、无工具调用 —— #8 实测失败形态
                response(AiMessage.from("化妆品一般分为护肤、彩妆、香水三大类哦～")),
                // 重问后：调 searchByCategory 查本店真实分类，再基于工具结果回答
                response(toolCallMessage("searchByCategory", "{\"categoryName\":\"化妆品\",\"limit\":5}")),
                response(AiMessage.from("本店在售的化妆品有保湿化妆水和修护面霜两类哦～")));

        CsAgentService.CsAnswer answer = newService("audit").answer("化妆品有哪些分类？");

        verify(csModel, times(3)).chat(any(ChatRequest.class));
        verify(mallTools).searchByCategory("化妆品", 5);
        assertEquals("本店在售的化妆品有保湿化妆水和修护面霜两类哦～", answer.answer());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(csModel, times(3)).chat(captor.capture());
        String reminder = captor.getAllValues().get(1).messages().toString();
        assertTrue(reminder.contains("数据铁律"), "重问必须明确提醒数据铁律");
        assertTrue(reminder.contains("searchByCategory") || reminder.contains("searchGoods"),
                "重问提示应给出可调用工具示例");
    }

    @Test
    @DisplayName("意图门禁阴性：退换货政策类问题不命中商城意图 → 不触发重问")
    void intentGuardShouldNotTriggerForPolicyQuestions() {
        when(csModel.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from("我们支持七天内退货，请保持商品完好。")));

        CsAgentService.CsAnswer answer = newService("audit").answer("你们支持退货吗？");

        verify(csModel, times(1)).chat(any(ChatRequest.class));
        assertFalse(CsAgentService.needsMallData("你们支持退货吗？"), "政策类问题不得命中商城意图");
        assertEquals("我们支持七天内退货，请保持商品完好。", answer.answer());
    }

    @Test
    @DisplayName("意图门禁阴性：天气闲聊不命中商城意图 → 不触发重问")
    void intentGuardShouldNotTriggerForSmallTalkQuestions() {
        when(csModel.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from("今天天气不错，适合出门逛街呀～")));

        CsAgentService.CsAnswer answer = newService("audit").answer("今天天气怎么样？");

        verify(csModel, times(1)).chat(any(ChatRequest.class));
        assertFalse(CsAgentService.needsMallData("今天天气怎么样？"), "闲聊问题不得命中商城意图");
        assertEquals("今天天气不错，适合出门逛街呀～", answer.answer());
    }

    @Test
    @DisplayName("意图门禁阴性：商城问题但本轮有工具调用 → 直接放行，不重问")
    void intentGuardShouldNotTriggerWhenToolWasCalled() {
        when(mallTools.searchByCategory(eq("化妆品"), anyInt()))
                .thenReturn("分类「化妆品」下找到 2 个商品：\n"
                        + "- [10006] 保湿化妆水｜88.00｜库存 100\n"
                        + "- [10007] 修护面霜｜129.00｜库存 50");
        when(csModel.chat(any(ChatRequest.class))).thenReturn(
                response(toolCallMessage("searchByCategory", "{\"categoryName\":\"化妆品\",\"limit\":5}")),
                response(AiMessage.from("本店的化妆品分类有保湿化妆水和修护面霜两类哦～")));

        CsAgentService.CsAnswer answer = newService("audit").answer("化妆品有哪些分类？");

        verify(csModel, times(2)).chat(any(ChatRequest.class));
        assertEquals(1, answer.toolCalls().size(), "调过工具就有数据来源，不做内容审查");
    }

    @Test
    @DisplayName("意图门禁词表：10 问回归集逐题断言（#7 刻意漏网属「宁漏不误伤」）")
    void needsMallDataShouldMatchTenRealQuestions() {
        assertTrue(CsAgentService.needsMallData("无印良品的笔记本多少钱？"), "#1 命中（多少钱）");
        assertTrue(CsAgentService.needsMallData("无印良品的化妆水有货吗？"), "#2 命中（有货）");
        assertTrue(CsAgentService.needsMallData("MUJI 化妆盒卖多少钱？"), "#3 命中（多少钱）");
        assertTrue(CsAgentService.needsMallData("店里有什么手机卖？"), "#4 命中（有什么）");
        assertTrue(CsAgentService.needsMallData("推荐一款洗面奶"), "#5 命中（推荐）");
        assertTrue(CsAgentService.needsMallData("我的订单 2024051312345678 什么状态？"), "#6 命中（订单）");
        // 词表刻意不收「有没有」："你有没有听过…"这类闲聊会误伤 → #7 是已知漏网，靠内容侧正则兜底
        assertFalse(CsAgentService.needsMallData("有没有扫地机器人？"), "#7 刻意不命中（宁漏不误伤）");
        assertTrue(CsAgentService.needsMallData("化妆品有哪些分类？"), "#8 命中（有哪些/分类）——TASK_GUARD2 的目标变体");
        assertFalse(CsAgentService.needsMallData("你们支持退货吗？"), "#9 必须不命中（政策类不得误伤）");
        assertFalse(CsAgentService.needsMallData("今天天气怎么样？"), "#10 必须不命中（闲聊不得误伤）");
    }
}
