package ltd.newbee.mall.controller.mall;

import ltd.newbee.mall.entity.NewBeeMallGoods;
import ltd.newbee.mall.service.NewBeeMallGoodsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 客服完整页（M3-A）：{@code GET /cs}。
 *
 * <p>这是本项目最初痛点的正面解 —— 旧实现用 iframe 套一个外部客服站，
 * 所以「客服」在页面里是<b>格格不入的一块</b>。现在它是同一个应用的一个路由：
 * 同一套 header/footer、同一套 CSS 变量与主题（{@code data-theme}），
 * 与商城其它页面没有任何视觉断层。<b>刻意没有 iframe。</b>
 *
 * <h3>上下文带入（DESIGN §4.3）</h3>
 * <ul>
 *   <li>{@code goodsId}：服务端回查商品名，首屏提示「正在咨询：xxx」。
 *       之所以敢回查，是因为<b>在售商品信息本来就是公开数据</b>（商品详情页人人可看）。</li>
 *   <li>{@code orderNo}：<b>只做透传、绝不回查</b>。订单是隐私数据，此处没有做归属校验的能力，
 *       回查就会把别人的订单号变成可读信息（越权泄露）。真正的订单校验在
 *       {@code MallTools.queryOrder} 里按登录身份做 —— 页面只负责把号码带进对话。</li>
 * </ul>
 *
 * <p>商品查不到（id 不存在/已下架）时<b>不让页面 500</b>：静默降级为「无商品上下文」。
 * 客服页本身没有理由因为一个过期链接而打不开。
 */
@Controller
public class CsPageController {

    private static final Logger log = LoggerFactory.getLogger(CsPageController.class);

    private final NewBeeMallGoodsService goodsService;

    public CsPageController(NewBeeMallGoodsService goodsService) {
        this.goodsService = goodsService;
    }

    @GetMapping("/cs")
    public String csPage(@RequestParam(value = "goodsId", required = false) Long goodsId,
                         @RequestParam(value = "orderNo", required = false) String orderNo,
                         Model model) {
        if (goodsId != null && goodsId > 0) {
            model.addAttribute("goodsId", goodsId);
            model.addAttribute("goodsName", lookupGoodsName(goodsId));
        }
        // 订单号只透传（前端也会拿它去问客服）；空白串视为没带
        if (orderNo != null && !orderNo.isBlank()) {
            model.addAttribute("orderNo", orderNo.trim());
        }
        return "mall/cs";
    }

    /** 商品名；查询失败只记日志并返回 {@code null}（模板据此不显示上下文条） */
    private String lookupGoodsName(Long goodsId) {
        try {
            NewBeeMallGoods goods = goodsService.getNewBeeMallGoodsById(goodsId);
            return goods == null ? null : goods.getGoodsName();
        } catch (Exception e) {
            log.warn("客服页回查商品名失败（不影响页面渲染）：goodsId={}，原因={}", goodsId, e.toString());
            return null;
        }
    }
}
