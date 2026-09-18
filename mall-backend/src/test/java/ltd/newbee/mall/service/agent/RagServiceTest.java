package ltd.newbee.mall.service.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-3 验收测试：混合检索（关键词 2-gram + 向量 → RRF）。
 *
 * <p>需要真实 MySQL + 带 Query Engine 的 Redis（localhost:16379）。
 */
@SpringBootTest
class RagServiceTest {

    @Resource
    private RagService ragService;

    @Resource
    private KnowledgeBuilder knowledgeBuilder;

    /** 中文查询应能通过"关键词 + 向量"两条路都命中 */
    @Test
    void retrieveShouldReturnHitsForChineseQuery() {
        knowledgeBuilder.buildIndex(false);

        List<RagService.Hit> hits = ragService.retrieve("保湿", 3);

        assertFalse(hits.isEmpty(), "「保湿」应能检索到结果（关键词或向量至少一路命中）");
        print("保湿", hits);

        for (RagService.Hit hit : hits) {
            assertNotNull(hit.chunk(), "命中片段不应为 null");
            assertTrue(hit.score() > 0, "RRF 融合分应为正数");
        }
    }

    /** 用商品名里的词检索（关键词侧应强命中） */
    @Test
    void retrieveShouldHitByProductNameKeyword() {
        List<RagService.Hit> hits = ragService.retrieve("化妆水", 3);
        assertFalse(hits.isEmpty(), "「化妆水」应能检索到结果");
        print("化妆水", hits);
    }

    /** 检索结果转 prompt 片段：应带「仅供参考」措辞，且**不含价格/库存** */
    @Test
    void asPromptShouldBeAdvisoryAndFreeOfPrice() {
        knowledgeBuilder.buildIndex(false);

        String prompt = ragService.asPrompt("保湿", 3);

        assertFalse(prompt.isBlank(), "有命中时 prompt 片段不应为空");
        assertTrue(prompt.contains("仅供参考"), "应显式声明仅供参考，实际：" + prompt);
        assertFalse(prompt.contains("价格："), "RAG 片段不得含价格（语料红线），实际：" + prompt);
        assertFalse(prompt.contains("库存："), "RAG 片段不得含库存（语料红线），实际：" + prompt);
    }

    /** 空查询直接返回空（不触发任何外部调用） */
    @Test
    void blankQueryShouldReturnEmpty() {
        assertTrue(ragService.retrieve("").isEmpty(), "空查询应返回空列表");
        assertTrue(ragService.retrieve("   ").isEmpty(), "空白查询应返回空列表");
    }

    // ------------------------------------------------------------------
    // 纯函数：RRF 融合（不依赖外部环境）
    // ------------------------------------------------------------------

    /** 同一片段被两路都命中时，融合分应高于只被一路命中的片段 */
    @Test
    void rrfFuseShouldRankDoubleMatchedHitHigher() {
        RagService.Hit both = new RagService.Hit("1", "被两路命中", 0, "chunk-both");
        RagService.Hit keywordOnly = new RagService.Hit("2", "仅关键词命中", 0, "chunk-kw");
        RagService.Hit vectorOnly = new RagService.Hit("3", "仅向量命中", 0, "chunk-vec");

        // 关键词侧：both 第 0 名，keywordOnly 第 1 名
        // 向量侧：both 第 1 名（与关键词侧顺序不同，模拟真实场景），vectorOnly 第 0 名
        List<RagService.Hit> fused = RagService.rrfFuse(
                List.of(both, keywordOnly),
                List.of(vectorOnly, both),
                3);

        assertEquals(3, fused.size(), "三个不同片段应都出现在结果里");
        assertEquals("被两路命中", fused.get(0).title(),
                "两路都命中的片段应排第一（RRF 累加），实际顺序：" + fused.stream()
                        .map(RagService.Hit::title).toList());
    }

    /** 融合后分数应为各路的 1/(60+rank) 之和 */
    @Test
    void rrfFuseShouldSumReciprocalRanks() {
        RagService.Hit hit = new RagService.Hit("1", "t", 0, "c");
        List<RagService.Hit> fused = RagService.rrfFuse(List.of(hit), List.of(hit), 1);

        double expected = 1.0 / (RagService.RRF_K + 0) + 1.0 / (RagService.RRF_K + 0);
        assertEquals(expected, fused.get(0).score(), 1e-9,
                "同一片段两路均第 0 名时，融合分应为 2/(60+0)");
    }

    /** 两路都为空时返回空，不抛异常 */
    @Test
    void rrfFuseShouldHandleEmptyInputs() {
        assertTrue(RagService.rrfFuse(List.of(), List.of(), 3).isEmpty());
        assertTrue(RagService.rrfFuse(List.of(), List.of(), 0).isEmpty());
    }

    /** 2-gram 切分（关键词检索的基础） */
    @Test
    void ngramsShouldNormalizeWhitespaceAndCase() {
        assertTrue(RagService.ngrams("AB cd").contains("ab"), "应小写化并去空白");
        assertTrue(RagService.ngrams("保湿").contains("保湿"), "中文应切成 2-gram");
        assertTrue(RagService.ngrams(null).isEmpty(), "null 应返回空集合");
    }

    /**
     * 打印检索样例。
     *
     * <p>除控制台外<b>额外写入</b> {@code target/rag-retrieval-demo.txt}：
     * Surefire 默认会把测试的 stdout 收走（本仓库实测控制台看不到），
     * 写文件才能事后核对检索质量。{@code target/} 已在 .gitignore 内，不会入库。
     */
    private static void print(String query, List<RagService.Hit> hits) {
        StringBuilder sb = new StringBuilder("=== RAG 检索「" + query + "」===\n");
        int i = 1;
        for (RagService.Hit hit : hits) {
            String flat = hit.chunk() == null ? "" : hit.chunk().replaceAll("\\s+", " ");
            sb.append(String.format("  [%d] %s (goodsId=%s, 融合分=%.4f)%n",
                    i++, hit.title(), hit.goodsId(), hit.score()));
            sb.append("      ").append(flat.length() > 80 ? flat.substring(0, 80) + "…" : flat).append('\n');
        }
        System.out.println(sb);
        try {
            Files.writeString(Path.of("target", "rag-retrieval-demo.txt"), sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 只是诊断输出，失败不影响测试结论
            System.out.println("[warn] 写检索样例文件失败：" + e);
        }
    }
}
