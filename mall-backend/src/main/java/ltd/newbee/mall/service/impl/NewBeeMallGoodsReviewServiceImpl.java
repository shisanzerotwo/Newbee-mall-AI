package ltd.newbee.mall.service.impl;

import ltd.newbee.mall.common.NewBeeMallException;
import ltd.newbee.mall.common.ServiceResultEnum;
import ltd.newbee.mall.controller.vo.NewBeeMallGoodsReviewVO;
import ltd.newbee.mall.dao.NewBeeMallGoodsReviewMapper;
import ltd.newbee.mall.entity.NewBeeMallGoodsReview;
import ltd.newbee.mall.service.NewBeeMallGoodsReviewService;
import ltd.newbee.mall.util.PageQueryUtil;
import ltd.newbee.mall.util.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class NewBeeMallGoodsReviewServiceImpl implements NewBeeMallGoodsReviewService {

    @Autowired
    private NewBeeMallGoodsReviewMapper newBeeMallGoodsReviewMapper;

    @Override
    public PageResult getGoodsReviewPage(PageQueryUtil pageUtil) {
        List<NewBeeMallGoodsReviewVO> reviews = newBeeMallGoodsReviewMapper.findGoodsReviewList(pageUtil);
        int total = newBeeMallGoodsReviewMapper.getTotalGoodsReviews(pageUtil);
        return new PageResult(reviews, total, pageUtil.getLimit(), pageUtil.getPage());
    }

    @Override
    public List<NewBeeMallGoodsReviewVO> getReviewListByGoodsId(Long goodsId) {
        List<NewBeeMallGoodsReviewVO> reviews = newBeeMallGoodsReviewMapper.selectByGoodsId(goodsId);
        if (reviews == null) {
            return new ArrayList<>();
        }
        return reviews;
    }

    @Override
    public String saveGoodsReview(NewBeeMallGoodsReview review) {
        // 参数校验:商品、用户、评分、内容都不能为空
        if (review.getGoodsId() == null || review.getUserId() == null) {
            return ServiceResultEnum.GOODS_NOT_EXIST.getResult();
        }
        if (review.getReviewScore() == null || review.getReviewScore() < 1 || review.getReviewScore() > 5) {
            NewBeeMallException.fail("评分必须在 1-5 之间");
        }
        if (!StringUtils.hasText(review.getReviewContent())) {
            NewBeeMallException.fail("评论内容不能为空");
        }
        review.setCreateTime(new Date());
        review.setUpdateTime(new Date());
        if (newBeeMallGoodsReviewMapper.insertSelective(review) > 0) {
            return ServiceResultEnum.SUCCESS.getResult();
        }
        return ServiceResultEnum.DB_ERROR.getResult();
    }

    @Override
    public Boolean deleteBatch(Long[] ids) {
        if (ids.length < 1) {
            return false;
        }
        // 逻辑删除
        return newBeeMallGoodsReviewMapper.deleteBatch(ids) > 0;
    }
}
