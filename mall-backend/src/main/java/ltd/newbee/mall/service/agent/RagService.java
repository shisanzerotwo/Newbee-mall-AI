package ltd.newbee.mall.service.agent;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 检索（M2-3）：<b>混合检索</b> = 关键词 2-gram 命中率 + 向量语义相似 → RRF 融合。
 *
 * <h3>为什么用混合检索</h3>
 * 单一方式各有明显短板（Python 版实测结论，本项目沿用）：
 * <ul>
 *   <li><b>纯关键词</b>：专名查询准（「无印良品化妆水」），但同义词失效（「保湿」搜不到「滋润」）</li>
 *   <li><b>纯向量</b>：语义泛化好，但用的是英文预训练模型 all-MiniLM-L6-v2，
 *       对中文<b>专名</b>命中不如字面匹配</li>
 * </ul>
 * RRF（Reciprocal Rank Fusion，{@code score = Σ 1/(60 + rank)}）只看<b>排名</b>不看分数，
 * 无需归一化两个异构打分，是工程上最省事且稳的融合方式。
 *
 * <h3>降级行为</h3>
 * 任何异常（Redis 连不上、嵌入模型未就绪、索引为空）都<b>不抛给调用方</b>，
 * 而是返回空列表 + 打 WARN —— 客服链路因此能平滑降级为「仅工具、无 RAG」。
 * 注意这是<b>有日志</b>的降级，不是静默吞异常。
 */
