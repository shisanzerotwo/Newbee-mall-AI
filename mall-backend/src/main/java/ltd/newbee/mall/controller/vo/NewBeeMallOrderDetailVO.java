/**
 * 严肃声明：
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！
 * 本系统已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！
 * Copyright (c) 2019-2020 十三 all rights reserved.
 * 版权所有，侵权必究！
 */
package ltd.newbee.mall.controller.vo;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * @apiNote 订单详情页VO对象
 *         用于个人中心"订单详情"页面展示完整的订单信息（含收货地址、支付信息、订单项等）
 */
public class NewBeeMallOrderDetailVO implements Serializable {

    private String orderNo;               // 订单编号
    private Integer totalPrice;           // 订单总价（单位：分）
    private Byte payStatus;               // 支付状态（同PayStatusEnum）
    private String payStatusString;       // 支付状态文本描述（用于前端展示）
    private Byte payType;                 // 支付类型（同PayTypeEnum）
    private String payTypeString;         // 支付类型文本描述（用于前端展示）
    private Date payTime;                 // 支付完成时间
    private Byte orderStatus;             // 订单状态（同NewBeeMallOrderStatusEnum）
    private String orderStatusString;     // 订单状态文本描述（用于前端展示）
    private String userAddress;           // 收货地址
    private Date createTime;              // 订单创建时间
    private List<NewBeeMallOrderItemVO> newBeeMallOrderItemVOS; // 订单项列表

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Integer getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(Integer totalPrice) {
        this.totalPrice = totalPrice;
    }

    public Byte getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(Byte payStatus) {
        this.payStatus = payStatus;
    }

    public Byte getPayType() {
        return payType;
    }

    public void setPayType(Byte payType) {
        this.payType = payType;
    }

    public Date getPayTime() {
        return payTime;
    }

    public void setPayTime(Date payTime) {
        this.payTime = payTime;
    }

    public Byte getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(Byte orderStatus) {
        this.orderStatus = orderStatus;
    }

    public String getUserAddress() {
        return userAddress;
    }

    public void setUserAddress(String userAddress) {
        this.userAddress = userAddress;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getPayStatusString() {
        return payStatusString;
    }

    public void setPayStatusString(String payStatusString) {
        this.payStatusString = payStatusString;
    }

    public String getPayTypeString() {
        return payTypeString;
    }

    public void setPayTypeString(String payTypeString) {
        this.payTypeString = payTypeString;
    }

    public String getOrderStatusString() {
        return orderStatusString;
    }

    public void setOrderStatusString(String orderStatusString) {
        this.orderStatusString = orderStatusString;
    }

    public List<NewBeeMallOrderItemVO> getNewBeeMallOrderItemVOS() {
        return newBeeMallOrderItemVOS;
    }

    public void setNewBeeMallOrderItemVOS(List<NewBeeMallOrderItemVO> newBeeMallOrderItemVOS) {
        this.newBeeMallOrderItemVOS = newBeeMallOrderItemVOS;
    }
}
