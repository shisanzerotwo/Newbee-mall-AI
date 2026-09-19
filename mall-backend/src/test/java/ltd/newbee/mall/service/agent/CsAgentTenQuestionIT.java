package ltd.newbee.mall.service.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P1：对齐 Python 版 {@code 12_agent_cs/test_questions.py} 的固定 10 问回归集。
 *
 * <p>本类以 {@code IT} 结尾，Surefire 默认不跑，避免真实上游 429 让 CI 随机红灯。
 * 手动运行：
 * <pre>
 *   cd /mnt/d/GitHub/xiangmu/newbee-mall-ai
 *   set -a &amp;&amp; . ./.env &amp;&amp; set +a
 *   bash ops/mvn.sh test -Dtest=CsAgentTenQuestionIT
 * </pre>
 *
 * <p>判定刻意不依赖人工“看着不错”：
 * <ul>
 *   <li>必须有非空回答；</li>
 *   <li>需要查数据的问题必须调用约定工具；</li>
 *   <li>价格/库存类回答至少要出现一个工具结果里的数字，防止模型自造数值；</li>
 *   <li>不存在的订单号必须明确表示查不到，不能声称任何订单状态；</li>
 *   <li>退换货和天气这类不需要商城数据的问题不得触发工具调用。</li>
 * </ul>
 *
 * <p>质检器替换为 Mock：这 10 问验证客服答案和工具行为，不重复消耗一次质检模型调用。
 * 完整 audit/gate 行为由既有单测与真机 SSE 测试负责。
 */
@SpringBootTest
/**
 * ⚠️ 本类会**真实调用上游模型**（烧额度、且上游限流时会大面积超时），
 * 因此默认**不执行**：需要显式设置环境变量才跑。
 *
 * <pre>
 * export CS_ENABLE_REAL_MODEL_IT=1
 * set -a &amp;&amp; . ./.env &amp;&amp; set +a
 * bash ops/mvn.sh test -Dtest=CsAgentRealCallIT
 * </pre>
 *
 * <p>加这个守卫的原因（claude 复核建议）：光靠 {@code *IT} 命名只能挡住 Maven 默认生命周期，
 * 挡不住"手滑指定 -Dtest=...IT"—— 一旦误跑就会白烧额度并等待长时间超时。
 *
 * <p><b>抽样复跑</b>：上游限流时跑满 10 题只会刷一批超时，可用 {@code -Dcs.it.questions=1,2,10}
 * 只跑指定题号（默认不指定时仍是全部 10 题，既有语义不变）：
 * <pre>
 *   export CS_ENABLE_REAL_MODEL_IT=1
 *   set -a &amp;&amp; . ./.env &amp;&amp; set +a
 *   bash ops/mvn.sh test -Dtest=CsAgentTenQuestionIT -Dcs.it.questions=1,2,10
 * </pre>
 * ⚠️ 抽样运行会**覆盖** {@code target/cs-regression/java-results.tsv}；要留下全量结果请先备份该文件。
 */
@EnabledIfEnvironmentVariable(named = "CS_ENABLE_REAL_MODEL_IT", matches = "1")
class CsAgentTenQuestionIT {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final List<QuestionCase> CASES = List.of(
            new QuestionCase(1, "无印良品的笔记本多少钱？",
                    Set.of("searchGoods", "getGoodsDetail"), true, false, Set.of()),
            new QuestionCase(2, "无印良品的化妆水有货吗？",
                    Set.of("checkStock"), true, false, Set.of()),
            new QuestionCase(3, "MUJI 化妆盒卖多少钱？",
                    Set.of("searchGoods", "getGoodsDetail"), true, false, Set.of()),
            new QuestionCase(4, "店里有什么手机卖？",
                    Set.of("searchGoods", "searchByCategory"), false, false, Set.of()),
            new QuestionCase(5, "推荐一款洗面奶",
                    Set.of("recommendGoods"), false, false, Set.of()),
            new QuestionCase(6, "我的订单 2024051312345678 什么状态？",
                    Set.of("queryOrder"), false, false,
                    Set.of("未找到", "查不到", "没找到", "没有找到", "无法查到", "无法找到")),
            new QuestionCase(7, "有没有扫地机器人？",
                    Set.of("searchGoods", "searchByCategory", "recommendGoods"), false, false, Set.of()),
            new QuestionCase(8, "化妆品有哪些分类？",
                    Set.of("searchByCategory", "searchGoods"), false, false, Set.of()),
            new QuestionCase(9, "你们支持退货吗？",
                    Set.of(), false, true, Set.of()),
            new QuestionCase(10, "今天天气怎么样？",
                    Set.of(), false, true, Set.of())
    );

    @Resource
    private CsAgentService csAgentService;

    @MockitoBean
    private QaReviewer qaReviewer;

