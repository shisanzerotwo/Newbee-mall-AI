/**
 * =====================================================================
 * Constants.java - 系统常量配置类
 *
 * 【项目背景】
 * 在大型项目中，硬编码的各种数字、字符串"魔法值"会极大降低代码的可读性和可维护性。
 * 每当需求变更（如轮播图从5张改6张），就需要到处搜索修改。这类值集中定义为常量后，
 * 一处修改，全局生效，是良好的软件工程实践。
 *
 * 【核心功能】
 * 1. 集中定义系统级常量，避免多处硬编码带来的维护困难
 * 2. 提供统一的命名规范，使常量用途一目了然
 * 3. 便于后续根据业务需求调整阈值和限制条件
 * 4. 作为系统配置的入口点，新开发者只需阅读该类即可了解系统的基本参数设置
 *
 * 【分类说明】
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 文件路径：src/main/java/ltd/newbee/mall/common/Constants.java │
 * │ 所属模块：common包（全局共享）                               │
 * │ 访问方式：通过 Constants.XXX 静态访问                         │
 * └─────────────────────────────────────────────────────────────┘
 *
 * ━�━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【常量分组详解】
 *
 * ── 文件上传配置 ──────────────────────────────────────
 * FILE_UPLOAD_DIC = "D:\\upload\\"
 *   • 功能：服务器本地文件存储的根目录路径
 *   • 用处：Controller中上传图片时保存到此目录，前端请求时从该目录读取
 *   • 用法示例：String filePath = Constants.FILE_UPLOAD_DIC + imageName;
 *   • 注意：生产环境需改为Web服务器可读的路径（如/opt/image/upload或Nginx静态资源目录）
 *
 * ── 首页展示数量限制 ───────────────────────────────────
 * INDEX_CAROUSEL_NUMBER = 5
 *   • 功能：首页轮播图最大显示数量
 *   • 用处：防止轮播图过多影响页面加载速度或用户体验
 *   • 使用位置：CarouselService.getCarouselsForIndex(INDEX_CAROUSEL_NUMBER)
 *   • 修改建议：可根据实际设计稿调整，但一般不超过7张
 *
 * INDEX_CATEGORY_NUMBER = 10
 *   • 功能：首页一级分类展示的最大数量
 *   • 用处：控制首页导航栏的分类项数，保持菜单简洁
 *   • 使用位置：CategoryService.getCategoriesForIndex()
 *   • 建议：8-12个为宜，太多会导致菜单过于拥挤
 *
 * SEARCH_CATEGORY_NUMBER = 8
 *   • 功能：搜索页左侧分类导航的一级分类最大数量
 *   • 用处：限制筛选面板的展示深度
 *   • 与INDEX_CATEGORY_NUMBER的区别：搜索页空间有限，可适当减少
 *
 * ── 首页商品展示数量 ───────────────────────────────────
 * INDEX_GOODS_HOT_NUMBER = 4
 *   • 功能："热销商品"板块推荐的商品数量
 *   • 用处：控制首页焦点区域的商品推荐密度
 *   • 对应模板：/src/main/resources/templates/mall/index.html 中的 "hotGoodses"
 *
 * INDEX_GOODS_NEW_NUMBER = 5
 *   • 功能："新品上线"板块推荐的商品数量
 * • 同上原理
 *
 * INDEX_GOODS_RECOMMOND_NUMBER = 10
 *   • 功能："为你推荐"板块推荐的商品数量
 *   • 推荐类模块可适当多展示一些（最多10-12个），提升转化率
 *
 * ── 购物车限制 ─────────────────────────────────────────
 * SHOPPING_CART_ITEM_TOTAL_NUMBER = 13
 *   • 功能：购物车中允许存放的不同商品的种类总数上限
 *   • 用处：防止用户添加过多商品导致页面卡顿或下单复杂度爆炸
 * • 实际业务中这个值可以设得更大，13是个保守值
 *
 * SHOPPING_CART_ITEM_LIMIT_NUMBER = 5
 *   • 功能：单个商品在购物车中的最大购买数量
 *   • 用处：防止恶意刷单、库存被一次性清空
 * • 更合理的做法：此限制应动态匹配商品实时库存，而不是固定值
 *
 * ── Session与Key配置 ──────────────────────────────────
 * MALL_VERIFY_CODE_KEY = "mallVerifyCode"
 *   • 功能：前台验证码在Session中的键名
 *   • 用处：LoginController生成验证码时存入session，验证时从同一key取出比对
 *
 * MALL_USER_SESSION_KEY = "newBeeMallUser"
 *   • 功能：登录用户信息在Session中的键名
 *   • 用处：用户登录后将用户VO存入此key，各页面通过此key获取当前用户信息
 *
 * ── 分页配置 ───────────────────────────────────────────
 * GOODS_SEARCH_PAGE_LIMIT = 10
 *   • 功能：商品搜索列表每页默认显示的记录条数
 *   • 用处：控制搜索结果的分页大小，平衡加载性能和翻页次数
 * • 常见取值：10、15、20，不宜过大（超过50会影响首屏加载速度）
 *
 * ORDER_SEARCH_PAGE_LIMIT = 3
 *   • 功能：订单列表每页默认显示的记录条数
 *   • 用处：订单数据通常不多，每页3-5条足够用户查看历史记录
 * • 设置为较小值因为订单历史一般不会很长
 *
 * ── 商品状态标志 ──────────────────────────────────────
 * SELL_STATUS_UP = 0
 *   • 功能：商品上架状态的标识值
 *   • 含义：商品在售，用户可以正常浏览和购买
 * • 商品新建后默认就是这个状态
 *
 * SELL_STATUS_DOWN = 1
 *   • 功能：商品下架状态的标识值
 *   • 含义：商品已下架，不在前台展示，用户无法购买
 * • 可用于临时停售某个商品（如缺货时），而非直接删除
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【重要提醒】
 * 1. 所有final static变量都是全局只读的，从任何地方都能访问
 * 2. 修改前需评估对系统整体的影响（如修改购物车限制可能需要调整UI）
 * 3. 如果常量需要频繁变动（如促销期间的活动规则），应改用数据库配置表
 * 4. FILE_UPLOAD_DIC路径要确保Web进程有读写权限，生产环境建议使用独立文件服务
 * =====================================================================
 */