@Component
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** RRF 平滑常数（论文惯例取 60） */
    static final int RRF_K = 60;

    /** 2-gram 的 n */
    private static final int NGRAM_N = 2;

    /**
     * 关键词侧候选数。默认 10 = 历史行为（做成 {@code @Value} 是为了让调参可 A/B，
     * 评测换参数不必改代码，见 {@code docs/RAG-EVAL.md}）。
     */
    @Value("${cs.rag.keyword-candidates:10}")
    private int keywordCandidates = 10;

    /** 向量侧候选数。默认 0 = 沿用历史行为 {@code max(topK * 2, 5)}；&gt;0 时取该绝对值。 */
    @Value("${cs.rag.vector-candidates:0}")
    private int vectorCandidates = 0;

    /** RRF 中关键词侧权重，默认 1.0 = 与向量侧等权（历史行为）。越小越偏向语义召回。 */
    @Value("${cs.rag.keyword-weight:1.0}")
    private double keywordWeight = 1.0;

    private final EmbeddingModel embeddingModel;

    private final EmbeddingStore<TextSegment> embeddingStore;

    private final KnowledgeBuilder knowledgeBuilder;

    @Value("${cs.rag.top-k:3}")
    private int defaultTopK;

    public RagService(@Lazy EmbeddingModel embeddingModel,
                      @Lazy EmbeddingStore<TextSegment> embeddingStore,
                      KnowledgeBuilder knowledgeBuilder) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.knowledgeBuilder = knowledgeBuilder;
    }

    /** 用默认 topK 检索 */
    public List<Hit> retrieve(String query) {
        return retrieve(query, defaultTopK);
    }

    /**
     * 混合检索：返回按 RRF 融合分排序的 Top-K 片段。
     *
     * <p>失败时返回空列表（调用方据此降级为无 RAG），不会抛异常。
     */
    public List<Hit> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int k = Math.max(1, topK);
        try {
            int vecLimit = vectorCandidates > 0 ? vectorCandidates : Math.max(k * 2, 5);
            List<Hit> keywordHits = keywordSearch(query, keywordCandidates);
            List<Hit> vectorHits = vectorSearch(query, vecLimit);
            List<Hit> fused = rrfFuse(keywordHits, vectorHits, k, keywordWeight);
            if (log.isDebugEnabled()) {
                log.debug("RAG 检索「{}」：关键词 {} 条 / 向量 {} 条 → 融合 {} 条",
                        query, keywordHits.size(), vectorHits.size(), fused.size());
            }
            return fused;
        } catch (Exception e) {
            // 显式 WARN：降级可见，不静默
            log.warn("RAG 检索失败，本次降级为『仅工具、无 RAG』：{}", e.toString());
            return List.of();
        }
    }

    /**
     * 检索结果 → prompt 片段（供 M2-4 的编排层注入上下文）。
     *
     * <p>返回值刻意带上「仅供参考」的措辞：RAG 内容里<b>没有</b>价格/库存/上下架
     * （见 {@link KnowledgeBuilder} 的语料红线），价格类事实必须由工具查询给出。
     */
    public String asPrompt(String query, int topK) {
        return asPromptFrom(retrieve(query, topK));
    }

    /**
     * 只做格式化（把「检索」与「格式化」拆开）。
     *
     * <p>存在的意义：调用方（{@code CsStreamService}）既需要 prompt、又需要原始的
     * {@link Hit} 列表去做 SSE 的 {@code rag} 事件（前端「引用来源」区块要展示融合分）。
     * 拆出本方法后两边共用<b>同一次</b>检索结果，不做二次嵌入/二次查询。
     */
    public String asPromptFrom(List<Hit> hits) {
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【商品知识库检索·混合检索】可能与用户问题相关的商品（仅供参考，价格/库存以工具查询为准）：");
        int i = 1;
        for (Hit h : hits) {
            sb.append("\n[").append(i++).append("] ").append(h.title())
                    .append("（goodsId=").append(h.goodsId())
                    .append("，融合分 ").append(String.format("%.4f", h.score())).append("）\n")
                    .append(preview(h.chunk(), 200));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 关键词侧：2-gram 命中率
    // ------------------------------------------------------------------

    /**
     * 关键词检索：遍历内存中的全部片段，按 2-gram 集合的命中率排序。
     *
     * <p>片段来源是 {@link KnowledgeBuilder#lastSegments()} —— 向量库里取不回全量文本，
     * 所以建库时在内存留了一份（规模很小）。
     */
    List<Hit> keywordSearch(String query, int limit) {
        List<TextSegment> segments = knowledgeBuilder.lastSegments();
        if (segments.isEmpty()) {
            return List.of();
        }
        Set<String> queryGrams = ngrams(query);
        if (queryGrams.isEmpty()) {
            return List.of();
        }
        List<Hit> scored = new ArrayList<>();
        for (TextSegment segment : segments) {
            int hit = 0;
            Set<String> segmentGrams = ngrams(segment.text());
            for (String gram : queryGrams) {
                if (segmentGrams.contains(gram)) {
                    hit++;
                }
            }
            if (hit > 0) {
                scored.add(new Hit(meta(segment, "goodsId"), meta(segment, "title"),
                        (double) hit / queryGrams.size(), segment.text()));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    /** 文本 → 2-gram 集合（中文无空格分词的最简近似） */
    static Set<String> ngrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null) {
            return grams;
        }
        String normalized = text.replaceAll("\\s+", "").toLowerCase();
        for (int i = 0; i + NGRAM_N <= normalized.length(); i++) {
            grams.add(normalized.substring(i, i + NGRAM_N));
        }
        return grams;
    }

    // ------------------------------------------------------------------
    // 向量侧：语义相似
    // ------------------------------------------------------------------

    List<Hit> vectorSearch(String query, int limit) {
        List<Embedding> embeddings = embeddingModel.embed(
                EmbeddingRequest.builder()
                        .textSegment(new TextSegment(query, new Metadata()))
                        .build()).embeddings();
        if (embeddings.isEmpty()) {
            return List.of();
        }
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(embeddings.get(0))
                        .maxResults(limit)
                        .build());
        List<Hit> hits = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            hits.add(new Hit(meta(segment, "goodsId"), meta(segment, "title"),
                    match.score() == null ? 0.0 : match.score(), segment.text()));
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // 融合
    // ------------------------------------------------------------------

    /**
     * RRF 融合：{@code score = Σ 1/(RRF_K + rank)}。
     *
     * <p>同一片段在关键词侧与向量侧可能同时出现 —— 用 {@code goodsId|chunk前50字符}
     * 作为身份键合并（对齐 Python 版做法），两路都排得靠前的片段自然得分更高。
     */
    static List<Hit> rrfFuse(List<Hit> keywordHits, List<Hit> vectorHits, int topK) {
        return rrfFuse(keywordHits, vectorHits, topK, 1.0);
    }

    /**
     * 带权重的 RRF 融合：关键词侧的分贡献乘 {@code keywordWeight}（向量侧恒为 1.0）。
     *
     * <p>{@code weight = 1.0} 时与三参重载完全等价（阴性对照要能退回旧行为）。
     */
    static List<Hit> rrfFuse(List<Hit> keywordHits, List<Hit> vectorHits, int topK, double keywordWeight) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Hit> byKey = new LinkedHashMap<>();

        accumulate(rrfScores, byKey, keywordHits, keywordWeight);
        accumulate(rrfScores, byKey, vectorHits, 1.0);

        return rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, topK))
                .map(e -> byKey.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }

    private static void accumulate(Map<String, Double> rrfScores, Map<String, Hit> byKey,
                                   List<Hit> hits, double weight) {
        for (int rank = 0; rank < hits.size(); rank++) {
            Hit hit = hits.get(rank);
            String key = hit.identity();
            rrfScores.merge(key, weight / (RRF_K + rank), Double::sum);
            byKey.putIfAbsent(key, hit);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String meta(TextSegment segment, String key) {
        if (segment == null || segment.metadata() == null) {
            return "";
        }
        String value = segment.metadata().getString(key);
        return value == null ? "" : value;
    }

    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    /** 一条检索结果 */
    public record Hit(String goodsId, String title, double score, String chunk) {

        /**
         * 融合用的身份键。
         *
         * <p><b>必须用完整 chunk</b>：早期版本用 {@code chunk 前 50 字符}，
         * 但本项目的片段几乎都以「商品名称：…分类：…标签：…」开头，前 50 字符高度重叠，
         * 导致<b>不同片段被错误合并</b>（融合分虚高、内容串条）。用完整文本可精确区分。
         */
        String identity() {
            return goodsId + "|" + chunk;
        }

        /** record 不可变，融合后需要换分数 —— 返回新实例 */
        Hit withScore(double newScore) {
            return new Hit(goodsId, title, newScore, chunk);
        }
    }
}
