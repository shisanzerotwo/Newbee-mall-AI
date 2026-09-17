package ltd.newbee.mall.service.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-2 验收测试：6 个客服工具逐个真实调用（连真实 MySQL），断言不抛异常且返回有效内容。
 *
 * <p>使用 {@code @SpringBootTest}（默认 MOCK 环境，不启 Tomcat），需要 MySQL / Redis 可用，
 * 并且通过 {@code DB_PASSWORD} 环境变量提供数据库密码（WSL 下经 ops/mvn.sh 的 WSLENV 转发）。
 *
 * <p>本测试同时把每个工具的输出打印出来，便于人工核对格式是否与 Python 版对齐。
 */
@SpringBootTest
class MallToolsTest {

    @Resource
    private MallTools mallTools;

    @Test
    void tool1_searchGoods() {
        String out = mallTools.searchGoods("无印良品", 3);
        System.out.println("[工具1 searchGoods] >>>\n" + out + "\n<<<");
        assertNotNull(out);
        assertFalse(out.isBlank());
    }

    @Test
    void tool2_getGoodsDetail() {
        String out = mallTools.getGoodsDetail(10003);
        System.out.println("[工具2 getGoodsDetail] >>>\n" + out + "\n<<<");
        assertNotNull(out);
        assertTrue(out.contains("商品名："), "详情应包含商品名");
    }

    @Test
    void tool3_checkStock() {
        String out = mallTools.checkStock(10003);
        System.out.println("[工具3 checkStock] >>>\n" + out + "\n<<<");
        assertNotNull(out);
        assertTrue(out.contains("库存") || out.contains("已下架"), "应返回库存或下架提示");
    }

    @Test
    void offShelfSemanticsShouldFollowMallSourceOfTruth() {
        // 商城权威定义：Constants.SELL_STATUS_UP = 0 才是在售
        //   10003 → goods_sell_status = 1 → 已下架
        //   10159 → goods_sell_status = 0 → 在售
        // 注：Python 版 db_tools.py:103/121 把判断写反了（== 1 视为在售），
        // 本项目以商城源码为准，故这里断言的是正确语义。
        String offShelf = mallTools.checkStock(10003);
        String onSale = mallTools.checkStock(10159);

        assertTrue(offShelf.contains("已下架"),
                "10003 的 goods_sell_status=1，应判为已下架"
                        + "（Constants.SELL_STATUS_UP=0 才是在售）；实际输出：" + offShelf);
        assertTrue(onSale.contains("在售"),
                "10159 的 goods_sell_status=0，应判为在售；实际输出：" + onSale);
    }

    @Test
    void tool4_queryOrder_notFound() {
        String out = mallTools.queryOrder("2024051312345678");
        System.out.println("[工具4 queryOrder(不存在)] >>>\n" + out + "\n<<<");
        assertNotNull(out);
        assertTrue(out.contains("未找到订单号"), "不存在的订单号应明确告知");
    }

    @Test
    void tool5_searchByCategory() {
        String out = mallTools.searchByCategory("手机", 3);
        System.out.println("[工具5 searchByCategory] >>>\n" + out + "\n<<<");
        assertNotNull(out);
        assertFalse(out.isBlank());
    }

    @Test
    void tool6_recommendGoods() {
        String out = mallTools.recommendGoods("化妆水", "price_asc", 3);
        System.out.println("[工具6 recommendGoods] >>>\n" + out + "\n<<<");
        assertNotNull(out);
        assertFalse(out.isBlank());
    }

    @Test
    void optionalParamsShouldNotBeRequiredInSchema() {
        // 验证 @P(required = false, defaultValue = ...) 真的反映到工具 schema 里。
        // 早先版本描述写“默认 5”但 schema 标 required —— 契约自相矛盾，模型每次都得自己编值。
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(mallTools);

        JsonObjectSchema search = specs.stream()
                .filter(s -> "searchGoods".equals(s.name()))
                .findFirst().orElseThrow().parameters();
        assertTrue(search.required().contains("keyword"), "keyword 应为必填");
        assertFalse(search.required().contains("limit"),
                "limit 应为可选（required=false + defaultValue=5）；实际 required=" + search.required());

        JsonObjectSchema recommend = specs.stream()
                .filter(s -> "recommendGoods".equals(s.name()))
                .findFirst().orElseThrow().parameters();
        assertFalse(recommend.required().contains("sort"),
                "sort 应为可选；实际 required=" + recommend.required());
        assertFalse(recommend.required().contains("limit"),
                "limit 应为可选；实际 required=" + recommend.required());
    }

    @Test
    void limitShouldBeClamped() {
        // 传入超范围 limit，应被白名单化到 1~10（不抛异常、不返回过多）
        String out = mallTools.searchGoods("无印良品", 999);
        System.out.println("[边界 limit=999] >>>\n" + out + "\n<<<");
        assertNotNull(out);
        long itemCount = out.lines().filter(l -> l.startsWith("- [")).count();
        assertTrue(itemCount <= 10, "limit 应被限制在 10 以内，实际 " + itemCount);
    }

    /**
     * 把 6 个工具的真实输出写到文件，供人工核对与报告引用。
     * （终端对中文输出偶有编码干扰，落文件更稳。）
     */
    @Test
    void dumpAllToolOutputsForReview() throws IOException {
        StringBuilder sb = new StringBuilder("=== M2-2 MallTools 真实输出样例 ===\n\n");
        sb.append("[1] searchGoods(\"无印良品\", 3)\n").append(mallTools.searchGoods("无印良品", 3)).append("\n\n");
        sb.append("[2] getGoodsDetail(10003)\n").append(mallTools.getGoodsDetail(10003)).append("\n\n");
        sb.append("[3] checkStock(10003)\n").append(mallTools.checkStock(10003)).append("\n\n");
        sb.append("[3b] checkStock(10159)  —— 在售商品\n").append(mallTools.checkStock(10159)).append("\n\n");
        sb.append("[4] queryOrder(\"2024051312345678\")  —— 不存在的单号\n")
                .append(mallTools.queryOrder("2024051312345678")).append("\n\n");
        sb.append("[5] searchByCategory(\"手机\", 3)\n").append(mallTools.searchByCategory("手机", 3)).append("\n\n");
        sb.append("[5b] searchByCategory(\"化妆\", 3)  —— 分类表里没有该分类，应友好返回\n")
                .append(mallTools.searchByCategory("化妆", 3)).append("\n\n");
        sb.append("[6] recommendGoods(\"化妆水\", \"price_asc\", 3)\n")
                .append(mallTools.recommendGoods("化妆水", "price_asc", 3)).append("\n\n");
        sb.append("[6b] recommendGoods(\"手机\", \"price_desc\", 5)\n")
                .append(mallTools.recommendGoods("手机", "price_desc", 5)).append("\n");

        Files.writeString(Path.of("target/malltools-demo.txt"), sb.toString(), StandardCharsets.UTF_8);
        assertTrue(Files.exists(Path.of("target/malltools-demo.txt")));
    }
}
