/**
 * =====================================================================
 * NewBeeMallCartNumberInterceptor.java - 购物车数量更新拦截器
 * 【项目背景】
 * 在新蜂商城系统中，每个页面顶部都有一个购物车图标，显示当前用户购物车中的商品总数（如"3件"）。
 * 这个数量需要从数据库实时查询获取，因为：
 * 1. 用户在任意页面都可以添加/修改购物车项（通过AJAX请求）
 * 2. 购物车数量需要保持与数据库一致，而不是依赖缓存或Session旧值
 * 3. 不同页面刷新时都需要展示最新的购物车状态
 *
 * NewBeeMallCartNumberInterceptor 就是为了解决这个问题而设计的全局拦截器。
 * 它在每次请求到达Controller之前，自动查询用户的购物车总数量并更新到Session中的User对象里。
 *
 * 【核心价值与设计理念】
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * ✦ 设计理念：自动同步，不侵入业务代码                                │
 *   - 业务层（Controller/Service）无需关心购物车数量的更新               │
 *   - 所有需要显示购物车数量的页面，通过此拦截器自动获得最新数据         │
 *   - 前端模板直接取session中的user.shopCartItemCount即可显示              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * 【工作原理详解】
 *
 * 拦截流程示例（浏览商品列表页）:
 *
 * Step 1: 用户访问 http://localhost:28089/search?keyword=iPhone
 * Step 2: Spring MVC拦截器链执行顺序：
 *         ① NewBeeMallCartNumberInterceptor.preHandle()  ← 先执行
 *         ② NewBeeMallLoginInterceptor.preHandle()     （如果需要登录检查）
 *         ③ GoodsController.searchPage()                ← 最终目标
 *
 * Step 3: NewBeeMallCartNumberInterceptor.preHandle()执行过程：
 *   a) 检查Session是否存在且已有登录用户 (Constants.MALL_USER_SESSION_KEY)
 *      → 未登录的用户直接跳过，不影响首页等公共页面
 *   b) 从Session取出 NewBeeMallUserVO user = (NewBeeMallUserVO) session.get("newBeeMallUser")
 *   c) 调用mapper查询该用户的购物车项总数：
 *      int count = newBeeMallShoppingCartItemMapper.selectCountByUserId(user.getUserId())
 *   d) 将count设置到user对象中：user.setShopCartItemCount(count)
 *   e) 把更新后的user写回Session：session.setAttribute("newBeeMallUser", user)
 *   f) 返回true，继续后续拦截器和Controller的执行
 *
 * Step 4: GoodsController执行时不需要知道购物车数量已更新
 * Step 5: Thymeleaf模板在渲染时直接引用 ${session.newBeeMallUser.shopCartItemCount}
 *         就能显示正确的购物车商品总数
 *
 * 【数据库交互分析】
 *
 * 被调用的Mapper方法：selectCountByUserId(Long userId)
 * - 位于 NewBeeMallShoppingCartItemMapper 接口
 * - SQL语句类似：SELECT COUNT(*) FROM shopping_cart_item WHERE user_id = #{userId}
 *   AND is_deleted = 0 (逻辑删除过滤)
 *
 * 【Session数据流转图示】
 *
 * 初始状态 (用户登录后):
 * Session[newBeeMallUser = {userId=123, nickName="张三", shopCartItemCount=0}]
 *    ↑                                 (初始值可能为0)
 *
 * 第一次请求 (浏览页):
 * interceptor.preHandle() → 查得 shopCartItemCount=3 → 更新Session
 * Session[newBeeMallUser = {userId=123, nickName="张三", shopCartItemCount=3}]
 *
 * 第二次添加商品后 (AJAX POST /shop-cart):
 * ShoppingCartController.updateCartItem() → 修改DB但不更新Session
 * Session[newBeeMallUser仍为旧的count值] ← 问题！
 *
 * 第三次请求任何页面:
 * interceptor.preHandle() → 重新查DB得到新count → 更新Session
 * Session[newBeeMallUser = {userId=123, ..., shopCartItemCount=新值}] ✓
 *
 * ⚠️ 重要说明：这就是为什么需要此拦截器！因为在AJAX添加购物车后，
 * Session中的shopCartItemCount不会立即更新，直到下次发起新的HTTP请求。
 * 通过拦截器在每个请求中重新查询，保证了数据的实时一致性。
 *
 * 【配置关联】
 * 在 NeeBeeMallWebMvcConfigurer.java 中注册（注意拦截顺序）：
 *
 * @Configuration
 * public class NeeBeeMallWebMvcConfigurer implements WebMvcConfigurer {
 *     @Autowired
 *     private NewBeeMallCartNumberInterceptor cartInterceptor;
 *     @Autowired
 *     private NewBeeMallLoginInterceptor loginInterceptor;
 *
 *     @Override
 *     public void addInterceptors(InterceptorRegistry registry) {
 *         // 注意顺序：先执行cartInterceptor，再执行loginInterceptor
 *         registry.addInterceptor(cartInterceptor)
 *                 .addPathPatterns("/**")           // 拦截所有请求
 *                 .excludePathPatterns("/login", "/register", "/common/kaptcha");
 *
 *         registry.addInterceptor(loginInterceptor)
 *                 .addPathPatterns("/personal", "/orders", "/shop-cart/settle")
 *                 .excludePathPatterns("/login", "/register");
 *     }
 * }
 *
 * 【关键代码逐行解析】
 *
 * // 1. 双重检查：Session存在且有用户对象
 * if (null != request.getSession() && null != request.getSession().getAttribute(Constants.MALL_USER_SESSION_KEY)) {
 *     // request.getSession() == null 不会发生（Spring会创建），但防御性编程好习惯
 *     // 只有已登录用户才需要更新购物车数量
 * }
 *
 * // 2. 从Session取出用户VO
 * NewBeeMallUserVO newBeeMallUserVO = (NewBeeMallUserVO)
 *     request.getSession().getAttribute(Constants.MALL_USER_SESSION_KEY);
 *     // Constants.MALL_USER_SESSION_KEY = "newBeeMallUser"
 *
 * // 3. 查询购物车项总数
 * int itemCount = newBeeMallShoppingCartItemMapper.selectCountByUserId(newBeeMallUserVO.getUserId());
 *     // 只统计未删除的购物项（业务上is_deleted=0表示有效）
 *
 * // 4. 设置购物车数量到用户对象
 * newBeeMallUserVO.setShopCartItemCount(itemCount);
 *     // ShopCartItemCount是前端模板直接读取的属性
 *
 * // 5. 将更新后的对象写回Session
 * request.getSession().setAttribute(Constants.MALL_USER_SESSION_KEY, newBeeMallUserVO);
 *     // 注意：这会覆盖Session中原来的对象，下次请求时拿到的是更新后的
 *
 * // 6. 返回true，继续拦截链
 * return true;
 *
 * 【使用场景】
 *
 * 场景1: 首页顶部导航栏
 *   - 用户浏览首页 (/index)
 *   - interceptor先更新shopCartItemCount
 *   - Thymeleaf模板: <span th:text="${session.newBeeMallUser.shopCartItemCount}">
 *   - 结果：显示准确的数字（即使刚添加过商品）
 *
 * 场景2: 商品详情页
 *   - 用户查看 /goods/detail/1
 *   - 同上，每次请求都会同步最新的购物车数
 *   - 避免AJAX添加后页面无刷新导致数字不一致的问题
 *
 * 场景3: 非登录用户
 *   - 访问首页前未登录
 *   - Session中没有newBeeMallUser对象
 *   - 拦截器跳过，不影响首页正常展示
 *
 * 【性能优化考虑】
 *
 * ⚠️ 潜在问题：每个请求都执行一次数据库查询，可能增加DB压力。
 *
 * 优化方案建议（可选）：
 * 1. Cache方案：将购物车数量放入Redis，key为 userId，过期时间5分钟
 *    - preHandle时先查Redis，存在则不用查DB
 *    - add/update购物车项时同步更新Redis
 * 2. 只在关键页面启用此拦截器（如/personal, /orders, /shop-cart）
 *    - 而不是对所有路径都拦截 (addPathPatterns("/personal/**,/shop-cart/**"))
 * 3. 异步查询+预加载：用户登录后预查一次写入Session，后续仅在关键操作后刷新
 *
 * 当前实现简单直接，适合中小型电商系统；如系统规模大可考虑上述优化。
 *
 * 【与其他组件的协作关系】
 *
 * +--------------------------------+       +-----------------------------+
 * | NewBeeMallShoppingCartItemVO   |       | Shopping Cart Form          |
 * | (购物车项VO)                   |       | （前端AJAX添加/修改）        |
 * +------------+-------------------+       +------------+---------------+
 *              |                                        |
 *              v                                        v
 * +--------------------------------+       +-----------------------------+
 * | ShoppingCartController         |       | /shop-cart (POST/PUT)       |
 * | (saveNewBeeMallCartItem)       |       | （修改DB中的购物车项）        |
 * +------------+-------------------+       +------------+---------------+
 *              |                                        |
 *              +-------------> DB (shopping_cart_item) <-----+
 *                                    ^                    |
 *                                    |                    | (不更新Session)
 *                                    |                    |
 * +---------------------------------+                    |
 * | NewBeeMallCartNumberInterceptor                     |
 * | (preHandle: selectCountByUserId)                    |
 * +----------------------------------------------------+
 *                                                    ↓
 *                                    (下次请求时自动刷新)
 * =====================================================================
 */
