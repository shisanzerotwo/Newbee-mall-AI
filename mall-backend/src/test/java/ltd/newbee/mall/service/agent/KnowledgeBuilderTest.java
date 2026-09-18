package ltd.newbee.mall.service.agent;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-3 验收测试：知识库构建 + <b>语料红线</b>。
 *
 * <p>需要真实的 MySQL（商品数据）与带 Query Engine 的 Redis（localhost:16379）。
 * 用 {@code @SpringBootTest}（默认 MOCK 环境，不启 Tomcat）。
 *
 * <p>首次运行会加载内置 ONNX 嵌入模型（下载 ~90MB），耗时较长属正常。
 */
@SpringBootTest
class KnowledgeBuilderTest {

    @Resource
    private KnowledgeBuilder knowledgeBuilder;

    @Test
    void chunksMustNotContainPriceOrStock() {
        KnowledgeBuilder.BuildResult result = knowledgeBuilder.buildIndex(false);

        assertTrue(result.chunkCount() > 0,
                "应至少写入一个片段，实际 " + result.chunkCount() + "（检查 MySQL/Redis 是否可用）");

        List<TextSegment> segments = knowledgeBuilder.lastSegments();
        assertEquals(result.chunkCount(), segments.size(),
                "内存片段数应与写入数一致（关键词检索依赖它）");

        // 红线 = 不得写入「价格：X 元」「库存：N 件」这种结构化字段。
        // 注意：不能简单查“价格”二字 —— 商品文案（名称/详情）里可能自然出现。
        // 所以查的是带冒号的字段格式，同时额外查 Python 版的价格格式后缀“元”。
        List<String> violations = new ArrayList<>();
        for (TextSegment segment : segments) {
            String text = segment.text();
            if (text.contains("价格：") || text.contains("价格:")
                    || text.contains("库存：") || text.contains("库存:")
                    || text.contains("件\n") && text.contains("库存")) {
                violations.add(text);
            }
        }

        assertTrue(violations.isEmpty(),
                "❌ 语料红线被破坏：" + violations.size() + " 个片段含价格/库存字段。"
                        + "样例上下文：" + context(violations));
    }

    /** 诊断用：把“价格”/“库存”周围的文字打出来，便于判断是字段泄漏还是文案自然出现 */
    private static String context(List<String> violations) {
        if (violations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String text : violations.subList(0, Math.min(2, violations.size()))) {
            int idx = text.contains("价格") ? text.indexOf("价格") : text.indexOf("库存");
            int from = Math.max(0, idx - 25);
            int to = Math.min(text.length(), idx + 25);
            sb.append("\n  …").append(text, from, to).append("…");
        }
        return sb.toString();
    }

    /** 上下架状态同样属于"会变的数据"，不应写进语料 */
    @Test
    void chunksMustNotContainSellStatus() {
        knowledgeBuilder.buildIndex(false);
        List<String> violations = new ArrayList<>();
        for (TextSegment segment : knowledgeBuilder.lastSegments()) {
            String text = segment.text();
            if (text.contains("在售") || text.contains("已下架")) {
                violations.add(text);
            }
        }
        assertTrue(violations.isEmpty(),
                "❌ 语料含上下架状态：" + violations.size() + " 个片段。样例：" + sample(violations));
    }

    /** 片段应带 goodsId 与 title 元数据（检索结果要据此回填） */
    @Test
    void chunksShouldCarryMetadata() {
        knowledgeBuilder.buildIndex(false);
        List<TextSegment> segments = knowledgeBuilder.lastSegments();
        assertTrue(!segments.isEmpty(), "片段列表不应为空");
        TextSegment first = segments.get(0);
        assertTrue(first.metadata().getString("goodsId") != null,
                "片段应带 goodsId 元数据");
        assertTrue(first.metadata().getString("title") != null,
                "片段应带 title 元数据");
    }

    /** 纯函数：分块行为（不依赖外部环境） */
    @Test
    void chunkTextShouldSplitBySizeWithOverlap() {
        String text = "a".repeat(1000);
        List<String> chunks = KnowledgeBuilder.chunkText(text, 400, 80);
        // 步长 = 400-80 = 320；起点 0,320,640,960
        //   块1 [0,400) 400字、块2 [320,720) 400字、块3 [640,1000) 360字、块4 [960,1000) 40字
        // 40 > MIN_CHUNK_LENGTH(20) 所以保留 -> 共 4 块
        assertEquals(4, chunks.size(), "1000 字符按 400/80 应切成 4 块（步长 320）");
        assertEquals(400, chunks.get(0).length());
        assertEquals(360, chunks.get(2).length(), "末段截断到字符串末尾");
    }

    /** 纯函数：HTML 清洗（商品详情是富文本） */
    @Test
    void cleanHtmlShouldStripTagsAndTruncate() {
        String html = "<p>这是<b>测试</b>详情</p><script>x</script>";
        String clean = KnowledgeBuilder.cleanHtml(html);
        assertTrue(!clean.contains("<"), "应剥掉 HTML 标签，实际：" + clean);
        assertTrue(clean.contains("测试"), "应保留文本内容，实际：" + clean);
    }

    /** 纯函数：组装文本必须不含价格/库存字段（红线在源头就守住） */
    @Test
    void toSearchTextShouldExcludeVolatileFields() {
        ltd.newbee.mall.entity.NewBeeMallGoods goods = new ltd.newbee.mall.entity.NewBeeMallGoods();
        goods.setGoodsId(10003L);
        goods.setGoodsName("测试商品");
        goods.setGoodsIntro("测试简介");
        goods.setTag("测试标签");
        goods.setSellingPrice(199);
        goods.setStockNum(1000);
        goods.setGoodsSellStatus((byte) 0);

        String text = KnowledgeBuilder.toSearchText(goods, "测试分类", "测试详情");

        assertTrue(text.contains("测试商品") && text.contains("测试分类"),
                "应包含稳定语义字段，实际：" + text);
        assertTrue(!text.contains("199"), "不得包含价格，实际：" + text);
        assertTrue(!text.contains("价格"), "不得出现「价格」字样，实际：" + text);
        assertTrue(!text.contains("库存"), "不得出现「库存」字样，实际：" + text);
        assertTrue(!text.contains("在售") && !text.contains("已下架"),
                "不得包含上下架状态，实际：" + text);
    }

    private static String sample(List<String> violations) {
        if (violations.isEmpty()) {
            return "";
        }
        String first = violations.get(0);
        return first.substring(0, Math.min(120, first.length())) + "…";
    }
}