    @BeforeEach
    void setUp() {
        when(qaReviewer.review(anyString(), anyString(), anyString()))
                .thenReturn(new QaReviewer.QaResult(true, "合格\nIT 不重复调用质检模型"));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    @DisplayName("固定 10 问：答案非空、工具轨迹与关键数字可复算")
    void tenQuestionRegressionShouldMeetObjectiveChecks() throws IOException {
        List<QuestionCase> cases = activeCases();
        List<Result> results = new ArrayList<>();
        for (QuestionCase testCase : cases) {
            results.add(runCase(testCase));
        }

        writeResults(results);

        List<String> failed = results.stream()
                .filter(result -> !result.passed())
                .map(result -> "#" + result.index() + " " + result.question()
                        + " -> " + String.join("；", result.failures()))
                .toList();

        assertEquals(cases.size(), results.size() - failed.size(),
                "回归未全部通过（本次 " + cases.size() + " 题）：\n" + String.join("\n", failed)
                        + "\n结果文件：target/cs-regression/java-results.tsv");
    }

    /**
     * 默认返回全部 10 题；指定 {@code -Dcs.it.questions=1,2,10} 时只返回这些题号（抽样复跑用）。
     * 抽取后仍保持原有顺序，结果文件格式不变。
     */
    private static List<QuestionCase> activeCases() {
        String filter = System.getProperty("cs.it.questions", "").trim();
        if (filter.isEmpty()) {
            return CASES;
        }
        Set<Integer> wanted = Arrays.stream(filter.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<QuestionCase> selected = CASES.stream()
                .filter(testCase -> wanted.contains(testCase.index()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "cs.it.questions=" + filter + " 未匹配任何题目（有效题号 1-" + CASES.size() + "）");
        }
        return selected;
    }

    private Result runCase(QuestionCase testCase) {
        long startedAt = System.currentTimeMillis();
        CsAgentService.CsAnswer answer = null;
        String error = "";
        try {
            answer = csAgentService.answer(testCase.question());
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long fullMs = System.currentTimeMillis() - startedAt;

        String answerText = answer == null || answer.answer() == null ? "" : answer.answer();
        List<CsAgentService.ToolCall> toolCalls =
                answer == null ? List.of() : List.copyOf(answer.toolCalls());
        Set<String> actualTools = toolCalls.stream()
                .map(CsAgentService.ToolCall::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String toolText = toolCalls.stream()
                .map(call -> call.result() == null ? "" : call.result())
                .collect(Collectors.joining("\n"));

        List<String> failures = new ArrayList<>();
        if (!error.isBlank()) {
            failures.add(error);
        }
        if (answerText.isBlank()) {
            failures.add("回答为空");
        }
        if (!testCase.expectedTools().isEmpty()
                && testCase.expectedTools().stream().noneMatch(actualTools::contains)) {
            failures.add("期望工具 " + testCase.expectedTools() + "，实际 " + actualTools);
        }
        if (testCase.forbidTools() && !toolCalls.isEmpty()) {
            failures.add("该问题不应调用工具，实际 " + actualTools);
        }
        if (testCase.requireToolNumber() && !containsToolNumber(answerText, toolText)) {
            failures.add("回答未出现任何工具结果中的数字");
        }
        if (!testCase.answerMustContainAny().isEmpty()
                && testCase.answerMustContainAny().stream().noneMatch(answerText::contains)) {
            failures.add("回答未明确表达查不到：需包含 " + testCase.answerMustContainAny());
        }
        if (testCase.index() == 6 && claimsOrderStatus(answerText)) {
            failures.add("不存在的订单却声称了订单状态");
        }

        String toolSummary = toolCalls.stream()
                .map(call -> call.name() + call.args() + "=" + oneLine(call.result()))
                .collect(Collectors.joining(" | "));
        Result result = new Result(
                testCase.index(), testCase.question(), fullMs, actualTools, answerText,
                toolSummary, error, List.copyOf(failures), failures.isEmpty());
        System.out.println("CS10_RESULT|" + result.index()
                + "|passed=" + result.passed()
                + "|fullMs=" + result.fullMs()
                + "|tools=" + result.actualTools()
                + "|answer=" + oneLine(result.answer()));
        if (!failures.isEmpty()) {
            System.out.println("CS10_FAIL|" + result.index() + "|" + String.join("；", failures));
        }
        return result;
    }

    private static boolean containsToolNumber(String answer, String toolText) {
        Matcher matcher = NUMBER.matcher(toolText);
        while (matcher.find()) {
            if (answer.contains(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    private static boolean claimsOrderStatus(String answer) {
        String normalized = answer.toLowerCase(Locale.ROOT);
        return List.of("已支付", "支付成功", "待支付", "待发货", "已发货", "待收货",
                        "已完成", "已关闭", "已取消")
                .stream()
                .anyMatch(normalized::contains);
    }

    private static void writeResults(List<Result> results) throws IOException {
        Path output = Path.of("target", "cs-regression", "java-results.tsv");
        Files.createDirectories(output.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("index\tpassed\tfullMs\ttools\tquestion\tanswer\ttoolSummary\terror");
        for (Result result : results) {
            lines.add(String.join("\t",
                    String.valueOf(result.index()),
                    String.valueOf(result.passed()),
                    String.valueOf(result.fullMs()),
                    String.join(",", result.actualTools()),
                    tsv(result.question()),
                    tsv(result.answer()),
                    tsv(result.toolSummary()),
                    tsv(result.error())));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    private static String tsv(String value) {
        return oneLine(value).replace('\t', ' ');
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private record QuestionCase(int index,
                                String question,
                                Set<String> expectedTools,
                                boolean requireToolNumber,
                                boolean forbidTools,
                                Set<String> answerMustContainAny) {
    }

    private record Result(int index,
                          String question,
                          long fullMs,
                          Set<String> actualTools,
                          String answer,
                          String toolSummary,
                          String error,
                          List<String> failures,
                          boolean passed) {
    }
}
