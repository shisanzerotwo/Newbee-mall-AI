package ltd.newbee.mall.controller.mall;

import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.common.ServiceResultEnum;
import ltd.newbee.mall.controller.vo.NewBeeMallGoodsReviewVO;
import ltd.newbee.mall.controller.vo.NewBeeMallUserVO;
import ltd.newbee.mall.entity.NewBeeMallGoodsReview;
import ltd.newbee.mall.service.NewBeeMallGoodsReviewService;
import ltd.newbee.mall.util.Result;
import ltd.newbee.mall.util.ResultGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpSession;
import java.util.List;

/**
 * 商品评价控制器(前台)
 * 负责商品详情页的评论列表展示与评论提交
 */
@Controller
public class GoodsReviewController {

    @Autowired
    private NewBeeMallGoodsReviewService newBeeMallGoodsReviewService;

    /**
     * 查询某商品的评论列表(AJAX 接口)
     * @param goodsId 商品id
     */
    @GetMapping("/goods/review/list")
    @ResponseBody
    public Result reviewList(@RequestParam("goodsId") Long goodsId) {
        List<NewBeeMallGoodsReviewVO> reviews = newBeeMallGoodsReviewService.getReviewListByGoodsId(goodsId);
        return ResultGenerator.genSuccessResult(reviews);
    }

    /**
     * 发表评论(AJAX 接口)
     * 用户信息从 Session 获取,不信任前端传的 userId
     * @param review 评论(含 goodsId / reviewScore / reviewContent)
     * @param session 当前会话
     */
    @PostMapping("/goods/review/save")
    @ResponseBody
    public Result saveReview(@RequestBody NewBeeMallGoodsReview review, HttpSession session) {
        NewBeeMallUserVO user = (NewBeeMallUserVO) session.getAttribute(Constants.MALL_USER_SESSION_KEY);
        if (user == null) {
            return ResultGenerator.genFailResult("请先登录");
        }
        review.setUserId(user.getUserId());
        String saveResult = newBeeMallGoodsReviewService.saveGoodsReview(review);
        if (ServiceResultEnum.SUCCESS.getResult().equals(saveResult)) {
            return ResultGenerator.genSuccessResult();
        }
        return ResultGenerator.genFailResult(saveResult);
    }
}
