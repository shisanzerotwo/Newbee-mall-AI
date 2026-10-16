package ltd.newbee.mall.controller.vo;

import ltd.newbee.mall.entity.NewBeeMallGoods;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 校园二手商品 VO —— 前端（mall-frontend）消费的字段契约。
 *
 * <p>与实体 {@link NewBeeMallGoods} 的差异：
 * <ul>
 *   <li>价格从「分」换算为「元」（前端直接展示，不再自行除 100）</li>
 *   <li>把商城字段映射为校园场景语义：{@code condition}（成色）、{@code urgent}（急出）、
 *       {@code tradeTypes}（交易方式）、{@code seller}（卖家卡片）</li>
 *   <li>成色 / 交易方式 / 急出目前从 {@code tag} 解析（种子数据用「九成新,校内自提」这类逗号标签），
 *       二期再落库为独立字段</li>
 * </ul>
 */
public class CampusGoodsVO implements Serializable {

    private Long id;
    private String title;
    private String intro;
    private String desc;
    private Double price;
    private Double originalPrice;
    private String cover;
    private String carousel;
    private Long categoryId;
    private String categoryName;
    private String condition;
    private Boolean urgent;
    private List<String> tradeTypes = new ArrayList<>();
    private String[] tags = new String[0];
    private boolean onSale;
    private Integer stock;
    private Integer views;
    private Seller seller;
    /** 卖家用户 ID：私聊会话的 peerId（M4） */
    private Long sellerId;

    /** 卖家卡片（校园场景：实名认证 + 信用分） */
    public static class Seller implements Serializable {
        private Long userId;
        private String name;
        private Double credit;
        private boolean campusVerified;
        private String school;

        public Seller(Long userId, String name, Double credit, boolean campusVerified, String school) {
            this.userId = userId;
            this.name = name;
            this.credit = credit;
            this.campusVerified = campusVerified;
            this.school = school;
        }

        public Long getUserId() { return userId; }
        public String getName() { return name; }
        public Double getCredit() { return credit; }
        public boolean isCampusVerified() { return campusVerified; }
        public String getSchool() { return school; }
    }

    /** 演示期卖家信息：真实场景应从商品所属用户聚合（二期） */
    public static CampusGoodsVO from(NewBeeMallGoods g, String categoryName, Integer views, Seller seller) {
        CampusGoodsVO vo = new CampusGoodsVO();
        vo.id = g.getGoodsId();
        vo.title = g.getGoodsName();
        vo.intro = g.getGoodsIntro();
        vo.desc = g.getGoodsDetailContent();
        vo.price = fenToYuan(g.getSellingPrice());
        vo.originalPrice = fenToYuan(g.getOriginalPrice());
        vo.cover = g.getGoodsCoverImg();
        vo.carousel = g.getGoodsCarousel();
        vo.categoryId = g.getGoodsCategoryId();
        vo.categoryName = categoryName;
        vo.stock = g.getStockNum();
        // 本项目约定：0 = 上架（Constants.SELL_STATUS_UP）
        vo.onSale = g.getGoodsSellStatus() != null && g.getGoodsSellStatus() == 0;
        vo.views = views == null ? 0 : views;

        String tag = g.getTag() == null ? "" : g.getTag();
        vo.tags = tag.isBlank() ? new String[0] : tag.split("[，,]");
        for (String t : vo.tags) {
            String item = t.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (item.contains("成新") || item.contains("全新")) {
                vo.condition = item;
            } else if (item.contains("自提") || item.contains("送货") || item.contains("邮寄")) {
                vo.tradeTypes.add(item);
            } else if (item.contains("急出")) {
                vo.urgent = true;
            }
        }
        if (vo.urgent == null) {
            vo.urgent = false;
        }
        if (vo.tradeTypes.isEmpty()) {
            vo.tradeTypes.add("校内自提");
        }
        vo.seller = seller;
        vo.sellerId = seller == null ? null : seller.getUserId();
        return vo;
    }

    private static Double fenToYuan(Integer fen) {
        return fen == null ? null : Math.round(fen) / 100.0;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getIntro() { return intro; }
    public String getDesc() { return desc; }
    public Double getPrice() { return price; }
    public Double getOriginalPrice() { return originalPrice; }
    public String getCover() { return cover; }
    public String getCarousel() { return carousel; }
    public Long getCategoryId() { return categoryId; }
    public String getCategoryName() { return categoryName; }
    public String getCondition() { return condition; }
    public Boolean getUrgent() { return urgent; }
    public List<String> getTradeTypes() { return tradeTypes; }
    public String[] getTags() { return tags; }
    public boolean isOnSale() { return onSale; }
    public Integer getStock() { return stock; }
    public Integer getViews() { return views; }
    public Seller getSeller() { return seller; }
    public Long getSellerId() { return sellerId; }
}
