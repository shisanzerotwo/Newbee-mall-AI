package ltd.newbee.mall.dao;

import ltd.newbee.mall.controller.vo.NewBeeMallGoodsReviewVO;
import ltd.newbee.mall.entity.NewBeeMallGoodsReview;
import ltd.newbee.mall.util.PageQueryUtil;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品评价 Mapper
 */
public interface NewBeeMallGoodsReviewMapper {

    /**
     * 后台分页查询评论列表(带筛选)
     */
    List<NewBeeMallGoodsReviewVO> findGoodsReviewList(PageQueryUtil pageUtil);

    /**
     * 后台评论总数(与列表同条件)
     */
    int getTotalGoodsReviews(PageQueryUtil pageUtil);

    /**
     * 前台:查询某商品的评论列表(按时间倒序)
     */
    List<NewBeeMallGoodsReviewVO> selectByGoodsId(@Param("goodsId") Long goodsId);

    /**
     * 新增评论
     */
    int insertSelective(NewBeeMallGoodsReview record);

    /**
     * 批量逻辑删除
     */
    int deleteBatch(@Param("ids") Long[] ids);
}
