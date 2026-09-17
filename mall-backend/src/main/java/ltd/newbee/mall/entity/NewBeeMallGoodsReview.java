package ltd.newbee.mall.entity;

import java.util.Date;

/**
 * 商品评价实体类
 * 对应表 tb_newbee_mall_goods_review
 */
public class NewBeeMallGoodsReview {

    private Long reviewId;        // 评论主键id
    private Long goodsId;         // 商品id
    private Long userId;          // 评论用户id
    private Byte reviewScore;     // 评分(1-5星)
    private String reviewContent; // 评论内容
    private Byte isDeleted;       // 删除标识(0-未删除 1-已删除)
    private Date createTime;      // 评论时间
    private Date updateTime;      // 最新修改时间

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
    }

    public Long getGoodsId() {
        return goodsId;
    }

    public void setGoodsId(Long goodsId) {
        this.goodsId = goodsId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Byte getReviewScore() {
        return reviewScore;
    }

    public void setReviewScore(Byte reviewScore) {
        this.reviewScore = reviewScore;
    }

    public String getReviewContent() {
        return reviewContent;
    }

    public void setReviewContent(String reviewContent) {
        this.reviewContent = reviewContent;
    }

    public Byte getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Byte isDeleted) {
        this.isDeleted = isDeleted;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
