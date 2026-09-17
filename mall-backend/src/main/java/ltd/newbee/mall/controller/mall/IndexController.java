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
import ltd.newbee.mall.common.IndexConfigTypeEnum;
import ltd.newbee.mall.common.NewBeeMallException;
import ltd.newbee.mall.controller.vo.NewBeeMallIndexCarouselVO;
import ltd.newbee.mall.controller.vo.NewBeeMallIndexCategoryVO;
import ltd.newbee.mall.controller.vo.NewBeeMallIndexConfigGoodsVO;
import ltd.newbee.mall.service.NewBeeMallCarouselService;
import ltd.newbee.mall.service.NewBeeMallCategoryService;
import ltd.newbee.mall.service.NewBeeMallIndexConfigService;
import org.springframework.stereotype.Controller;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 首页控制器
 * 负责处理首页门户请求，展示轮播图、商品分类、热销/新品/推荐商品等信息
 * 这是用户进入商城后看到的第一个页面
 *
 * @author 13
 */
@Controller
public class IndexController {

    @Resource // 注入轮播图服务
    private NewBeeMallCarouselService newBeeMallCarouselService;

    @Resource // 注入首页配置服务（热销、新品、推荐等配置）
    private NewBeeMallIndexConfigService newBeeMallIndexConfigService;

    @Resource // 注入分类服务
    private NewBeeMallCategoryService newBeeMallCategoryService;

    /**
     * 首页入口方法
 *    GET /index, GET /, GET /index.html
     *
     * @param request HTTP 请求对象
     * @return 视图名称 "mall/index"，指向首页模板
     */
    @GetMapping({"/index", "/", "/index.html"}) // 支持多种 URL 方式访问首页
    public String indexPage(HttpServletRequest request) {
        // 获取首页分类数据（构建三级分类树形结构）
        List<NewBeeMallIndexCategoryVO> categories = newBeeMallCategoryService.getCategoriesForIndex();

        // 如果分类数据为空，说明未正确配置，抛出业务异常
        if (CollectionUtils.isEmpty(categories)) {
            NewBeeMallException.fail("分类数据不完善，请先配置商品分类");
        }

        // 获取首页轮播图数据（按排序取前 N 个，默认5个）
        List<NewBeeMallIndexCarouselVO> carousels = newBeeMallCarouselService.getCarouselsForIndex(Constants.INDEX_CAROUSEL_NUMBER);

        // 获取首页热销商品配置（推荐销量高的商品）
        List<NewBeeMallIndexConfigGoodsVO> hotGoodses = newBeeMallIndexConfigService.getConfigGoodsesForIndex(
            IndexConfigTypeEnum.INDEX_GOODS_HOT.getType(),
            Constants.INDEX_GOODS_HOT_NUMBER
        );

        // 获取首页新品商品配置（推荐最新上架的商品）
        List<NewBeeMallIndexConfigGoodsVO> newGoodses = newBeeMallIndexConfigService.getConfigGoodsesForIndex(
            IndexConfigTypeEnum.INDEX_GOODS_NEW.getType(),
            Constants.INDEX_GOODS_NEW_NUMBER
        );

        // 获取首页推荐商品配置（根据算法或人工推荐的相似商品）
        List<NewBeeMallIndexConfigGoodsVO> recommendGoodses = newBeeMallIndexConfigService.getConfigGoodsesForIndex(
            IndexConfigTypeEnum.INDEX_GOODS_RECOMMOND.getType(),
            Constants.INDEX_GOODS_RECOMMOND_NUMBER
        );

        // 将所有数据设置到 Request 中，供 Thymeleaf 模板使用
        request.setAttribute("categories", categories);      // 分类数据
        request.setAttribute("carousels", carousels);       // 轮播图数据
        request.setAttribute("hotGoodses", hotGoodses);     // 热销商品
        request.setAttribute("newGoodses", newGoodses);     // 新品商品
        request.setAttribute("recommendGoodses", recommendGoodses); // 推荐商品

        // 返回前台首页模板
        return "mall/index";
    }
}