package ltd.newbee.mall.interceptor;

import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.controller.vo.NewBeeMallUserVO;
import ltd.newbee.mall.dao.NewBeeMallShoppingCartItemMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote 购物车数量自动更新拦截器
 *          在所有请求到达Controller之前，自动查询用户购物车项总数并更新到Session中的User对象中，
 *          确保前端顶部导航栏显示的购物车数字始终准确。这是一个透明地、无侵入式的数据同步机制。
 */
@Component  // Spring组件扫描，将此拦截器注册为Spring Bean
public class NewBeeMallCartNumberInterceptor implements HandlerInterceptor {

    @Autowired  // Spring自动注入，由容器提供NewBeeMallShoppingCartItemMapper实例
    private NewBeeMallShoppingCartItemMapper newBeeMallShoppingCartItemMapper;

    /**
     * 【功能】请求处理前拦截 - 自动更新购物车数量
 * 在每次HTTP请求到达Controller之前，查询当前用户的购物车总项数并更新到Session中的用户对象
 *
 * 【设计目的】
 * 解决AJAX添加/修改购物车项后，Session中的用户对象未及时同步问题：
 * - ShoppingCartController中的save/update方法直接修改数据库
 * - 但不更新Session中的NewBeeMallUserVO对象的shopCartItemCount属性
 * - 导致前端显示的购物车数字不准确（除非手动刷新页面）
 * - 本拦截器通过"每次请求都重新查询"的策略，确保数据最终一致
 *
 * 【工作流程详解】
 * 1. 检查Session是否存在且已登录（有newBeeMallUser对象）→ 非登录用户跳过
 * 2. 从Session取出NewBeeMallUserVO对象
 * 3. 通过mapper查询该用户的所有购物车项数量（未删除的）
 * 4. 将数量设置到user对象的shopCartItemCount属性
 * 5. 把更新后的user对象写回Session（覆盖原对象）
 * 6. 返回true，继续后续的拦截器和Controller处理
 *
 * 【参数说明】
 * @param request HTTP 请求对象，用于获取Session和当前用户信息
 * @param response HTTP 响应对象（本方法中不使用）
 * @param handler 被处理的Handler对象（本方法中不使用）
 *
 * 【返回值】
 * @return boolean: always true (always allow the request to proceed)
 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 双重检查：确保Session存在且用户已登录
        // 只有登录过的用户才有购物车数量需要同步的需求
        if (null != request.getSession() && null != request.getSession().getAttribute(Constants.MALL_USER_SESSION_KEY)) {
            // 从Session取出当前登录的用户VO对象
            // 键名来自 Constants.MALL_USER_SESSION_KEY = "newBeeMallUser"
            NewBeeMallUserVO newBeeMallUserVO = (NewBeeMallUserVO) request.getSession().getAttribute(Constants.MALL_USER_SESSION_KEY);

            // 查询数据库中该用户的购物车项总数（排除已删除的项）
            // 注意：这里的count是购物车项的数量，不是商品种类数
            // 例如：同一商品加2个，count为2；再加一个不同的商品，count为3
            int itemCount = newBeeMallShoppingCartItemMapper.selectCountByUserId(newBeeMallUserVO.getUserId());

            // 将查询到的数量设置到用户对象的shopCartItemCount属性中
            newBeeMallUserVO.setShopCartItemCount(itemCount);

            // 将更新后的用户对象写回Session，替换原来的对象
            // 这样后续请求（包括当前请求的Controller和模板）都能拿到带有正确数量
            request.getSession().setAttribute(Constants.MALL_USER_SESSION_KEY, newBeeMallUserVO);
        }

        // 无论是否更新了购物车数量，都返回true放行请求
        // 非登录用户也正常通过，只是不更新数量而已
        return true;
    }

    /**
     * 【功能】请求处理后回调（购物车拦截器）
 * 在Controller方法执行后被调用
 *
 * 【设计考虑】
 * 此拦截器仅需在preHandle阶段完成查询和更新，postHandle不需要额外操作。
 * 如果需要记录请求耗时或在视图层添加数据，可以在这里实现。
 */
    @Override
    public void postHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler, ModelAndView modelAndView) throws Exception {
        // 购物车数量更新拦截器无需postHandle逻辑
    }

    /**
     * 【功能】请求完成后回调（购物车拦截器）
 * 在整个请求完成（视图渲染后）被调用
 *
 * 【设计考虑】
 * 不需要进行资源清理工作，因为拦截器本身没有打开数据库连接等资源需要关闭。
 * 框架层面的数据库连接管理由DataSource和MyBatis负责。
 */
    @Override
    public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler, Exception e) throws Exception {
        // 购物车数量更新拦截器无需afterCompletion逻辑
    }
}
