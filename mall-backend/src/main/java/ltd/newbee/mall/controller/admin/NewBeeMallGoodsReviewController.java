package ltd.newbee.mall.controller.admin;

import ltd.newbee.mall.service.NewBeeMallGoodsReviewService;
import ltd.newbee.mall.util.PageQueryUtil;
import ltd.newbee.mall.util.Result;
import ltd.newbee.mall.util.ResultGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 商品评价管理控制器(后台)
 * 功能:评论列表分页(可按商品/用户筛选)+ 批量删除
 */
@Controller
@RequestMapping("/admin")
public class NewBeeMallGoodsReviewController {

    @Autowired
    private NewBeeMallGoodsReviewService newBeeMallGoodsReviewService;

    /**
     * 评论管理页面(侧边栏菜单高亮 path = newbee_mall_goods_review)
     */
    @GetMapping("/goodsReviews")
    public String goodsReviewPage(HttpServletRequest request) {
        request.setAttribute("path", "newbee_mall_goods_review");
        return "admin/newbee_mall_goods_review";
    }

    /**
     * 评论列表(分页 + 可按 goodsId / userId 筛选)
     */
    @GetMapping("/goodsReviews/list")
    @ResponseBody
    public Result list(@RequestParam Map<String, Object> params) {
        if (ObjectUtils.isEmpty(params.get("page")) || ObjectUtils.isEmpty(params.get("limit"))) {
            return ResultGenerator.genFailResult("参数异常！");
        }
        PageQueryUtil pageUtil = new PageQueryUtil(params);
        return ResultGenerator.genSuccessResult(newBeeMallGoodsReviewService.getGoodsReviewPage(pageUtil));
    }

    /**
     * 批量删除评论(逻辑删除)
     */
    @PostMapping("/goodsReviews/delete")
    @ResponseBody
    public Result delete(@RequestBody Long[] ids) {
        if (ObjectUtils.isEmpty(ids)) {
            return ResultGenerator.genFailResult("参数异常！");
        }
        if (newBeeMallGoodsReviewService.deleteBatch(ids)) {
            return ResultGenerator.genSuccessResult();
        }
        return ResultGenerator.genFailResult("删除失败");
    }
}