package ltd.newbee.mall.common;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote 系统常量配置类
 *          集中定义全局常量，供全系统各处统一使用。
 *          所有常量均为final static，一旦初始化不可更改。
 */
public class Constants {
    // ========================================
    // 【文件上传配置】
    // ========================================

    /**
     * 文件上传目录
     * 【功能】服务器本地用于存储用户上传文件（如商品图片、头像等）的物理目录路径
     * 【用处】Controller处理图片上传时将文件保存至此路径，后续请求通过该路径拼接URL访问
     * 【注意】生产环境应当：
     * 1. 路径指向Web服务器能公开访问的目录（最好放在/var/www/static/images等）
     * 2. 或者使用云对象存储（阿里云OSS、腾讯云COS等），此时此处为OSS的域名前缀
     * 3. 此处Windows路径仅用于开发测试
     */
    public final static String FILE_UPLOAD_DIC = "D:\\upload\\";

    // ========================================
    // 【首页轮播图配置】
    // ========================================

    /**
     * 首页轮播图数量限制
     * 【功能】决定首页最多展示多少个轮播图
     * 【用处】在获取轮播图数据时作为查询参数传入：service.getCarouselsForIndex(INDEX_CAROUSEL_NUMBER)
     * 【业务逻辑】轮播图按排序字段（carousel_rank）升序排列，取前N条
     */
    public final static int INDEX_CAROUSEL_NUMBER = 5;

    // ========================================
    // 【首页分类展示配置】
    // ========================================

    /**
     * 首页一级分类最大数量
     * 【功能】限制首页顶部导航栏展示的一级分类个数
     * 【用处】首页一级分类菜单项数不宜过多，否则影响导航体验
     * 【业务逻辑】CategoryService会根据此限制只返回前N个一级分类
     */
    public final static int INDEX_CATEGORY_NUMBER = 10;

    /**
     * 搜索页一级分类最大数量
     * 【功能】搜索页面左侧筛选区域显示的一级分类个数上限
     * 【用处】搜索页同样需要控制分类数量，但这个值通常比首页略少
     * 【对比】SEARCH_CATEGORY_NUMBER < INDEX_CATEGORY_NUMBER，因为搜索页空间更局促
     */
    public final static int SEARCH_CATEGORY_NUMBER = 8;

    // ========================================
    // 【首页商品推荐数量配置】
    // ========================================

    /**
     * 首页热卖商品数量
     * 【功能】"热销商品"板块展示的商品条数
     * 【用处】首页焦点区域的商品推荐数量，需控制版面不会太拥挤
     * 【相关】对应模板中的 hotGoodses 变量，遍历显示
     */
    public final static int INDEX_GOODS_HOT_NUMBER = 4;

    /**
     * 首页新品商品数量
     * 【功能】"新品上线"板块展示的商品条数
     * 【用处】展示最新上架的商品，鼓励用户发现新品
     * 【排序依据】按商品创建时间降序取前N条
     */
    public final static int INDEX_GOODS_NEW_NUMBER = 5;

    /**
     * 首页推荐商品数量
     * 【功能】"为你推荐"/"猜你喜欢"板块展示的商品条数
     * 【用处】推荐类模块可以适当多一些，给用户更多选择空间
     * 【算法】可能基于浏览历史、购买记录协同过滤，或是人工配置
     */
    public final static int INDEX_GOODS_RECOMMOND_NUMBER = 10;

    // ========================================
    // 【购物车限制配置】
    // ========================================

    /**
     * 购物车商品种类最大数量
     * 【功能】允许添加到购物车的不同商品的总种类数上限
     * 【用处】防止购物车过于臃肿，简化结算流程；也可防刷单
     * 【业务校验】ShoppingCartService.addCartItem()会检查当前种类数是否已达上限
     * 【注】13种的限制偏保守，可按实际需求调大（如50-100）
     */
    public final static int SHOPPING_CART_ITEM_TOTAL_NUMBER = 13;

