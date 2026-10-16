package ltd.newbee.mall.dao;

import ltd.newbee.mall.controller.vo.CampusOrderItemVO;
import ltd.newbee.mall.entity.CampusOrder;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 校园二手订单 DAO（M5） */
public interface CampusOrderMapper {

    int insert(CampusOrder order);

    CampusOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /** 条件更新（支付/取消），带状态前置条件防重复支付 */
    int updateStatus(@Param("orderNo") String orderNo,
                     @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus,
                     @Param("payType") String payType);

    List<CampusOrderItemVO> selectOrdersByUser(@Param("userId") Long userId, @Param("limit") int limit);

    /** 同一商品是否已有未完成订单（防重复下单） */
    int countActiveByGoods(@Param("goodsId") Long goodsId, @Param("buyerId") Long buyerId);
}