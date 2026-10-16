package ltd.newbee.mall.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发布辅助 AI（校园二手平台 M3）：复用客服模块的 {@code csChatModel} 通道（OmniRoute 网关，LangChain4j），
 * 不启用 RAG / 工具（估价与标题是单轮生成任务），直接 Prompt + 结果 JSON 校验。
 *
 * <p>与客服流式接口不同，这两个接口是普通 REST（发布页一次点击、结果一次性消费），
 * 因此走非流式模型，契约对前端友好（错误直接 200 内 message 提示 / 5xx 兜底）。</p>
 */
@Service
public class AiAssistService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistService.class);

    private static final String TITLE_PROMPT = """
            你是校园二手交易平台的发布助手。根据用户给出的闲置品信息，
            生成一个「30 字以内的二手商品标题」和一段「80 字以内的描述」。

            要求：
            - 标题口语化、信息密度高：成色 + 品类 + 卖点（如"九成新山地车 可校内试骑"）
            - 描述包含：入手时间/来源、使用情况、出手原因中的已知信息，不编造用户没说的内容
            - 只输出 JSON，不要输出其它文字，不要 markdown 代码块。格式：
              {"title":"...","desc":"..."}
            """;

    private static final String ESTIMATE_PROMPT = """
            你是校园二手市场的估价助手。根据用户提供的品类、成色和标题，
            给出一个合理的二手转卖价格区间（元，人民币）。

            要求：
            - 按校园真实成交习惯估价：九成新约为原价 3~5 折，教材资料按笔记质量 10~50 元
            - 只输出 JSON，不要输出其它文字，不要 markdown 代码块。格式：
              {"min":整数,"max":整数,"reason":"一句话说明依据，30 字内"}
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRetries;

    public AiAssistService(@Qualifier("csChatModel") ChatModel chatModel,
                           @Value("${cs.agent.max-model-retries:3}") int maxRetries) {
        this.chatModel = chatModel;
        this.maxRetries = Math.max(1, maxRetries);
    }

    /**
     * 生成标题与描述。失败抛 RuntimeException，由 Controller 转 503。
     */
    public TitleResult generateTitle(String category, String condition, String rawTitle) {
        String prompt = buildContext(category, condition, rawTitle) + "\n\n请生成标题和描述。";
        JsonNode node = callJson(TITLE_PROMPT + "\n\n" + prompt);
        return new TitleResult(
                textOf(node, "title"),
                textOf(node, "desc"));
    }

    /**
     * 估价。失败抛 RuntimeException，由 Controller 转 503。
     */
    public EstimateResult estimate(String category, String condition, String rawTitle) {
        String prompt = buildContext(category, condition, rawTitle) + "\n\n请给出估价区间。";
        JsonNode node = callJson(ESTIMATE_PROMPT + "\n\n" + prompt);
        int min = intOf(node, "min", 10);
        int max = intOf(node, "max", Math.max(min, 100));
        if (max < min) { int t = min; min = max; max = t; }
        return new EstimateResult(min, max, textOf(node, "reason"));
    }

    private String buildContext(String category, String condition, String rawTitle) {
        return "品类：" + safe(category, "未知")
                + "\n成色：" + safe(condition, "未知")
                + "\n用户已填的原始标题：" + safe(rawTitle, "（未填写）");
    }

    /** 单轮调用 + 简单线性退避重试（复用客服模块 4s/8s 经验）。 */
    private JsonNode callJson(String prompt) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ChatResponse response = chatModel.chat(ChatRequest.builder()
                        .messages(List.of(UserMessage.from(prompt)))
                        .build());
                AiMessage ai = response == null ? null : response.aiMessage();
                if (ai == null || ai.text() == null || ai.text().isBlank()) {
                    throw new IllegalStateException("模型返回空文本");
                }
                String text = ai.text().trim();
                // 容错：剥掉可能的 ```json 围栏（Prompt 已禁止，个别模型仍会带）
                text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
                JsonNode node = objectMapper.readTree(text);
                if (!node.isObject()) {
                    throw new IllegalStateException("模型输出不是 JSON 对象：" + oneLine(text));
                }
                return node;
            } catch (RuntimeException e) {
                throw e; // 业务异常不重试
            } catch (Exception e) {
                last = new RuntimeException("AI 辅助生成失败：" + oneLine(e.toString()), e);
                log.warn("AI 辅助调用失败（第 {}/{} 次）：{}", attempt, maxRetries, oneLine(e.toString()));
                if (attempt < maxRetries) {
                    sleepQuietly(4000L * attempt);
                }
            }
        }
        throw last != null ? last : new IllegalStateException("AI 辅助生成失败");
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static String safe(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || !v.isTextual() ? "" : v.asText();
    }

    private static int intOf(JsonNode node, String field, int fallback) {
        JsonNode v = node.get(field);
        return v == null || !v.isNumber() ? fallback : v.asInt();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ');
    }

    public record TitleResult(String title, String desc) {
    }

    public record EstimateResult(int min, int max, String reason) {
    }
}
