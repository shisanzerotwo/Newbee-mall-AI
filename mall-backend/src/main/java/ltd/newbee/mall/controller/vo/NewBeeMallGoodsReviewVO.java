package ltd.newbee.mall.controller.vo;

import java.io.Serializable;
import java.util.Date;

/**
 * 商品评价展示 VO
 * 前台商品详情页展示评论列表使用,比实体多一个用户昵称字段
 */
public class NewBeeMallGoodsReviewVO implements Serializable {

    private Long reviewId;        // 评论主键id
    private Long goodsId;         // 商品id
    private Long userId;          // 评论用户id
    private String nickName;      // 评论用户昵称(联查用户表)
    private Byte reviewScore;     // 评分(1-5星)
    private String reviewContent; // 评论内容
    private Date createTime;      // 评论时间

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

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
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

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
