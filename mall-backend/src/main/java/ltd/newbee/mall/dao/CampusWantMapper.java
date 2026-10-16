package ltd.newbee.mall.dao;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 校园二手「想要（收藏）」DAO：C2C 直买模式下替代购物车（见设计文档 §7）。
 */
public interface CampusWantMapper {

    int insert(@Param("userId") Long userId, @Param("goodsId") Long goodsId);

    int delete(@Param("userId") Long userId, @Param("goodsId") Long goodsId);

    int countByUserAndGoods(@Param("userId") Long userId, @Param("goodsId") Long goodsId);

    /** 我想要的商品 ID 列表（按加入时间倒序） */
    List<Long> selectGoodsIdsByUser(@Param("userId") Long userId);

    int countByUser(@Param("userId") Long userId);
}
