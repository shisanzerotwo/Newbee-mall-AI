/**
 * <b>严肃声明：</b><br/>
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！<br/>
 * 本系统已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！<br/>
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！<br/>
 * Copyright (c) 2019-2020 十三 all rights reserved.<br/>
 * 版权所有，侵权必究！
 */
package ltd.newbee.mall.controller.mall;

import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.common.NewBeeMallException;
import ltd.newbee.mall.common.ServiceResultEnum;
import ltd.newbee.mall.controller.vo.NewBeeMallGoodsDetailVO;
import ltd.newbee.mall.controller.vo.NewBeeMallGoodsReviewVO;
import ltd.newbee.mall.controller.vo.SearchPageCategoryVO;
import ltd.newbee.mall.entity.NewBeeMallGoods;
import ltd.newbee.mall.service.NewBeeMallCategoryService;
import ltd.newbee.mall.service.NewBeeMallGoodsReviewService;
import ltd.newbee.mall.service.NewBeeMallGoodsService;
import ltd.newbee.mall.util.BeanUtil;
import ltd.newbee.mall.util.PageQueryUtil;
import org.springframework.stereotype.Controller;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 前台商品控制器
 * 负责处理商品搜索、商品详情展示等相关请求
 * 这是用户浏览商品的主要入口控制器
 *
 * @author 13
 */
@Controller
public class GoodsController {

    @Resource // 注入商品服务，提供商品业务逻辑
    private NewBeeMallGoodsService newBeeMallGoodsService;

    @Resource // 注入分类服务，提供分类相关查询
    private NewBeeMallCategoryService newBeeMallCategoryService;

    @Resource // 注入商品评价服务,提供评论列表/发表评论
    private NewBeeMallGoodsReviewService newBeeMallGoodsReviewService;

    /**
     * 商品搜索页面
 *    GET /search, GET /search.html
     *
     * @param params 搜索参数地图（包含页码、关键字、分类ID等）
     * @param request HTTP 请求对象
     * @return 视图名称 "mall/search"，指向搜索结果页模板
     */
    @GetMapping({"/search", "/search.html"}) // 支持多种搜索页访问路径
    public String searchPage(@RequestParam Map<String, Object> params, HttpServletRequest request) {
        // 如果未指定页码，默认为第1页
        if (ObjectUtils.isEmpty(params.get("page"))) {
            params.put("page", 1);
        }

        // 设置每页显示的商品数量（默认10条）
        params.put("limit", Constants.GOODS_SEARCH_PAGE_LIMIT);

        // 封装左侧分类树形数据，用于筛选导航
        if (params.containsKey("goodsCategoryId") && StringUtils.hasText(params.get("goodsCategoryId") + "")) {
            Long categoryId = Long.valueOf(params.get("goodsCategoryId") + "");
            SearchPageCategoryVO searchPageCategoryVO = newBeeMallCategoryService.getCategoriesForSearch(categoryId);
            if (searchPageCategoryVO != null) {
                request.setAttribute("goodsCategoryId", categoryId); // 当前选中的分类ID
                request.setAttribute("searchPageCategoryVO", searchPageCategoryVO); // 分类树形数据
            }
        }

        // 封装排序参数供前端回显（按销量/价格/上架时间排序）
        if (params.containsKey("orderBy") && StringUtils.hasText(params.get("orderBy") + "")) {
            request.setAttribute("orderBy", params.get("orderBy") + "");
        }

        // 提取搜索关键字，并去除两端空格
        String keyword = "";
        if (params.containsKey("keyword") && StringUtils.hasText((params.get("keyword") + "").trim())) {
            keyword = params.get("keyword") + "";
        }
        request.setAttribute("keyword", keyword); // 保存搜索关键字到Request
        params.put("keyword", keyword); // 传递关键字到分页工具

        // 只搜索上架状态的商品（SELL_STATUS_UP = 0）
        params.put("goodsSellStatus", Constants.SELL_STATUS_UP);

        // 创建分页查询工具对象
        PageQueryUtil pageUtil = new PageQueryUtil(params);

        // 执行搜索，获取分页结果
        request.setAttribute("pageResult", newBeeMallGoodsService.searchNewBeeMallGoods(pageUtil));

        // 返回搜索结果页模板
        return "mall/search";
    }

    /**
     * 商品详情页
 *    GET /goods/detail/{goodsId}
     *
     * @param goodsId 商品 ID（路径变量）
     * @param request HTTP 请求对象
     * @return 视图名称 "mall/detail"，指向商品详情页模板
     */
    @GetMapping("/goods/detail/{goodsId}") // 商品详情页面，如 /goods/detail/1
    public String detailPage(@PathVariable("goodsId") Long goodsId, HttpServletRequest request) {
        // 校验商品 ID 有效性（必须大于0）
        if (goodsId < 1) {
            NewBeeMallException.fail("商品 ID 不能为空或无效参数");
        }

        // 通过 Service 获取商品信息
        NewBeeMallGoods goods = newBeeMallGoodsService.getNewBeeMallGoodsById(goodsId);

        // 检查商品是否在售（SELL_STATUS_UP = 0 表示在售）
        if (Constants.SELL_STATUS_UP != goods.getGoodsSellStatus()) {
            NewBeeMallException.fail(ServiceResultEnum.GOODS_PUT_DOWN.getResult());
        }

        // 构建商品详细信息视图对象（含轮播图等扩展数据）
        NewBeeMallGoodsDetailVO goodsDetailVO = new NewBeeMallGoodsDetailVO();
        BeanUtil.copyProperties(goods, goodsDetailVO); // 复制基本属性

        // 设置轮播图关联图片（逗号分隔的字符串转为数组）
        goodsDetailVO.setGoodsCarouselList(goods.getGoodsCarousel().split(","));

        // 将商品详情设置到 Request 中，供 Thymeleaf 模板使用
        request.setAttribute("goodsDetail", goodsDetailVO);

        // 查询该商品的评论列表,直出到页面(与商品详情同一次请求渲染)
        List<NewBeeMallGoodsReviewVO> reviews = newBeeMallGoodsReviewService.getReviewListByGoodsId(goodsId);
        request.setAttribute("reviews", reviews);

        // 返回商品详情页模板
        return "mall/detail";
    }
}
