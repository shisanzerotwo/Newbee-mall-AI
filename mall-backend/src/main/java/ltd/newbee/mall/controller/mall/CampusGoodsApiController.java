package ltd.newbee.mall.controller.mall;

import jakarta.servlet.http.HttpSession;
import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.controller.vo.CampusGoodsVO;
import ltd.newbee.mall.controller.vo.NewBeeMallUserVO;
import ltd.newbee.mall.dao.CampusWantMapper;
import ltd.newbee.mall.dao.GoodsCategoryMapper;
import ltd.newbee.mall.dao.MallUserMapper;
import ltd.newbee.mall.entity.MallUser;
import ltd.newbee.mall.dao.NewBeeMallGoodsMapper;
import ltd.newbee.mall.entity.GoodsCategory;
import ltd.newbee.mall.entity.NewBeeMallGoods;
import ltd.newbee.mall.util.PageQueryUtil;
import ltd.newbee.mall.util.Result;
import ltd.newbee.mall.util.ResultGenerator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校园二手商品 REST 接口（M4）。
 *
 * <p>补齐此前缺失的「前端消费层」：项目原有的商品能力是 Thymeleaf 页面（`GET /goods/detail/{id}`），
 * 前端 mall-frontend 需要 JSON；这里新增 {@code /api/goods/**} 只做<b>读</b>与收藏，
 * 写入类（发布/下单）二期接入。</p>
 *
 * <p>价格：实体存「分」，VO 换算为「元」；上架状态沿用项目约定 {@code 0 = 上架}。</p>
 */
@RestController
@RequestMapping("/api/goods")
public class CampusGoodsApiController {

    /** 演示期卖家：真实场景应由商品所属用户聚合（二期） */
    private static final CampusGoodsVO.Seller DEMO_SELLER =
            new CampusGoodsVO.Seller(null, "王同学", 4.9, true, "伊犁师范大学 · 本部");

    private final NewBeeMallGoodsMapper goodsMapper;
    private final GoodsCategoryMapper categoryMapper;
    private final CampusWantMapper wantMapper;
    private final MallUserMapper userMapper;

    public CampusGoodsApiController(NewBeeMallGoodsMapper goodsMapper,
                                    GoodsCategoryMapper categoryMapper,
                                    CampusWantMapper wantMapper,
                                    MallUserMapper userMapper) {
        this.goodsMapper = goodsMapper;
        this.categoryMapper = categoryMapper;
        this.wantMapper = wantMapper;
        this.userMapper = userMapper;
    }

    /**
     * 推荐流（首页瀑布流）。
     * 默认只返回校园二手分类（categoryId=900 子树）的在售商品——即商品 id ≥ 90000 的种子数据；
     * 参数 {@code campusOnly=false} 可回退到全站商品（保留商城演示数据）。
     */
    @GetMapping("/recommend")
    public Result recommend(@RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int limit,
                            @RequestParam(defaultValue = "true") boolean campusOnly) {
        List<CampusGoodsVO> list = query(page, limit, null, null, "new", campusOnly);
        return ResultGenerator.genSuccessResult(pageResult(list, page, limit));
    }

    /** 关键词搜索（首页搜索框 / 分类跳转） */
    @GetMapping("/search")
    public Result search(@RequestParam String keyword,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "10") int limit,
                         @RequestParam(defaultValue = "true") boolean campusOnly) {
        List<CampusGoodsVO> list = query(page, limit, keyword, null, "new", campusOnly);
        return ResultGenerator.genSuccessResult(pageResult(list, page, limit));
    }

    /** 分类商品（分类页） */
    @GetMapping("/byCategory")
    public Result byCategory(@RequestParam Long categoryId,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "10") int limit) {
        List<CampusGoodsVO> list = query(page, limit, null, categoryId, "new", false);
        return ResultGenerator.genSuccessResult(pageResult(list, page, limit));
    }

    /** 商品详情（含卖家卡片、想要数） */
    @GetMapping("/{goodsId}")
    public Result detail(@PathVariable Long goodsId) {
        NewBeeMallGoods g = goodsMapper.selectByPrimaryKey(goodsId);
        if (g == null) {
            return ResultGenerator.genFailResult("商品不存在");
        }
        String categoryName = categoryNameOf(g.getGoodsCategoryId());
        return ResultGenerator.genSuccessResult(
                CampusGoodsVO.from(g, categoryName, Math.abs(goodsId.hashCode() % 400) + 20, sellerOf(g)));
    }

    /** 校园二手分类树（一级 + 二级） */
    @GetMapping("/categories")
    public Result categories() {
        Map<String, Object> params = new HashMap<>();
        params.put("page", 1);
        params.put("limit", 200);
        List<GoodsCategory> all = categoryMapper.findGoodsCategoryList(new PageQueryUtil(params));
        List<Map<String, Object>> tree = new ArrayList<>();
        Map<Long, Map<String, Object>> index = new LinkedHashMap<>();
        for (GoodsCategory c : all) {
            if (c.getCategoryLevel() == null || c.getCategoryLevel() != 1) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", c.getCategoryId());
            node.put("name", c.getCategoryName());
            node.put("children", new ArrayList<Map<String, Object>>());
            tree.add(node);
            index.put(c.getCategoryId(), node);
        }
        for (GoodsCategory c : all) {
            if (c.getCategoryLevel() == null || c.getCategoryLevel() != 2) {
                continue;
            }
            Map<String, Object> parent = index.get(c.getParentId());
            if (parent == null) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", c.getCategoryId());
            node.put("name", c.getCategoryName());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
            children.add(node);
        }
        return ResultGenerator.genSuccessResult(tree);
    }

    /** 加入 / 取消「想要」（收藏）。返回 {wanted: true/false, wantCount: n} */
    @PostMapping("/{goodsId}/want")
    public Result toggleWant(@PathVariable Long goodsId, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        boolean wanted = wantMapper.countByUserAndGoods(userId, goodsId) > 0;
        if (wanted) {
            wantMapper.delete(userId, goodsId);
        } else {
            wantMapper.insert(userId, goodsId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("wanted", !wanted);
        data.put("wantCount", wantMapper.countByUser(userId));
        return ResultGenerator.genSuccessResult(data);
    }

    /** 我想要的商品列表 */
    @GetMapping("/wants/mine")
    public Result myWants(HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        List<Long> ids = wantMapper.selectGoodsIdsByUser(userId);
        List<CampusGoodsVO> list = new ArrayList<>();
        for (Long id : ids) {
            NewBeeMallGoods g = goodsMapper.selectByPrimaryKey(id);
            if (g != null) {
                list.add(CampusGoodsVO.from(g, categoryNameOf(g.getGoodsCategoryId()), 0, sellerOf(g)));
            }
        }
        return ResultGenerator.genSuccessResult(list);
    }

    /** 取消想要（DELETE 语义的显式入口，供小程序等使用） */
    @DeleteMapping("/{goodsId}/want")
    public Result removeWant(@PathVariable Long goodsId, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        wantMapper.delete(userId, goodsId);
        return ResultGenerator.genSuccessResult(Map.of("wanted", false, "wantCount", wantMapper.countByUser(userId)));
    }

    /**
     * 发布闲置（写接口）。
     *
     * <p>入参为前端发布页表单：{@code {title, desc, category, condition, price(元), tradeType, cover}}。
     * 落库规则：价格元 → 分；{@code tag} 由「成色,交易方式(,急出)」拼装（与种子数据同构，
     * 便于知识库与前端解析）；分类名映射到校园二手分类树（901-906），未匹配则挂 900。</p>
     */
    @PostMapping
    public Result publish(@RequestBody PublishRequest body, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录后再发布");
        }
        if (body == null || body.title() == null || body.title().isBlank() || body.price() == null) {
            return ResultGenerator.genFailResult("请填写标题与价格");
        }
        NewBeeMallGoods g = new NewBeeMallGoods();
        g.setGoodsName(body.title().trim());
        g.setGoodsIntro(body.desc() == null || body.desc().isBlank()
                ? body.title().trim() : truncate(body.desc().trim(), 190));
        g.setGoodsDetailContent(body.desc() == null ? "" : body.desc());
        g.setGoodsCategoryId(campusCategoryId(body.category()));
        String cover = body.cover() == null || body.cover().isBlank()
                ? "/campus/placeholder.jpg" : body.cover();
        g.setGoodsCoverImg(cover);
        g.setGoodsCarousel(cover);
        g.setOriginalPrice(yuanToFen(body.price()));
        g.setSellingPrice(yuanToFen(body.price()));
        g.setStockNum(1);                                  // 二手闲置默认唯一一件
        g.setTag(buildTag(body));
        g.setGoodsSellStatus((byte) 0);                    // 0 = 上架
        g.setCreateUser(userId.intValue());
        g.setUpdateUser(userId.intValue());
        if (goodsMapper.insertSelective(g) <= 0) {
            return ResultGenerator.genFailResult("发布失败，请稍后再试");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goodsId", g.getGoodsId());
        data.put("message", "发布成功");
        return ResultGenerator.genSuccessResult(data);
    }

    // ------------------------------------------------------------------

    private static int yuanToFen(Double yuan) {
        return yuan == null ? 0 : (int) Math.round(yuan * 100);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 标签拼装：成色,交易方式(,急出) —— 与种子数据同构，供 VO 解析 */
    private static String buildTag(PublishRequest body) {
        StringBuilder sb = new StringBuilder();
        if (body.condition() != null && !body.condition().isBlank()) {
            sb.append(body.condition().trim());
        }
        if (body.tradeType() != null && !body.tradeType().isBlank()) {
            sb.append(sb.length() > 0 ? "," : "").append(body.tradeType().trim());
        }
        if (Boolean.TRUE.equals(body.urgent())) {
            sb.append(sb.length() > 0 ? "," : "").append("急出");
        }
        return sb.length() > 20 ? sb.substring(0, 20) : sb.toString();
    }

    /** 分类名 → 校园二手分类树 id（901 教材书籍 / 902 数码电子 / 903 生活宿舍 / 904 服饰鞋包 / 905 运动出行 / 906 票券卡务） */
    private static Long campusCategoryId(String categoryName) {
        if (categoryName == null) {
            return 900L;
        }
        return switch (categoryName.trim()) {
            case "教材书籍", "教材", "书籍" -> 901L;
            case "数码电子", "数码", "电子" -> 902L;
            case "生活宿舍", "生活", "宿舍" -> 903L;
            case "服饰鞋包", "服饰", "鞋包" -> 904L;
            case "运动出行", "运动", "出行" -> 905L;
            case "票券卡务", "票券", "卡务" -> 906L;
            default -> 900L;
        };
    }

    public record PublishRequest(String title, String desc, String category, String condition,
                                 Double price, String tradeType, Boolean urgent, String cover) {
    }

    // ------------------------------------------------------------------

    private List<CampusGoodsVO> query(int page, int limit, String keyword, Long categoryId,
                                      String orderBy, boolean campusOnly) {
        Map<String, Object> params = new HashMap<>();
        params.put("page", Math.max(1, page));
        params.put("limit", Math.min(Math.max(1, limit), 50));
        params.put("keyword", keyword == null || keyword.isBlank() ? null : keyword.trim());
        params.put("goodsCategoryId", categoryId);
        params.put("goodsSellStatus", 0);   // 0 = 上架
        params.put("orderBy", orderBy);
        PageQueryUtil pageUtil = new PageQueryUtil(params);
        // 校园商品在 SQL 层过滤（分页后过滤会漏数据）
        List<NewBeeMallGoods> goodsList = campusOnly
                ? goodsMapper.findCampusGoodsList(pageUtil)
                : goodsMapper.findNewBeeMallGoodsListBySearch(pageUtil);

        List<CampusGoodsVO> list = new ArrayList<>();
        for (NewBeeMallGoods g : goodsList) {
            list.add(CampusGoodsVO.from(g, categoryNameOf(g.getGoodsCategoryId()),
                    Math.abs(g.getGoodsId().hashCode() % 400) + 20, sellerOf(g)));
        }
        return list;
    }

    /**
     * 是否校园二手商品：分类挂 900-930（校园二手分类树）或 id ≥ 90000（种子数据段）。
     * 注：列表查询已在 SQL 层过滤（findCampusGoodsList），此方法供单条判断使用。
     */
    private boolean isCampus(NewBeeMallGoods g) {
        if (g.getGoodsId() != null && g.getGoodsId() >= 90000) {
            return true;
        }
        Long catId = g.getGoodsCategoryId();
        if (catId == null) {
            return false;
        }
        return catId >= 900 && catId <= 930;
    }

    /**
     * 构造卖家卡片：按商品的 {@code create_user} 聚合卖家身份（私聊会话的 peerId 即此 userId）；
     * create_user ≤ 0 视为演示数据，回落到统一演示卖家。
     */
    private CampusGoodsVO.Seller sellerOf(NewBeeMallGoods g) {
        Integer createUser = g.getCreateUser();
        if (createUser == null || createUser <= 0) {
            return DEMO_SELLER;
        }
        Long uid = createUser.longValue();
        return new CampusGoodsVO.Seller(uid, nickNameOf(uid), 4.9, true, "伊犁师范大学 · 本部");
    }

    private String nickNameOf(Long userId) {
        MallUser u = userMapper.selectByPrimaryKey(userId);
        return u == null || u.getNickName() == null || u.getNickName().isBlank() ? "同学" : u.getNickName();
    }

    private String categoryNameOf(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        GoodsCategory c = categoryMapper.selectByPrimaryKey(categoryId);
        return c == null ? null : c.getCategoryName();
    }

    private Long currentUserId(HttpSession session) {
        Object attr = session == null ? null : session.getAttribute(Constants.MALL_USER_SESSION_KEY);
        return attr instanceof NewBeeMallUserVO vo ? vo.getUserId() : null;
    }

    private Map<String, Object> pageResult(List<?> list, int page, int limit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("page", page);
        data.put("hasMore", list.size() >= Math.min(Math.max(1, limit), 50));
        return data;
    }
}
