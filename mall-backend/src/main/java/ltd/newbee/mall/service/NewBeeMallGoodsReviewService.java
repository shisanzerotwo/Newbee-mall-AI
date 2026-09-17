package ltd.newbee.mall.service;

import ltd.newbee.mall.controller.vo.NewBeeMallGoodsReviewVO;
import ltd.newbee.mall.entity.NewBeeMallGoodsReview;
import ltd.newbee.mall.util.PageQueryUtil;
import ltd.newbee.mall.util.PageResult;

import java.util.List;

/**
 * 商品评价 Service
 */
public interface NewBeeMallGoodsReviewService {

    /**
     * 后台分页查询评论(带筛选)
     */
    PageResult getGoodsReviewPage(PageQueryUtil pageUtil);

    /**
     * 前台:查询某商品的评论列表
     */
    List<NewBeeMallGoodsReviewVO> getReviewListByGoodsId(Long goodsId);

    /**
     * 前台:发表评论
     * @return ServiceResultEnum 的结果字符串
     */
    String saveGoodsReview(NewBeeMallGoodsReview review);

    /**
     * 后台:批量删除
     */
    Boolean deleteBatch(Long[] ids);
}
