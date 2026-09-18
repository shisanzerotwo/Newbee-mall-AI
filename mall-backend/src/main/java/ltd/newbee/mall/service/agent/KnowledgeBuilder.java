package ltd.newbee.mall.service.agent;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import ltd.newbee.mall.dao.GoodsCategoryMapper;
import ltd.newbee.mall.dao.NewBeeMallGoodsMapper;
import ltd.newbee.mall.entity.GoodsCategory;
import ltd.newbee.mall.entity.NewBeeMallGoods;
import ltd.newbee.mall.util.PageQueryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识库构建（M2-3）：MySQL 商品 → 检索文本 → 分块 → 嵌入 → Redis 向量库。
 *
 * <h3>⚠️ 语料红线（本项目最重要的设计约束之一）</h3>
 * 写入向量库的 chunk <b>不得包含价格、库存、上下架状态</b>。
 *
 * <p>理由：Python 教学版的 609 个片段中有 <b>575 段</b>正文写死了「价格：X 元 / 库存：N 件」
 * （模板见 {@code 12_agent_cs/kb_build.py}），注入 prompt 时会与工具查询结果<b>冲突</b>。
 * 本项目的「数据铁律」是：<b>价格 / 库存 / 订单状态一律以工具查询为准，RAG 只作语义参考</b>。
 *
 * <p>因此 {@link #toSearchText} 只组装稳定语义字段（名称 / 分类 / 标签 / 简介 / 详情），
 * <b>明确排除</b> {@code sellingPrice}、{@code originalPrice}、{@code stockNum}、
 * {@code goodsSellStatus}。测试用 0 命中断言来守这条红线。
 *
 * <h3>重建方式</h3>
 * {@code RedisEmbeddingStore} 只暴露 indexName、<b>没有别名 API</b>，做不到"双写 + 原子切换"。
 * 因此重建走 {@code store.removeAll()} 后重来；本模块的规模（575 商品）重建只需秒级。
 */
@Component
public class KnowledgeBuilder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBuilder.class);

    /** 商品详情的截断长度（对齐 Python 版 _clean_html 的 max_len=300） */
    private static final int DETAIL_MAX = 300;

    /** 单次嵌入/写入的批大小（避免一次性构造过大的请求） */
    private static final int BATCH_SIZE = 50;

    /** 分块后丢弃过短的片段（对齐 Python 版 "len(c.strip()) > 20"） */
    private static final int MIN_CHUNK_LENGTH = 20;

    /** 一次能取回的商品/分类上限（当前库：575 商品 / 92 分类） */
    private static final int FETCH_LIMIT = 2000;

    @Resource
    private NewBeeMallGoodsMapper goodsMapper;

    @Resource
    private GoodsCategoryMapper categoryMapper;

    /**
     * Redis 连接：用 {@link ObjectProvider} 而不是直接注入，
     * 否则 {@code @Lazy} 的 Jedis Bean 会在本类实例化时就被提前创建（破坏懒加载）。
     * 这里只在探测索引状态时才取用。
     */
    @Resource
    private ObjectProvider<UnifiedJedis> ragJedisProvider;

    @Value("${cs.rag.index-name:goods_kb}")
    private String indexName;

    private final EmbeddingModel embeddingModel;

    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${cs.rag.enabled:true}")
    private boolean enabled;

    @Value("${cs.rag.chunk-size:400}")
    private int chunkSize;

    @Value("${cs.rag.chunk-overlap:80}")
    private int chunkOverlap;

    /** 最近一次建库结果（供测试与诊断读取） */
    private volatile BuildResult lastResult = new BuildResult(0, 0, 0L, "尚未构建");

    /**
     * 最近一次建库写入的全部片段（内存保留）。
     *
     * <p>用途：{@link RagService} 的<b>关键词侧检索</b>需要遍历全部 chunk 算 2-gram 命中率
     * （对齐 Python 版做法）。这些内容在 Redis 侧只能靠向量检索取出，无法全量遍历，
     * 所以在内存里留一份。规模很小（~600 片段 × 400 字符 ≈ 250KB），可接受。
     */
    private volatile List<TextSegment> lastSegments = List.of();

    /**
     * 构造注入；{@code @Lazy} 保证嵌入模型（首次需下载 ~90MB ONNX）不会在启动期实例化，
     * 而是在首次真正建库/检索时才加载。
     */
    public KnowledgeBuilder(@Lazy EmbeddingModel embeddingModel,
                            @Lazy EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 启动完成后异步建库（不阻塞启动；失败只记日志，不影响商城功能） */
    @EventListener(ApplicationReadyEvent.class)
    public void buildOnStartup() {
        if (!enabled) {
            log.info("RAG 已禁用（cs.rag.enabled=false），跳过启动建库");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                buildIndex(false);
            } catch (Exception e) {
                // 显式记录：不静默降级（本项目在 M1/M2 反复踩过"静默失败"的坑）
                log.error("启动建库失败（RAG 将降级为『仅工具、无 RAG』）：{}", e.toString(), e);
            }
        }, "rag-knowledge-builder");
        t.setDaemon(true);
        t.start();
    }

    /** 每日 03:00 全量重建（商品改价/上下架后最多延迟一天生效；价格类问题仍以工具为准） */
    @Scheduled(cron = "0 0 3 * * ?")
    public void rebuildDaily() {
        if (!enabled) {
            return;
        }
        try {
            log.info("定时任务：开始每日重建 RAG 知识库");
            buildIndex(true);
        } catch (Exception e) {
            log.error("每日重建 RAG 知识库失败：{}", e.toString(), e);
        }
    }

    /**
     * 构建（或重建）知识库。
     *
     * @param force true = 先清空索引再重建；false = 索引已有内容时跳过
     * @return 构建结果
     */
    public synchronized BuildResult buildIndex(boolean force) {
        long start = System.currentTimeMillis();

        if (!force && isIndexReady()) {
            // Redis 里已有索引 -> 不重复嵌入/写入。但**内存片段仍需组装**：
            // RagService 的关键词侧检索要遍历全部片段，不组装就会退化成纯向量检索。
            lastSegments = List.copyOf(assembleSegments());
            long cost = System.currentTimeMillis() - start;
            log.info("复用已有 RAG 索引（{} 个片段），仅重建内存片段，耗时 {}ms",
                    lastSegments.size(), cost);
            return lastResult;
        }

        List<TextSegment> segments = assembleSegments();

        // 写入前必须清空：本方法写的是【全量】片段，若索引里已有旧数据，
        // 直接 addAll 会与之累积 —— 实测连续两次建库把 588 个片段变成 1176 个，
        // 后果是检索结果重复、RRF 合并错乱。
        // 该 store 无别名 API，做不到“双写 + 原子切换”，故这里接受一次清空窗口。
        embeddingStore.removeAll();

        int written = embedAndStore(segments);
        // 保留片段副本供关键词检索（先赋值副本，避免检索方看到未完成的 List）
        lastSegments = List.copyOf(segments);
        long cost = System.currentTimeMillis() - start;
        String info = String.format("商品 %d 个 → 片段 %d 个，耗时 %.1fs",
                countDistinctGoods(segments), written, cost / 1000.0);
        lastResult = new BuildResult(countDistinctGoods(segments), written, cost, info);
        log.info("RAG 知识库构建完成：{}", info);
        return lastResult;
    }

    /** 最近一次建库结果 */
    public BuildResult lastResult() {
        return lastResult;
    }

    /** 最近一次建库写入的全部片段（关键词检索用；未建库时为空列表） */
    public List<TextSegment> lastSegments() {
        return lastSegments;
    }

    // ------------------------------------------------------------------
    // 组装与分块（纯函数，便于单测）
    // ------------------------------------------------------------------

    /**
     * 商品 → 检索文本。
     *
     * <p><b>刻意不含</b>：价格（{@code sellingPrice}/{@code originalPrice}）、
     * 库存（{@code stockNum}）、上下架（{@code goodsSellStatus}）——
     * 这三类是"会变的数据"，必须走工具查询，否则会把旧值带进 prompt。
     *
     * <p>对照 Python 版模板（{@code kb_build.py}）可以看出差异：那边有
     * 「价格：X 元」「库存：N 件」两行，这里<b>被刻意删掉</b>。
     */
    static String toSearchText(NewBeeMallGoods goods, String categoryName, String detail) {
        return "商品名称：" + nullToDash(goods.getGoodsName()) + "\n"
                + "分类：" + nullToDash(categoryName) + "\n"
                + "标签：" + nullToDash(goods.getTag()) + "\n"
                + "简介：" + nullToDash(goods.getGoodsIntro()) + "\n"
                + "详情：" + nullToDash(detail);
    }

    /** 固定窗口滑动分块（对齐 Python 版 {@code chunk_text}：size=400 / overlap=80） */
    static List<String> chunkText(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        String normalized = text.replaceAll("\n{3,}", "\n\n");
        int step = Math.max(1, size - overlap);
        for (int start = 0; start < normalized.length(); start += step) {
            chunks.add(normalized.substring(start, Math.min(start + size, normalized.length())));
        }
        chunks.removeIf(c -> c.trim().length() <= MIN_CHUNK_LENGTH);
        return chunks;
    }

    /** HTML → 纯文本并截断（对齐 Python 版 {@code _clean_html}） */
    static String cleanHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return text.length() > DETAIL_MAX ? text.substring(0, DETAIL_MAX) : text;
    }

    // ------------------------------------------------------------------
    // 内部：取数 / 组装 / 嵌入 / 写入
    // ------------------------------------------------------------------

    /**
     * 组装全部片段（读 MySQL → 清洗 → 合成文本 → 分块），<b>不涉及嵌入与 Redis</b>。
     *
     * <p>单独抽出来是为了幂等路径复用：索引已在 Redis 时，只需把片段装回内存
     * （关键词检索依赖），不必重复嵌入与写入。
     */
    private List<TextSegment> assembleSegments() {
        List<NewBeeMallGoods> goodsList = fetchAllGoods();
        if (goodsList.isEmpty()) {
            throw new IllegalStateException("未读取到任何商品，可能是数据库为空或连接异常");
        }
        Map<Long, String> categoryNames = fetchCategoryNames();

        List<TextSegment> segments = new ArrayList<>();
        for (NewBeeMallGoods goods : goodsList) {
            String detail = cleanHtml(fullDetailOf(goods.getGoodsId()));
            // 语料红线：toSearchText 不含价格/库存/上下架
            String text = toSearchText(goods, categoryNames.get(goods.getGoodsCategoryId()), detail);
            Metadata metadata = new Metadata()
                    .put("goodsId", String.valueOf(goods.getGoodsId()))
                    .put("title", goods.getGoodsName() == null ? "" : goods.getGoodsName());
            for (String chunk : chunkText(text, chunkSize, chunkOverlap)) {
                segments.add(new TextSegment(chunk, metadata));
            }
        }
        return segments;
    }

    /** 片段里涉及多少个不同商品（用于日志） */
    private static int countDistinctGoods(List<TextSegment> segments) {
        return (int) segments.stream()
                .map(s -> s.metadata().getString("goodsId"))
                .distinct()
                .count();
    }

    /**
     * 索引是否已就绪。
     *
     * <p><b>必须查 Redis 而不是只看进程内状态</b>：早期版本只判断 {@code lastResult.chunkCount()>0}，
     * 那是进程内变量 —— 每次重启应用都会归零，导致<b>每次启动都白重建一次</b>（实测 6s + 588 次写入）。
     * 现在用 {@code FT.INFO <index>} 的 {@code num_docs} 判断，跨进程有效、幂等。
     */
    private boolean isIndexReady() {
        try {
            Map<String, Object> info = ragJedisProvider.getObject().ftInfo(indexName);
            Object docs = info == null ? null : info.get("num_docs");
            if (docs == null) {
                return false;
            }
            int count = Integer.parseInt(docs.toString());
            if (count > 0) {
                lastResult = new BuildResult(0, count, 0L, "复用已有索引，跳过重建（片段 " + count + "）");
                return true;
            }
            return false;
        } catch (Exception e) {
            // 索引不存在或 Redis 不可用 —— 都应走构建流程；这里用 debug 级别，避免刷日志
            log.debug("RAG 索引探测未命中（将执行构建）：{}", e.toString());
            return false;
        }
    }

    private List<NewBeeMallGoods> fetchAllGoods() {
        Map<String, Object> params = new HashMap<>();
        params.put("page", 1);
        params.put("limit", FETCH_LIMIT);
        List<NewBeeMallGoods> list = goodsMapper.findNewBeeMallGoodsList(new PageQueryUtil(params));
        // 防静默截断：取满 limit 说明可能还有商品未进入知识库
        //（本项目反复强调“不要让失败静默”：M1/M2 已踩过 DB 失联页面 200、尾斜杠错误页 200 等）
        if (list.size() >= FETCH_LIMIT) {
            log.warn("商品数达到 FETCH_LIMIT={}，可能存在静默截断（超出部分不会进入知识库）；"
                    + "如商品增长，请调大该常量或改为分页遍历", FETCH_LIMIT);
        }
        return list;
    }

    private Map<Long, String> fetchCategoryNames() {
        Map<String, Object> params = new HashMap<>();
        params.put("page", 1);
        params.put("limit", FETCH_LIMIT);
        List<GoodsCategory> categories = categoryMapper.findGoodsCategoryList(new PageQueryUtil(params));
        if (categories.size() >= FETCH_LIMIT) {
            log.warn("分类数达到 FETCH_LIMIT={}，可能存在静默截断", FETCH_LIMIT);
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (GoodsCategory c : categories) {
            names.put(c.getCategoryId(), c.getCategoryName());
        }
        return names;
    }

    /**
     * 取商品详情（BLOB 字段）。
     *
     * <p>列表查询 {@code findNewBeeMallGoodsList} 用的 {@code Base_Column_List} <b>不含</b>
     * {@code goods_detail_content}，所以详情要逐条用 {@code selectByPrimaryKey} 取。
     * 575 次单查约 1 秒，一次性建库可接受。
     */
    private String fullDetailOf(Long goodsId) {
        NewBeeMallGoods full = goodsMapper.selectByPrimaryKey(goodsId);
        return full == null ? "" : full.getGoodsDetailContent();
    }

    /** 分批嵌入并写入向量库，返回成功写入的片段数 */
    private int embedAndStore(List<TextSegment> segments) {
        int written = 0;
        for (int i = 0; i < segments.size(); i += BATCH_SIZE) {
            List<TextSegment> batch = segments.subList(i, Math.min(i + BATCH_SIZE, segments.size()));
            List<Embedding> embeddings = embeddingModel.embed(
                    EmbeddingRequest.builder().textSegments(batch).build()).embeddings();
            if (embeddings.size() != batch.size()) {
                throw new IllegalStateException(
                        "嵌入数量与片段数量不一致：" + embeddings.size() + " vs " + batch.size());
            }
            embeddingStore.addAll(embeddings, batch);
            written += batch.size();
        }
        return written;
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "无" : s;
    }

    /** 建库结果 */
    public record BuildResult(int goodsCount, int chunkCount, long costMillis, String summary) {
    }
}
