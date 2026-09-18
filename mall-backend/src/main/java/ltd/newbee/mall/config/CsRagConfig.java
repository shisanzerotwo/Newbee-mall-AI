package ltd.newbee.mall.config;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import redis.clients.jedis.UnifiedJedis;

import java.util.List;

/**
 * RAG（M2-3）的 LangChain4j 装配。
 *
 * <p>三个 Bean：Jedis 连接、向量库（{@link RedisEmbeddingStore}）、嵌入模型。
 *
 * <p><b>为什么连 16379 而不是 6379</b>：向量检索依赖 RediSearch / {@code FT.*} 命令，
 * 而本机 6379 上跑的是 Redis 3.0.504（无 Query Engine）。项目用官方
 * {@code redis:8}（8.10.1，Query Engine 已并入核心），经 docker-compose 映射到宿主
 * <b>16379</b>（避开被占用的 6379）。已实测 {@code FT.CREATE ... VECTOR HNSW} 可用。
 *
 * <p><b>为什么 {@code @Lazy}</b>：{@link AllMiniLmL6V2EmbeddingModel} 首次初始化会
 * 下载内置 ONNX 模型（约 90MB），若在启动期实例化会拖慢/阻塞启动。加 {@code @Lazy}
 * 后模型在首次真正用到（建库或检索）时才加载。
 *
 * <p><b>已知限制</b>：{@link RedisEmbeddingStore} 只暴露 {@code indexName}，<b>没有别名 API</b>，
 * 因此做不到"双写新索引 + 原子切换"；重建只能 {@code FT.DROPINDEX ... DD} 后重来
 * （见 DESIGN §7.1）。
 */
@Configuration
public class CsRagConfig {

    private static final Logger log = LoggerFactory.getLogger(CsRagConfig.class);

    /** 内置 ONNX 模型 all-MiniLM-L6-v2 的维度（英文模型，中文弱） */
    public static final int EMBEDDING_DIMENSION_MINILM = 384;

    /** 中文嵌入模型 bge-small-zh-v15 的维度 */
    public static final int EMBEDDING_DIMENSION_BGE_ZH = 512;

    /**
     * 兼容旧引用：默认维度（= MiniLM）。
     *
     * <p>实际维度由 {@code cs.rag.embedding-model} 决定（见 {@link #currentDimension()}）——
     * 因为两个模型的维度不同（384 vs 512），**换模型必须同时重建索引**，
     * 否则 Redis 侧维度不匹配。
     */
    public static final int EMBEDDING_DIMENSION = EMBEDDING_DIMENSION_MINILM;

    @Value("${cs.rag.redis-host:localhost}")
    private String host;

    @Value("${cs.rag.redis-port:16379}")
    private int port;

    @Value("${cs.rag.index-name:goods_kb}")
    private String indexName;

    /**
     * 嵌入模型选择：{@code bge-zh}（**默认**，中文 512 维）或 {@code minilm}（英文 384 维）。
     *
     * <p>做成可切换是为了能做 A/B 对比（{@code RagChineseQualityTest} 会打印 Top3 关键词命中率）：
     * 实测 all-MiniLM 中文命中率 40% —— 而它是**英文模型**，中文语义区分度本来就差。
     *
     * <p>⚠️ 两个模型维度不同（384 / 512），**切换后必须重建索引**
     * （删掉 Redis 里的 {@code goods_kb} 索引，让 {@link KnowledgeBuilder} 重建），
     * 否则向量维度不匹配会直接报错。
     */
    @Value("${cs.rag.embedding-model:bge-zh}")
    private String embeddingModelName;

    /** 当前选中的嵌入维度 —— 与 {@link #goodsEmbeddingModel()} 保持同一个判据。 */
    public int currentDimension() {
        return isBgeZh() ? EMBEDDING_DIMENSION_BGE_ZH : EMBEDDING_DIMENSION_MINILM;
    }

    private boolean isBgeZh() {
        return "bge-zh".equalsIgnoreCase(embeddingModelName);
    }

    @Bean(destroyMethod = "close")
    @Lazy
    public UnifiedJedis ragJedis() {
        String uri = "redis://" + host + ":" + port;
        log.info("RAG 连接 Redis 向量库：{}（需要带 Query Engine 的 Redis 8）", uri);
        return new UnifiedJedis(uri);
    }

    @Bean
    @Lazy
    public EmbeddingModel goodsEmbeddingModel() {
        if (isBgeZh()) {
            log.info("加载内置 ONNX 嵌入模型 bge-small-zh-v15（中文，512 维；首次需下载，之后走本地缓存）");
            return new BgeSmallZhV15EmbeddingModel();
        }
        log.info("加载内置 ONNX 嵌入模型 all-MiniLM-L6-v2（英文，384 维；首次需下载 ~90MB）");
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    @Lazy
    public EmbeddingStore<TextSegment> goodsEmbeddingStore(UnifiedJedis ragJedis) {
        int dimension = currentDimension();
        log.info("装配 RedisEmbeddingStore：indexName={} dimension={} model={}",
                indexName, dimension, embeddingModelName);
        return RedisEmbeddingStore.builder()
                .unifiedJedis(ragJedis)
                .indexName(indexName)
                .dimension(dimension)
                // 必须把商品标识写进 Redis 索引：否则向量侧返回的 TextSegment 拿不到 metadata，
                // 检索结果无法回填商品（实测：不加时 goodsId 为空，导致 RRF 合并错乱、分数虚高）
                .metadataKeys(List.of("goodsId", "title"))
                .build();
    }
}
