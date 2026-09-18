package ltd.newbee.mall.service.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 中文检索质量评测（M3）。
 *
 * <h3>为什么需要它</h3>
 * M2-3 时就记过一条：当前用的 {@code all-MiniLM-L6-v2} 是**英文模型**，
 * 中文查询「化妆水」的 Top2 是无关项。但这只是**印象**，
 * 需要一个**可重复、可量化**的口径来判断「换中文嵌入模型到底有没有用」。
 *
 * <h3>两组用例，分工不同（这是本次实验的关键设计）</h3>
 * <ul>
 *   <li>{@link #CASES}「同词查询」：查询词与商品标题**共用同一批汉字**
 *       （问“化妆水”，商品也叫“化妆水”）。这组**关键词（2-gram）通路就能命中**，
 *       实测两个嵌入模型都 40%、**毫无差异** —— 所以它区分不出向量模型好坏。</li>
 *   <li>{@link #SEMANTIC_CASES}「语义查询」：用同义/口语化说法
 *       （不说“化妆水”而说“补水的护肤水”），关键词通路径本命中不了，
 *       **向量语义能力才能体现**。</li>
 * </ul>
 * 评测口径：看 Top-K 片段文本里是否出现该类目的期望关键词，统计命中率。
 * 口径粗，但**可复算、不随人“感觉”漂移**，足以支撑「换不换模型」的决策。
 *
 * <p>需要真实 MySQL + 带 Query Engine 的 Redis（localhost:16379）。
 */
@SpringBootTest
class RagChineseQualityTest {

    /** Top-K：取多少条来判定命中。 */
    private static final int TOP_K = 3;

    @Resource
    private RagService ragService;

    /** 同词查询（关键词通路即可命中；用于看整体链路是否正常，区分不出模型）。 */
    private static final Object[][] CASES = {
            {"化妆水", new String[]{"化妆水", "爽肤水", "水"}},
            {"面膜", new String[]{"面膜"}},
            {"口红", new String[]{"口红", "唇", "唇膏"}},
            {"洗发水", new String[]{"洗发", "洗发水"}},
            {"防晒", new String[]{"防晒"}},
            {"眼霜", new String[]{"眼霜", "眼部"}},
            {"香水", new String[]{"香水"}},
            {"洁面", new String[]{"洁面", "洗面"}},
            {"精华", new String[]{"精华"}},
            {"乳液", new String[]{"乳液"}},
    };

    /** 语义查询（同义/口语化；这组才区分得出嵌入模型）。 */
    private static final Object[][] SEMANTIC_CASES = {
            {"补水的护肤水", new String[]{"化妆水", "爽肤水", "水"}},
            {"洗完脸用来擦的", new String[]{"化妆水", "爽肤水", "乳液", "水"}},
            {"涂嘴唇的颜色", new String[]{"口红", "唇", "唇膏"}},
            {"洗头发用的", new String[]{"洗发", "洗发水"}},
            {"夏天防晒用的", new String[]{"防晒"}},
            {"眼睛周围的护肤品", new String[]{"眼霜", "眼部"}},
            {"身上香香的", new String[]{"香水"}},
            {"洗脸用的清洁产品", new String[]{"洁面", "洗面"}},
            {"浓度高的保养品", new String[]{"精华"}},
            {"保湿擦脸的", new String[]{"乳液", "面霜", "保湿"}},
    };

    private String searchText(String question) {
        // Hit 的字段是 chunk（不是 text）—— 编译验证过
        StringBuilder sb = new StringBuilder();
        for (RagService.Hit h : ragService.retrieve(question, TOP_K)) {
            sb.append(h.chunk()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 等知识库建完再评测。
     *
     * <p>⚠️ 不强调这一点就会得出<strong>错误结论</strong>：{@link KnowledgeBuilder} 是**异步建库**，
     * 测试若立即开始检索，命中的只是“建了一半”的索引 ——
     * 实测踩过：换 BGE 后直接测出 10%（比英文基线 40% 还差），一度以为中文模型不行，
     * 查 Redis 才发现 {@code num_docs=300}（应 588）、{@code hash_indexing_failures=50}，
     * 即**索引还没建完**。
     *
     * <p>用「连续多次检索到稳定且非空」作为就绪信号（不依赖 KnowledgeBuilder 内部状态，避免耦合）。
     */
    private void awaitIndexReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
        int last = -1;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int n = ragService.retrieve("化妆水", TOP_K).size();
            if (n > 0 && n == last) {
                if (++stable >= 3) {
                    return;
                }
            } else {
                stable = 0;
            }
            last = n;
            Thread.sleep(1000);
        }
        System.out.println("[warn] 等待索引就绪超过 3 分钟，仍继续评测（结果可能受未建完影响）");
    }

    /** 对一组用例统计命中率并打印明细。 */
    private int evaluate(String title, Object[][] cases) {
        int hit = 0;
        List<String> details = new ArrayList<>();
        for (Object[] c : cases) {
            String query = (String) c[0];
            String[] keywords = (String[]) c[1];
            String top = searchText(query).toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String kw : keywords) {
                if (top.contains(kw.toLowerCase(Locale.ROOT))) {
                    ok = true;
                    break;
                }
            }
            if (ok) {
                hit++;
            }
            details.add(String.format("  %-16s -> %s", query, ok ? "命中" : "未命中"));
        }
        System.out.printf("%n===== %s（Top%d）=====%n", title, TOP_K);
        details.forEach(System.out::println);
        System.out.printf("%s 命中率 = %d/%d = %.1f%%%n",
                title, hit, cases.length, hit * 100.0 / cases.length);
        System.out.println("--------------------------------------------------");
        return hit;
    }

    /**
     * 一次性跑两组用例（只等一次索引就绪）。
     *
     * <p>⚠️ 为什么不拆成两个 @Test：两个方法会**各自**调用 {@link #awaitIndexReady()}，
     * 而每次等待最多 3 分钟 —— 实测拆开跑会因累计等待而超时（375s，其中一个失败）。
     * 合并后只等一次，输出也集中在一起便于对比。
     */
    @Test
    @DisplayName("中文检索质量：同词查询 vs 语义查询（换嵌入模型前后的统一口径）")
    void chineseRetrievalQuality() throws InterruptedException {
        awaitIndexReady();

        int wordHit = evaluate("同词查询（关键词通路即可命中）", CASES);
        int semHit = evaluate("⭐ 语义查询（同义/口语化，这组才区分得出模型）", SEMANTIC_CASES);

        System.out.printf("%n【小结】同词 %d/%d ｜ 语义 %d/%d%n",
                wordHit, CASES.length, semHit, SEMANTIC_CASES.length);
        System.out.println("（同词命中率相近是预期的：混合检索的关键词通路已能命中；"
                + "要判断向量模型好坏，看语义那一组）");

        // 不断言具体阈值（这是评测不是门禁）：只保证链路没整体失效。
        assertTrue(wordHit > 0 || semHit > 0,
                "两组都完全命中不了任何类目 —— 说明索引或检索链路坏了，而不只是模型弱");
    }
}
