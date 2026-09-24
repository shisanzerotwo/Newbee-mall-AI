package ltd.newbee.mall.service.agent;

import dev.langchain4j.data.segment.TextSegment;
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
 * <h3>⚠️ 分母陷阱与「可满足子集」（本次修订新增，务必读）</h3>
 * 原始 10+10 条用例里，有相当一部分的期望关键词**在语料里根本不存在**
 * （商品库 575 条 = 276 台手机 + 256 未分类 + 40 口红 + 3 扫地机器人，
 * 没有面膜/防晒/眼霜/香水/精华/洗发水/洁面膏这类目）。
 * 拿这类用例当分母，等于**给分母灌水**：检索再准也不可能命中。
 * 因此本类同时给出两个指标，两份都如实记录、不互相取代：
 * <ol>
 *   <li><b>原始全量</b>：10+10 条一条不删，如实记录（这是历史口径，便于纵向对比）；</li>
 *   <li><b>可满足子集</b>：只保留「期望关键词在语料中至少出现一次」的用例
 *       —— 由 {@link #satisfiable} 在运行时**扫全量语料动态判定**，不是人工挑的。</li>
 * </ol>
 * 「可满足」是**必要不充分**条件：关键词在语料里存在，不代表检索一定能把它排进 Top-K。
 *
 * <p>需要真实 MySQL + 带 Query Engine 的 Redis（localhost:16379）。
 */
@SpringBootTest
class RagChineseQualityTest {

    /** Top-K：取多少条来判定命中。 */
    private static final int TOP_K = 3;

    @Resource
    private RagService ragService;

    @Resource
    private KnowledgeBuilder knowledgeBuilder;

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

    private List<String> topChunks(String question) {
        List<String> chunks = new ArrayList<>();
        for (RagService.Hit h : ragService.retrieve(question, TOP_K)) {
            chunks.add(h.chunk());  // Hit 的字段是 chunk（不是 text）—— 编译验证过
        }
        return chunks;
    }

    /** 全量语料小写拼接（用于判定「该类目在语料里到底有没有」）。 */
    private String corpusText() {
        StringBuilder sb = new StringBuilder();
        for (TextSegment s : knowledgeBuilder.lastSegments()) {
            sb.append(s.text()).append('\n');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 该用例的期望关键词是否**至少有一个**在语料中出现过。
     *
     * <p>这是「该查询客观上有无可能命中」的机械判据（必要不充分）——
     * 由运行时代码扫描语料得到，而不是人工标注，避免"挑对自己有利的用例"。
     */
    private static boolean satisfiable(String corpus, String[] keywords) {
        for (String kw : keywords) {
            if (corpus.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 单条用例的评测结果。 */
    private record CaseResult(String query, boolean hit, int hitChunks, boolean satisfiable) {}

    /** 跑一组用例（只做检索与判定，不打印）。 */
    private List<CaseResult> runCases(Object[][] cases, String corpus) {
        List<CaseResult> out = new ArrayList<>();
        for (Object[] c : cases) {
            String query = (String) c[0];
            String[] keywords = (String[]) c[1];
            int hitChunks = 0;
            for (String chunk : topChunks(query)) {
                String lower = chunk.toLowerCase(Locale.ROOT);
                for (String kw : keywords) {
                    if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                        hitChunks++;
                        break;
                    }
                }
            }
            out.add(new CaseResult(query, hitChunks > 0, hitChunks, satisfiable(corpus, keywords)));
        }
        return out;
    }

    private static int hitsOf(List<CaseResult> results) {
        return (int) results.stream().filter(CaseResult::hit).count();
    }

    /** 打印一组用例的明细与命中率（分子/分母都写出来，便于读者复算）。 */
    private void printGroup(String title, List<CaseResult> results, boolean showSatisfiable) {
        System.out.printf("%n===== %s（Top%d）=====%n", title, TOP_K);
        for (CaseResult r : results) {
            System.out.printf("  %-16s -> %-4s (%d/%d 条命中)%s%n",
                    r.query(), r.hit() ? "命中" : "未命中", r.hitChunks(), TOP_K,
                    showSatisfiable ? (r.satisfiable() ? "  [语料可满足]" : "  [语料无该类目]") : "");
        }
        int hit = hitsOf(results);
        System.out.printf("%s 命中率 = %d/%d = %.1f%%%n",
                title, hit, results.size(), hit * 100.0 / results.size());
        System.out.println("--------------------------------------------------");
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
     * <h3>⚠️ 就绪判据必须覆盖“被测依赖本身”（2026-09-24 修）</h3>
     * 本方法原先只看“检索能返回结果且连续 3 次稳定”。这个信号是**错的**，
     * 因为 {@code embedAndStore} 是**边嵌入边写入**：中途只要插进去 1 条，
     * {@code retrieve} 就会返回非空且数字不变 → 判定为“就绪”，
     * 而断言真正依赖的 {@link KnowledgeBuilder#lastSegments()}（**建库跑到末尾才赋值**）还是空的。
     * 后果：全部 20 条用例被当成“[语料无该类目]”，可满足子集变成 {@code 0/0 = NaN%}，
     * 报错话术却写成“语料没建起来”——**指向了错误的方向**。
     *
     * <p>CI 上真实踩到（本地因建库更快而侥幸绿）。因此现在要求**两个条件同时成立**：
     * <ol>
     *   <li>{@code lastSegments()} 非空 —— 建库已跑到末尾（这才是 {@link #corpusText()} 读的东西）；</li>
     *   <li>{@code retrieve} 结果非空且连续稳定 —— 向量索引侧也已可用。</li>
     * </ol>
     */
    private void awaitIndexReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
        int last = -1;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int seg = knowledgeBuilder.lastSegments().size();
            int n = ragService.retrieve("化妆水", TOP_K).size();
            if (seg > 0 && n > 0 && n == last) {
                if (++stable >= 3) {
                    return;
                }
            } else {
                stable = 0;
            }
            last = n;
            Thread.sleep(1000);
        }
        System.out.printf("[warn] 等待 RAG 就绪超过 3 分钟：内存片段 %d 个、检索命中 %d 条%n",
                knowledgeBuilder.lastSegments().size(), last);
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

        String corpus = corpusText();
        List<CaseResult> word = runCases(CASES, corpus);
        List<CaseResult> sem = runCases(SEMANTIC_CASES, corpus);

        // 口径一：原始全量（一条不删，如实记录 —— 历史口径，便于纵向对比）
        printGroup("同词查询（原始 10 条，如实记录）", word, true);
        printGroup("⭐ 语义查询（原始 10 条，如实记录）", sem, true);

        // 口径二：可满足子集（只含语料里真有的类目；运行时动态判定，非人工挑选）
        List<CaseResult> satisfiable = new ArrayList<>();
        word.stream().filter(CaseResult::satisfiable).forEach(satisfiable::add);
        sem.stream().filter(CaseResult::satisfiable).forEach(satisfiable::add);
        printGroup("可满足子集（期望关键词在语料中确实存在）", satisfiable, false);

        int wordHit = hitsOf(word);
        int semHit = hitsOf(sem);
        int satHit = hitsOf(satisfiable);
        System.out.printf("%n【小结·口径一｜原始全量】同词 %d/%d ｜ 语义 %d/%d ｜ 合计 %d/%d = %.1f%%%n",
                wordHit, CASES.length, semHit, SEMANTIC_CASES.length,
                wordHit + semHit, CASES.length + SEMANTIC_CASES.length,
                (wordHit + semHit) * 100.0 / (CASES.length + SEMANTIC_CASES.length));
        System.out.printf("【小结·口径二｜可满足子集】%d/%d = %.1f%%（其中同词 %d/%d、语义 %d/%d）%n",
                satHit, satisfiable.size(), satHit * 100.0 / satisfiable.size(),
                hitsOf(word.stream().filter(CaseResult::satisfiable).toList()),
                (int) word.stream().filter(CaseResult::satisfiable).count(),
                hitsOf(sem.stream().filter(CaseResult::satisfiable).toList()),
                (int) sem.stream().filter(CaseResult::satisfiable).count());
        System.out.println("（可满足子集的「可满足」= 期望关键词在语料中至少出现一次，是必要不充分条件："
                + "关键词存在也不代表一定排得进 Top-K）");

        // 不断言具体阈值（这是评测不是门禁）：只保证链路没整体失效。
        assertTrue(wordHit > 0 || semHit > 0,
                "两组都完全命中不了任何类目 —— 说明索引或检索链路坏了，而不只是模型弱");
        assertTrue(!satisfiable.isEmpty(),
                "可满足子集为空 —— 语料没建起来或商品库异常，本评测已失去意义");
    }
}
