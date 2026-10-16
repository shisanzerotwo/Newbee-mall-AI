package ltd.newbee.mall.controller.mall;

import jakarta.servlet.http.HttpSession;
import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.controller.vo.CampusOrderItemVO;
import ltd.newbee.mall.controller.vo.NewBeeMallUserVO;
import ltd.newbee.mall.dao.CampusOrderMapper;
import ltd.newbee.mall.dao.NewBeeMallGoodsMapper;
import ltd.newbee.mall.entity.CampusOrder;
import ltd.newbee.mall.entity.NewBeeMallGoods;
import ltd.newbee.mall.util.Result;
import ltd.newbee.mall.util.ResultGenerator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 校园二手订单与支付（M5）。
 *
 * <p>支付为<b>模拟收银台</b>：校园二手以「校内自提当面验货」为主，真实支付需接学校/第三方通道；
 * 这里把下单 → 待支付 → 已支付待自提 → 完成的<b>状态机与跳转链路</b>做实，便于后续替换真实支付网关。</p>
 *
 * <p>防重复：{@code countActiveByGoods} 拦截同一商品重复下单；{@code updateStatus} 带前置状态条件，
 * 避免重复支付/取消的竞态。</p>
 */
@RestController
@RequestMapping("/api/order")
public class CampusOrderApiController {

    private static final String DEFAULT_PICKUP = "本部快递服务中心自提点";

    private final CampusOrderMapper orderMapper;
    private final NewBeeMallGoodsMapper goodsMapper;

    public CampusOrderApiController(CampusOrderMapper orderMapper, NewBeeMallGoodsMapper goodsMapper) {
        this.orderMapper = orderMapper;
        this.goodsMapper = goodsMapper;
    }

