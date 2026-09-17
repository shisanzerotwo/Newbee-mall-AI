package ltd.newbee.mall.service.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Resource;
import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.common.NewBeeMallOrderStatusEnum;
import ltd.newbee.mall.common.PayStatusEnum;
import ltd.newbee.mall.common.PayTypeEnum;
import ltd.newbee.mall.dao.NewBeeMallGoodsMapper;
import ltd.newbee.mall.dao.NewBeeMallOrderMapper;
import ltd.newbee.mall.entity.NewBeeMallGoods;
import ltd.newbee.mall.entity.NewBeeMallOrder;
import ltd.newbee.mall.util.PageQueryUtil;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 客服的工具集（M2-2）。
 *
 * <p>6 个工具，<b>全部只读</b>，全部<b>直调 Mapper</b>（不走 HTTP、不自己 JDBC 连库）。
 * 调用方是 M2-4 的编排层；本类不含任何模型调用或对话逻辑。
 *
 * <p><b>上下架语义（重要）</b>：本项目 {@code goods_sell_status} <b>0 = 在售</b>、
 * <b>1 = 已下架</b>——依据是 {@link Constants#SELL_STATUS_UP} 与商城扣库存 SQL
 * （{@code NewBeeMallGoodsMapper.xml} 里 {@code and goods_sell_status = 0}）。
 * 注意：同功能的 Python 教学版（{@code 12_agent_cs/db_tools.py}）把该判断写反了，
 * Java 侧以商城源码为准。
 *
 * <p><b>参数安全</b>：所有 {@code limit} 一律白名单化；排序字段在 XML 里用
 * {@code <choose>} 白名单，不使用 {@code ${}} 拼接。
 */
@Component
public class MallTools {

    private static final int MAX_LIMIT = 10;
    private static final int MAX_RECOMMEND_LIMIT = 5;
    private static final int INTRO_PREVIEW = 40;
    private static final int DETAIL_MAX = 300;

    @Resource
    private NewBeeMallGoodsMapper goodsMapper;

    @Resource
    private NewBeeMallOrderMapper orderMapper;

    // ------------------------------------------------------------------
    // 工具 1：商品搜索
    // ------------------------------------------------------------------

    /**
     * 商品搜索。
     *
     * <p><b>排序</b>：复用商城既有查询 {@code findNewBeeMallGoodsListBySearch}，
     * 其 SQL <b>没有 ORDER BY</b>（2026-09-18 用 MyBatis DEBUG 日志实测确认），
     * 因此结果顺序由 MySQL 决定、不保证稳定。这是有意为之 —— 不修改商城前台
     * 查询的语义（该查询被商城搜索页共用）。
     *
     * <p>若后续需要稳定顺序，应显式加 ORDER BY（可参考本类 {@code recommendGoods}
     * 用的 {@code selectForRecommend}，它以 {@code <choose>} 做白名单排序）。
     */
    @Tool({"按关键词搜索商品（名称或简介模糊匹配，含已下架商品并标注状态）。",
            "适合回答「有什么XX卖？」这类问题。",
            "注意：下架商品要如实告知已下架，不要推荐。"})
    public String searchGoods(@P("搜索关键词，如：化妆水、手机") String keyword,
                              @P(value = "最多返回几条，1-10", required = false, defaultValue = "5") int limit) {
        int n = clamp(limit, 1, MAX_LIMIT);
        Map<String, Object> params = new HashMap<>();
        params.put("page", 1);
        params.put("limit", n);
        params.put("keyword", keyword);
        List<NewBeeMallGoods> rows =
                goodsMapper.findNewBeeMallGoodsListBySearch(new PageQueryUtil(params));
        if (rows.isEmpty()) {
            return "未找到与「" + keyword + "」相关的商品。";
        }
        List<String> lines = new ArrayList<>();
        lines.add("找到 " + rows.size() + " 个相关商品：");
        for (NewBeeMallGoods g : rows) {
            lines.add("- [" + g.getGoodsId() + "] " + g.getGoodsName() + offShelfMark(g)
                    + "｜价格 " + formatPrice(g.getSellingPrice())
                    + "｜库存 " + g.getStockNum()
                    + "｜简介：" + preview(g.getGoodsIntro(), INTRO_PREVIEW));
        }
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------
    // 工具 2：商品详情
    // ------------------------------------------------------------------
    @Tool({"查询指定商品的完整信息：名称、价格、库存、标签、简介、详情描述、上下架状态。",
            "适合回答「这个商品怎么样」「具体参数是什么」这类问题。"})
    public String getGoodsDetail(@P("商品 ID，如 10003") int goodsId) {
        NewBeeMallGoods g = goodsMapper.selectByPrimaryKey((long) goodsId);
        if (g == null) {
            return "商品 " + goodsId + " 不存在。";
        }
        return "商品名：" + g.getGoodsName() + "（" + sellStatusText(g) + "）\n"
                + "价格：" + formatPrice(g.getSellingPrice()) + "\n"
                + "库存：" + g.getStockNum() + " 件\n"
                + "标签：" + (isBlank(g.getTag()) ? "无" : g.getTag()) + "\n"
                + "简介：" + (isBlank(g.getGoodsIntro()) ? "无" : g.getGoodsIntro()) + "\n"
                + "详情：" + cleanHtml(g.getGoodsDetailContent());
    }

    // ------------------------------------------------------------------
    // 工具 3：库存查询
    // ------------------------------------------------------------------
    @Tool({"查询商品库存量与在售状态。适合回答「有货吗」「还有多少」这类问题。"})
    public String checkStock(@P("商品 ID，如 10003") int goodsId) {
        NewBeeMallGoods g = goodsMapper.selectByPrimaryKey((long) goodsId);
        if (g == null) {
            return "商品 " + goodsId + " 不存在。";
        }
        if (!isOnSale(g)) {
            return "「" + g.getGoodsName() + "」已下架，暂不可购买。";
        }
        return "「" + g.getGoodsName() + "」库存 " + g.getStockNum() + " 件，当前在售。";
    }

    // ------------------------------------------------------------------
    // 工具 4：订单状态查询
    // ------------------------------------------------------------------
    @Tool({"按订单号查询订单状态、金额、支付方式与收货信息。",
            "适合回答「我的订单到哪了」这类问题（需要用户提供订单号）。"})
    public String queryOrder(@P("订单号，如 2024051312345678") String orderNo) {
        if (isBlank(orderNo)) {
            return "请提供订单号。";
        }
        NewBeeMallOrder o = orderMapper.selectByOrderNo(orderNo.trim());
        if (o == null) {
            return "未找到订单号 " + orderNo + "，请确认订单号是否正确。";
        }
        return "订单 " + o.getOrderNo() + "：金额 " + formatPrice(o.getTotalPrice())
                + "｜" + payStatusText(o.getPayStatus())
                + "｜订单状态：" + orderStatusText(o.getOrderStatus())
                + "｜支付方式：" + payTypeText(o.getPayType())
                + "｜收货信息：" + (isBlank(o.getUserAddress()) ? "无" : o.getUserAddress())
                + "｜下单时间：" + formatTime(o.getCreateTime());
    }

    // ------------------------------------------------------------------
    // 工具 5：按分类查找
    // ------------------------------------------------------------------
    @Tool({"按商品分类名称查找商品（如「化妆水」「手机」，含已下架并标注）。",
            "适合回答「XX 类有什么商品」这类问题。"})
    public String searchByCategory(@P("分类名，可匹配一/二/三级分类") String categoryName,
                                   @P(value = "最多返回几条，1-10", required = false, defaultValue = "5") int limit) {
        int n = clamp(limit, 1, MAX_LIMIT);
        List<NewBeeMallGoods> rows = goodsMapper.selectByCategoryNameLike(categoryName, n);
        if (rows.isEmpty()) {
            return "分类「" + categoryName + "」下没有商品。";
        }
        List<String> lines = new ArrayList<>();
        lines.add("分类「" + categoryName + "」下找到 " + rows.size() + " 个商品：");
        for (NewBeeMallGoods g : rows) {
            lines.add("- [" + g.getGoodsId() + "] " + g.getGoodsName() + offShelfMark(g)
                    + "｜" + formatPrice(g.getSellingPrice())
                    + "｜库存 " + g.getStockNum());
        }
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------
    // 工具 6：商品推荐
    // ------------------------------------------------------------------
    @Tool({"按品类或需求推荐商品：返回完整简介、标签、价格、库存，在售商品排最前。",
            "适合回答「推荐一款XX」「有什么性价比高的XX」这类问题。",
            "sort 可选：price_asc（性价比优先）/ price_desc（品质优先）/ default。"})
    public String recommendGoods(@P("品类或需求关键词，如：化妆水、洗面奶") String keyword,
                                 @P(value = "排序：price_asc / price_desc / default", required = false, defaultValue = "default") String sort,
                                 @P(value = "推荐数量，1-5", required = false, defaultValue = "3") int limit) {
        int n = clamp(limit, 1, MAX_RECOMMEND_LIMIT);
        List<NewBeeMallGoods> rows =
                goodsMapper.selectForRecommend(keyword, isBlank(sort) ? "default" : sort, n);
        if (rows.isEmpty()) {
            return "没有找到与「" + keyword + "」相关的商品。";
        }
        List<String> lines = new ArrayList<>();
        lines.add("推荐「" + keyword + "」相关商品 " + rows.size() + " 款：");
        for (NewBeeMallGoods g : rows) {
            lines.add("- [" + g.getGoodsId() + "] " + g.getGoodsName() + offShelfMark(g)
                    + "｜" + formatPrice(g.getSellingPrice())
                    + "｜库存 " + g.getStockNum()
                    + (isBlank(g.getTag()) ? "" : "｜标签：" + g.getTag()) + "\n"
                    + "   简介：" + (isBlank(g.getGoodsIntro()) ? "无" : g.getGoodsIntro()));
        }
        return String.join("\n", lines);
    }

    // ==================================================================
    // 内部辅助
    // ==================================================================

    /** 是否在售：本项目 0 = 在售（{@link Constants#SELL_STATUS_UP}），1 = 已下架 */
    private boolean isOnSale(NewBeeMallGoods g) {
        return g.getGoodsSellStatus() != null
                && g.getGoodsSellStatus() == Constants.SELL_STATUS_UP;
    }

    private String offShelfMark(NewBeeMallGoods g) {
        return isOnSale(g) ? "" : "（已下架）";
    }

    private String sellStatusText(NewBeeMallGoods g) {
        return isOnSale(g) ? "在售" : "已下架";
    }

    private String formatPrice(Integer price) {
        return (price == null ? 0 : price) + ".00 元";
    }

    private String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 富文本详情 → 纯文本（去标签、压缩空白）并截断 */
    private String cleanHtml(String html) {
        if (html == null || html.isBlank()) {
            return "无";
        }
        String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return preview(text, DETAIL_MAX);
    }

    /** 订单状态文本（复用项目枚举，避免自建一套映射） */
    private String orderStatusText(Byte status) {
        if (status == null) {
            return "未知";
        }
        for (NewBeeMallOrderStatusEnum e : NewBeeMallOrderStatusEnum.values()) {
            if (e.getOrderStatus() == status) {
                return e.getName();
            }
        }
        return "未知(" + status + ")";
    }

    private String payStatusText(Byte status) {
        if (status == null) {
            return "未知";
        }
        for (PayStatusEnum e : PayStatusEnum.values()) {
            if (e.getPayStatus() == status) {
                return e.getName();
            }
        }
        return "未知(" + status + ")";
    }

    private String payTypeText(Byte type) {
        if (type == null) {
            return "未知";
        }
        for (PayTypeEnum e : PayTypeEnum.values()) {
            if (e.getPayType() == type) {
                return e.getName();
            }
        }
        return "未知(" + type + ")";
    }

    private String formatTime(Date d) {
        return d == null ? "未知" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
