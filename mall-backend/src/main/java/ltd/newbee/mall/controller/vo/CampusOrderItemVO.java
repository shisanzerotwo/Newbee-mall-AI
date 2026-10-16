package ltd.newbee.mall.controller.vo;

import java.util.Date;

/** 订单列表项（M5）：避免 resultType=map 依赖列别名大小写（该坑曾导致 /api/order/mine 500）。 */
public class CampusOrderItemVO {

    private String orderNo;
    private Long goodsId;
    private String goodsTitle;
    private Integer priceFen;
    private Integer status;
    private String payType;
    private Long sellerId;
    private String pickupPoint;
    private Date createTime;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getGoodsId() { return goodsId; }
    public void setGoodsId(Long goodsId) { this.goodsId = goodsId; }
    public String getGoodsTitle() { return goodsTitle; }
    public void setGoodsTitle(String goodsTitle) { this.goodsTitle = goodsTitle; }
    public Integer getPriceFen() { return priceFen; }
    public void setPriceFen(Integer priceFen) { this.priceFen = priceFen; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getPayType() { return payType; }
    public void setPayType(String payType) { this.payType = payType; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public String getPickupPoint() { return pickupPoint; }
    public void setPickupPoint(String pickupPoint) { this.pickupPoint = pickupPoint; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}