    /**
     * 购物车单品最大购买数量
     * 【功能】单个商品一次能添加到购物车的最大数量
     * 【用处】防止恶意抢购、库存瞬间售罄；避免用户误操作大量添加
     * 【业务校验】加入购物车时会校验：newQuantity <= currentStock && newQuantity <= SHOPPING_CART_ITEM_LIMIT_NUMBER
     */
    public final static int SHOPPING_CART_ITEM_LIMIT_NUMBER = 5;

    // ========================================
    // 【Session Key配置】
    // ========================================

    /**
     * 验证码Session键名
     * 【功能】存放验证码图片信息的session attribute name
     * 【用处】CommonController生成验证码时：session.setAttribute("verifyCode", captcha);
     *                          PersonalController验证时：session.getAttribute("verifyCode")
     *          这里使用了Constants.MALL_VERIFY_CODE_KEY来统一管理key名
     */
    public final static String MALL_VERIFY_CODE_KEY = "mallVerifyCode";

    /**
     * 用户信息Session键名
     * 【功能】存放当前登录用户信息的session attribute name
     * 【用处】用户登录成功后：session.setAttribute("newBeeMallUser", userVO);
     *          各页面获取当前用户：session.getAttribute("newBeeMallUser")
     *          拦截器也会从这个key判断用户是否已登录
     */
    public final static String MALL_USER_SESSION_KEY = "newBeeMallUser";

    // ========================================
    // 【分页默认配置】
    // ========================================

    /**
     * 商品搜索分页默认条数
     * 【功能】搜索商品时，每页默认显示的商品数量
     * 【用处】PageQueryUtil拿到params.put("limit", GOODS_SEARCH_PAGE_LIMIT)作为默认值
     * 【可调整】根据页面布局调整：10列较紧凑，20列信息量大但加载慢
     */
    public final static int GOODS_SEARCH_PAGE_LIMIT = 10;

    /**
     * 订单列表分页默认条数
     * 【功能】我的订单页面每页默认的订单数量
     * 【用处】OrderController orderListPage方法中设置此值
     * 【原因】用户历史订单通常不多，每条订单记录较多信息，每页3-5条比较合适
     */
    public final static int ORDER_SEARCH_PAGE_LIMIT = 3;

    // ========================================
    // 【商品状态枚举值】
    // ========================================

    /**
     * 商品上架状态
     * 【功能】表示商品处于可销售状态的数值编码
     * 【用处】NewBeeMallGoods实体中的goods_sell_status字段，值为0即表示在售
     * 【业务判断】搜索和详情页面都只展示SELL_STATUS_UP的商品，下架的不显示
     * @see NewBeeMallGoods#getGoodsSellStatus()
     */
    public final static int SELL_STATUS_UP = 0;

    /**
     * 商品下架状态
     * 【功能】表示商品处于不可销售状态的数值编码
     * 【用处】goods_sell_status字段的值为1时表示已下架
     * 【业务场景】缺货、违规、清仓时使用下架而非删除，保留数据记录
     * @see NewBeeMallGoods#getGoodsSellStatus()
     */
    public final static int SELL_STATUS_DOWN = 1;

    // ========================================
    // 【Redis 缓存配置】
    // ========================================

    /**
     * 首页缓存过期时间(秒)
     * 【功能】首页各板块缓存在 Redis 中的默认有效期
     * 【用处】后台修改数据时会主动删除对应 key 保证一致性；
     *         TTL 作为兜底，防止"改了数据但缓存忘了删"导致长期展示旧数据
     */
    public final static int INDEX_CACHE_TTL = 30 * 60;

    /**
     * 首页轮播图缓存 key
     */
    public final static String INDEX_CAROUSEL_CACHE_KEY = "mall:index:carousel";

    /**
     * 首页分类树缓存 key
     */
    public final static String INDEX_CATEGORY_CACHE_KEY = "mall:index:category";

    /**
     * 首页商品推荐位缓存 key 前缀(后面拼 configType)
     * 例：热销 goods:3、新品 goods:4、推荐 goods:5
     */
    public final static String INDEX_GOODS_CACHE_KEY_PREFIX = "mall:index:goods:";

    // ========================================
    // 【订单超时延迟队列配置】
    // ========================================

    /**
     * 订单延迟队列 ZSet 的 key
     * 【功能】member=订单号 orderNo,score=订单到期时间戳(毫秒)
     * 【用处】下单时入队,支付成功出队,定时任务按 score 取出到期的订单自动关闭
     */
    public final static String ORDER_DELAY_QUEUE_KEY = "mall:order:delay";

    /**
     * 待支付订单超时时间(秒)
     * 【功能】下单后 N 秒内未支付,订单自动关闭并恢复库存
     * 【演示】当前设 5 分钟方便演示,真实环境可改为 30*60 秒
     */
    public final static int ORDER_PAY_EXPIRE_SECONDS = 5 * 60;
}
