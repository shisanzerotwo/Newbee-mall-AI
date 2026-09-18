package ltd.newbee.mall.service.agent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 客服质检员（M2-4）—— 提议者-审核者范式里的「审核者」。
 *
 * <p>与客服角色<b>共用同一个模型 Bean</b>（{@code csChatModel}），但用<b>不同的
 * 人设 prompt</b> 独立复核回答。这正是「独立第二角色」的含义：独立性来自
 * <b>视角隔离</b>（只查硬伤），而不是必须换一个模型。
 *
 * <p><b>注入为什么必须写 {@code @Qualifier}</b>：项目后续若为质检单独接入第二个
 * 模型 Bean，按类型注入就会产生歧义（两个 {@code ChatModel} 候选）。提前写死
 * Bean 名可免去那一天的改动。<b>本模块不新增模型 Bean</b>，与 Python 版
 * （两个角色同一个 model、不同 prompt）保持一致。
 *
 * <p><b>结论解析为什么用「结构化 + 回退」双口径</b>：Python 教学版用
 * {@code "合格" in review and "不合格" not in review} 的子串判定，遇到
 * 「合格\n备注：本次判定不合格的项目无」这类文本会判反。这里优先取<b>首个非空行</b>，
 * 去掉 markdown 装饰与序号前缀后，判断它是否<b>以</b> {@code 合格} / {@code 不合格}
 * <b>开头</b>（先判「不合格」；注意「不合格」不以「合格」开头，不会互相误匹配）；
 * 首个非空行两者都不沾时，才回退到子串口径，并且<b>回退时打 WARN</b>
 * （不让口径漂移静默发生）。
 */
@Service
public class QaReviewer {

    private static final Logger log = LoggerFactory.getLogger(QaReviewer.class);

    /** 质检人设：只查硬伤，第一行严格输出结论 */
    public static final String QA_PROMPT = """
            你是客服质检员，只检查【硬伤】，不要挑剔表达风格：
            1. 【数据准确性】回答中的价格、库存、订单状态是否与工具结果一致？（重点核对数字）
            2. 【诚实性】已下架商品是否如实告知？有没有把下架商品当在售推荐？
            3. 【合规性】有没有承诺做不到的事（如"马上发货""保证退款"）？
            4. 【推荐合理性】推荐的商品是否在售？推荐语有没有明显错误？

            注意：回答口语化、没逐条引用工具数据、没说"您说得对"都不算问题，不要为此打回。

            输出格式（严格遵守）：
            第一行只写两个字：合格 或 不合格
            第二行起写一句简短理由
            """;

    private static final String QUALIFIED = "合格";
    private static final String NOT_QUALIFIED = "不合格";

    private final ChatModel chatModel;

    public QaReviewer(@Qualifier("csChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 独立复核一次回答。
     *
     * @param question    用户问题
     * @param answer      客服生成的回答
     * @param toolSummary 本轮工具调用记录（供核对数字来源；为空表示模型没调过工具）
     */
    public QaResult review(String question, String answer, String toolSummary) {
        String userText = "用户问题：" + question
                + "\n\n客服回答：" + answer
                + "\n\n工具调用记录：" + (isBlank(toolSummary) ? "无" : toolSummary);

        ChatResponse resp = chatModel.chat(ChatRequest.builder()
                .messages(SystemMessage.from(QA_PROMPT), UserMessage.from(userText))
                .build());

        String text = (resp == null || resp.aiMessage() == null) ? null : resp.aiMessage().text();
        if (isBlank(text)) {
            // 质检自身没产出结论时「放行」：宁可少拦一次，也不让质检故障把回答吞掉；
            // 但必须留痕（WARN + review 文本注明），不做静默降级。
            log.warn("质检未返回内容，本次按『合格』放行（不阻塞回答）。question={}", question);
            return new QaResult(true, "（质检未返回内容，已放行）");
        }
        return new QaResult(parseQualified(text), text);
    }

    /**
     * 解析质检结论。
     *
     * <p>口径：取<b>首个非空行</b>，去掉 markdown 装饰与序号前缀后判断是否以
     * {@code 合格} / {@code 不合格} 开头（先判「不合格」）；若首个非空行两者都不沾，
     * 回退到 Python 版的子串口径并打 WARN。
     */
    static boolean parseQualified(String review) {
        if (isBlank(review)) {
            return true;
        }
        for (String line : review.split("\\R")) {
            String normalized = normalize(line);
            if (normalized.isEmpty()) {
                continue;
            }
            // 用 startsWith 而不是 equals：模型常写成「不合格：理由」这种一行里带理由的形式。
            // 先判「不合格」——注意「不合格」不以「合格」开头，两者不会互相误匹配。
            if (normalized.startsWith(NOT_QUALIFIED)) {
                return false;
            }
            if (normalized.startsWith(QUALIFIED)) {
                return true;
            }
            break;   // 只看首个非空行，避免被理由里的字样带偏
        }
        boolean fallback = review.contains(QUALIFIED) && !review.contains(NOT_QUALIFIED);
        log.warn("质检结论未按结构化格式返回（首个非空行既非「合格」也非「不合格」），"
                + "已回退到子串口径判定 qualified={}；原始结论：{}", fallback, abbreviate(review));
        return fallback;
    }

    /** 去掉行首的 markdown 装饰/序号（如 {@code **}、{@code -}、{@code 1.}）与行尾标点装饰 */
    private static String normalize(String line) {
        String t = line.trim();
        t = t.replaceAll("^[\\s>*#`\\-–—+\\d.、)）\\]]+", "");
        t = t.replaceAll("[\\s*`。.!！:：]+$", "");
        return t;
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\R+", " / ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 质检结论。{@code review} 是模型原文，保留给调用方展示（M2-5 的质检面板要用） */
    public record QaResult(boolean qualified, String review) {
    }

    /**
     * 质检模式。
     *
     * <ul>
     *   <li>{@link #AUDIT}（默认）—— 旁路：回答先生成，质检事后异步跑，<b>不阻塞、不打回</b></li>
     *   <li>{@link #GATE} —— 同步把关：不合格时把质检意见追加给客服<b>重答一次</b>（只一次，不循环）</li>
     * </ul>
     */
    public enum QaMode {
        AUDIT,
        GATE;

        /** 解析配置值；无法识别时回退 AUDIT 并打 WARN（不让配置写错静默变成另一种语义） */
        public static QaMode from(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUDIT;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if ("gate".equals(value)) {
                return GATE;
            }
            if ("audit".equals(value)) {
                return AUDIT;
            }
            log.warn("未知的 cs.qa.mode={}，已回退为默认 audit（旁路质检）", raw);
            return AUDIT;
        }
    }
}
