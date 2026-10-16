package ltd.newbee.mall.entity;

import java.io.Serializable;
import java.util.Date;

/** 校园二手订单（M5：下单 → 支付 → 完成；线下自提场景的「担保」记录）。 */
public class CampusOrder implements Serializable {

    /** 订单状态：0 待支付 / 1 已支付待自提 / 2 交易完成 / 3 已取消 */
    public static final int STATUS_UNPAID = 0;
    public static final int STATUS_PAID = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_CANCELED = 3;

    private String orderNo;
    private Long buyerId;
    private Long sellerId;
    private Long goodsId;
    private String goodsTitle;
    /** 金额单位：分 */
    private Integer priceFen;
    private String payType;
    private Integer status;
    private String pickupPoint;
    private Date createTime;
    private Date payTime;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public Long getGoodsId() { return goodsId; }
    public void setGoodsId(Long goodsId) { this.goodsId = goodsId; }
    public String getGoodsTitle() { return goodsTitle; }
    public void setGoodsTitle(String goodsTitle) { this.goodsTitle = goodsTitle; }
    public Integer getPriceFen() { return priceFen; }
    public void setPriceFen(Integer priceFen) { this.priceFen = priceFen; }
    public String getPayType() { return payType; }
    public void setPayType(String payType) { this.payType = payType; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getPickupPoint() { return pickupPoint; }
    public void setPickupPoint(String pickupPoint) { this.pickupPoint = pickupPoint; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getPayTime() { return payTime; }
    public void setPayTime(Date payTime) { this.payTime = payTime; }
}