    /** 下单（生成待支付订单） */
    @PostMapping("/create")
    public Result create(@RequestBody CreateRequest body, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录后再下单");
        }
        if (body == null || body.goodsId() == null) {
            return ResultGenerator.genFailResult("缺少商品");
        }
        NewBeeMallGoods g = goodsMapper.selectByPrimaryKey(body.goodsId());
        if (g == null) {
            return ResultGenerator.genFailResult("商品不存在");
        }
        if (g.getGoodsSellStatus() == null || g.getGoodsSellStatus() != 0) {
            return ResultGenerator.genFailResult("商品已下架，无法购买");
        }
        Long sellerId = g.getCreateUser() == null ? 0L : g.getCreateUser().longValue();
        if (sellerId == userId) {
            return ResultGenerator.genFailResult("不能购买自己发布的商品");
        }
        if (orderMapper.countActiveByGoods(body.goodsId(), userId) > 0) {
            return ResultGenerator.genFailResult("您已有该商品的进行中订单，请先完成或取消");
        }
        CampusOrder order = new CampusOrder();
        order.setOrderNo(generateOrderNo(userId));
        order.setBuyerId(userId);
        order.setSellerId(sellerId);
        order.setGoodsId(g.getGoodsId());
        order.setGoodsTitle(g.getGoodsName());
        order.setPriceFen(g.getSellingPrice());
        order.setStatus(CampusOrder.STATUS_UNPAID);
        order.setPayType(null);
        order.setPickupPoint(body.pickupPoint() == null || body.pickupPoint().isBlank()
                ? DEFAULT_PICKUP : body.pickupPoint());
        orderMapper.insert(order);
        return ResultGenerator.genSuccessResult(toVO(order));
    }

    /** 订单详情（收银台按此渲染） */
    @GetMapping("/{orderNo}")
    public Result detail(@PathVariable String orderNo, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        CampusOrder order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ResultGenerator.genFailResult("订单不存在");
        }
        if (!userId.equals(order.getBuyerId()) && !userId.equals(order.getSellerId())) {
            return ResultGenerator.genErrorResult(403, "无权查看该订单");
        }
        return ResultGenerator.genSuccessResult(toVO(order));
    }

    /** 支付（模拟收银台；二期替换为微信/支付宝预下单 + 回调） */
    @PostMapping("/{orderNo}/pay")
    public Result pay(@PathVariable String orderNo, @RequestBody(required = false) PayRequest body,
                      HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        CampusOrder order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ResultGenerator.genFailResult("订单不存在");
        }
        if (!userId.equals(order.getBuyerId())) {
            return ResultGenerator.genErrorResult(403, "只有买家可以支付该订单");
        }
        if (order.getStatus() != CampusOrder.STATUS_UNPAID) {
            return ResultGenerator.genFailResult("订单状态为「" + statusText(order.getStatus()) + "」，无需重复支付");
        }
        String payType = body == null || body.payType() == null || body.payType().isBlank()
                ? "微信支付" : body.payType();
        int updated = orderMapper.updateStatus(orderNo, CampusOrder.STATUS_UNPAID,
                CampusOrder.STATUS_PAID, payType);
        if (updated == 0) {
            return ResultGenerator.genFailResult("支付失败，订单状态已变化，请刷新重试");
        }
        CampusOrder paid = orderMapper.selectByOrderNo(orderNo);
        return ResultGenerator.genSuccessResult(toVO(paid));
    }

    /** 取消订单（仅待支付可取消） */
    @PostMapping("/{orderNo}/cancel")
    public Result cancel(@PathVariable String orderNo, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        CampusOrder order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ResultGenerator.genFailResult("订单不存在");
        }
        if (!userId.equals(order.getBuyerId())) {
            return ResultGenerator.genErrorResult(403, "只有买家可以取消该订单");
        }
        int updated = orderMapper.updateStatus(orderNo, CampusOrder.STATUS_UNPAID,
                CampusOrder.STATUS_CANCELED, null);
        if (updated == 0) {
            return ResultGenerator.genFailResult("订单状态已变化，无法取消");
        }
        return ResultGenerator.genSuccessResult(Map.of("orderNo", orderNo, "status", CampusOrder.STATUS_CANCELED));
    }

    /** 确认自提完成（买家或卖家均可触发，二期由扫码核销） */
    @PostMapping("/{orderNo}/confirm")
    public Result confirm(@PathVariable String orderNo, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        CampusOrder order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ResultGenerator.genFailResult("订单不存在");
        }
        if (!userId.equals(order.getBuyerId()) && !userId.equals(order.getSellerId())) {
            return ResultGenerator.genErrorResult(403, "无权操作该订单");
        }
        int updated = orderMapper.updateStatus(orderNo, CampusOrder.STATUS_PAID,
                CampusOrder.STATUS_DONE, null);
        if (updated == 0) {
            return ResultGenerator.genFailResult("订单状态已变化，无法确认完成");
        }
        return ResultGenerator.genSuccessResult(Map.of("orderNo", orderNo, "status", CampusOrder.STATUS_DONE));
    }

    /** 我的订单（买/卖双向） */
    @GetMapping("/mine")
    public Result mine(HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        List<CampusOrderItemVO> rows = orderMapper.selectOrdersByUser(userId, 50);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CampusOrderItemVO item : rows) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("orderNo", item.getOrderNo());
            node.put("goodsId", item.getGoodsId());
            node.put("goodsTitle", item.getGoodsTitle());
            node.put("price", item.getPriceFen() == null ? null : item.getPriceFen() / 100.0);
            node.put("status", item.getStatus());
            node.put("statusText", statusText(item.getStatus()));
            node.put("payType", item.getPayType());
            node.put("sellerId", item.getSellerId());
            node.put("pickupPoint", item.getPickupPoint());
            node.put("createTime", item.getCreateTime() == null ? ""
                    : new SimpleDateFormat("MM-dd HH:mm").format(item.getCreateTime()));
            out.add(node);
        }
        return ResultGenerator.genSuccessResult(out);
    }

    // ------------------------------------------------------------------

    private Map<String, Object> toVO(CampusOrder o) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("orderNo", o.getOrderNo());
        vo.put("buyerId", o.getBuyerId());
        vo.put("sellerId", o.getSellerId());
        vo.put("goodsId", o.getGoodsId());
        vo.put("goodsTitle", o.getGoodsTitle());
        vo.put("price", o.getPriceFen() == null ? null : o.getPriceFen() / 100.0);
        vo.put("payType", o.getPayType());
        vo.put("status", o.getStatus());
        vo.put("statusText", statusText(o.getStatus()));
        vo.put("pickupPoint", o.getPickupPoint());
        vo.put("createTime", o.getCreateTime() == null ? ""
                : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(o.getCreateTime()));
        vo.put("payTime", o.getPayTime() == null ? ""
                : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(o.getPayTime()));
        return vo;
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case CampusOrder.STATUS_UNPAID -> "待支付";
            case CampusOrder.STATUS_PAID -> "已支付，待自提";
            case CampusOrder.STATUS_DONE -> "交易完成";
            case CampusOrder.STATUS_CANCELED -> "已取消";
            default -> "未知状态";
        };
    }

    /** 订单号：yyyyMMddHHmmss + 4 位随机（演示规模足够；生产应换成雪花/全局唯一序列） */
    private static String generateOrderNo(Long userId) {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date())
                + userId + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private Long currentUserId(HttpSession session) {
        Object attr = session == null ? null : session.getAttribute(Constants.MALL_USER_SESSION_KEY);
        return attr instanceof NewBeeMallUserVO vo ? vo.getUserId() : null;
    }

    public record CreateRequest(Long goodsId, String pickupPoint) {
    }

    public record PayRequest(String payType) {
    }
}