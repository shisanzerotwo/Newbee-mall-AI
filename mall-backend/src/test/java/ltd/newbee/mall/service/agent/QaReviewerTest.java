package ltd.newbee.mall.service.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2-4 验收测试：质检员（结论解析 + 复核请求构造 + 模式解析）。
 *
 * <p>全部用 Mock 模型，不调真实 LLM。
 *
 * <p><b>「audit 不打回 / gate 打回一次」的用例放在
 * {@link CsAgentServiceTest}</b> —— 那个开关和打回逻辑都在编排层（它持有对话历史），
 * 质检员只负责产出结论。本类聚焦质检员自己的职责。
 */
class QaReviewerTest {

    private static ChatResponse response(AiMessage aiMessage) {
        return ChatResponse.builder().aiMessage(aiMessage).build();
    }

    private static QaReviewer reviewerOf(ChatModel model) {
        return new QaReviewer(model);
    }

    // ------------------------------------------------------------------
    // 结论解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("结构化口径：首个非空行严格「合格」/「不合格」")
    void shouldParseStructuredFirstLine() {
        assertTrue(QaReviewer.parseQualified("合格\n数据与工具结果一致"));
        assertFalse(QaReviewer.parseQualified("不合格\n推荐了已下架商品"));
    }

    @Test
    @DisplayName("容忍 markdown 装饰与序号前缀（模型不总是听话）")
    void shouldTolerateMarkdownDecoration() {
        assertTrue(QaReviewer.parseQualified("**合格**\n理由"), "加粗的「合格」要认");
        assertTrue(QaReviewer.parseQualified("# 合格\n理由"), "标题式「合格」要认");
        assertFalse(QaReviewer.parseQualified("1. 不合格\n理由"), "序号前缀的「不合格」要认");
        assertFalse(QaReviewer.parseQualified("- 不合格：价格对不上"), "列表符 + 冒号后接理由也要认");
    }

    @Test
    @DisplayName("结构化口径能救回子串口径会判反的文本（这就是换口径的原因）")
    void structuredRuleShouldFixSubstringFalsePositive() {
        // 首行是「合格」，但理由里出现了「不合格」字样
        String review = "合格\n备注：本次判定不合格的项目无。";

        assertTrue(QaReviewer.parseQualified(review), "按首行判定应为合格");

        // 对照：Python 教学版的子串口径（含「合格」且不含「不合格」）在这里会得到 false
        assertFalse(review.contains("合格") && !review.contains("不合格"),
                "本用例正是为子串口径的失效场景准备的对照");
    }

    @Test
    @DisplayName("非结构化输出：回退到子串口径（并打 WARN，不让口径漂移静默发生）")
    void shouldFallbackToSubstringRule() {
        assertFalse(QaReviewer.parseQualified("我认为这是不合格的，因为价格与工具结果不一致。"));
        assertTrue(QaReviewer.parseQualified("经核对，数据与工具结果一致，判定合格。"));
    }

    @Test
    @DisplayName("空结论：按放行处理（不因质检自身异常吞掉回答）")
    void blankReviewShouldPassThrough() {
        assertTrue(QaReviewer.parseQualified(""));
        assertTrue(QaReviewer.parseQualified(null));
        assertTrue(QaReviewer.parseQualified("   \n  "));
    }

    // ------------------------------------------------------------------
    // 复核请求
    // ------------------------------------------------------------------

    @Test
    @DisplayName("review：把「问题 / 回答 / 工具记录」组成质检请求，并解析出结论")
    void reviewShouldBuildRequestAndParseVerdict() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                response(AiMessage.from("不合格\n回答里的库存数字与工具结果不一致")));

        QaReviewer.QaResult result = reviewerOf(model)
                .review("有货吗", "有货 9999 件", "工具 checkStock({\"goodsId\":10003})");

        assertFalse(result.qualified());
        assertTrue(result.review().contains("库存"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        String sent = captor.getValue().messages().toString();
        assertTrue(sent.contains("质检员"), "要带上质检人设");
        assertTrue(sent.contains("有货吗"), "要带上用户问题");
        assertTrue(sent.contains("有货 9999 件"), "要带上客服回答");
        assertTrue(sent.contains("checkStock"), "要带上工具调用记录（核对数字来源的依据）");
    }

    @Test
    @DisplayName("review：工具记录为空时写「无」，而不是留空白")
    void emptyToolSummaryShouldBeMarkedAsNone() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("合格\nok")));

        reviewerOf(model).review("你好", "您好呀～", "");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        assertTrue(captor.getValue().messages().toString().contains("工具调用记录：无"));
    }

    @Test
    @DisplayName("review：模型没返回内容时放行，且结论文本里写明（可观测，不静默）")
    void blankModelAnswerShouldPassThroughWithNote() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("")));

        QaReviewer.QaResult result = reviewerOf(model).review("有货吗", "有货", "");

        assertTrue(result.qualified(), "质检拿不到结论时放行，避免误伤回答");
        assertTrue(result.review().contains("未返回内容"), "但要在结论里写明，便于排查");
    }

    // ------------------------------------------------------------------
    // 模式解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("QaMode：大小写不敏感；未知值回退 audit（并打 WARN）")
    void qaModeShouldParseCaseInsensitivelyAndFallback() {
        assertEquals(QaReviewer.QaMode.AUDIT, QaReviewer.QaMode.from("audit"));
        assertEquals(QaReviewer.QaMode.GATE, QaReviewer.QaMode.from("gate"));
        assertEquals(QaReviewer.QaMode.GATE, QaReviewer.QaMode.from("GATE"));
        assertEquals(QaReviewer.QaMode.GATE, QaReviewer.QaMode.from(" gate "));
        assertEquals(QaReviewer.QaMode.AUDIT, QaReviewer.QaMode.from(null));
        assertEquals(QaReviewer.QaMode.AUDIT, QaReviewer.QaMode.from(""));
        assertEquals(QaReviewer.QaMode.AUDIT, QaReviewer.QaMode.from("nonsense"),
                "未知值要回退到默认 audit，而不是抛异常让应用起不来");
    }
}
