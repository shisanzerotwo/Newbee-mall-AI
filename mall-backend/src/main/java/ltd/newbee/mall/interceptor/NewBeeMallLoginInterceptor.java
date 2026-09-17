/**
 * =====================================================================
 * NewBeeMallLoginInterceptor.java - 前台商城登录拦截器
 * 【项目背景】
 * 前台商城系统中有部分页面需要用户登录后才能访问，例如：
 * - /personal (个人中心)
 * - /orders (订单列表)
 * - /shop-cart/settle (结算页)
 * - 其他需要显示用户信息的页面
 *
 * 如果没有登录的用户直接通过URL访问这些页面，会看到错误或空白。
 * NewBeeMallLoginInterceptor 就是用来保护这些前台页面的拦截器。
 *
 * 【与后台拦截器的区别对比】
 *
 * | 特性 | AdminLoginInterceptor | NewBeeMallLoginInterceptor |
 * |------|----------------------|---------------------------|
 * | 拦截路径 | /admin/** | 配置路径（/personal, /orders等） |
 * | Session检查键名 | loginUser | MALL_USER_SESSION_KEY ("newBeeMallUser") |
 * | 重定向目标 | /admin/login | /login |
 * | 适用场景 | 后台管理 | 前台商城用户页面 |
 * | 错误提示 | "请登陆" | 无额外提示，直接跳登录页 |
 *
 * 【核心功能】
 * 1. 拦截需要用户登录的前台请求
 * 2. 检查Session中是否存在已登录的用户对象
 * 3. 未登录时重定向到登录页面 (/login)
 * 4. 已登录时放行，继续执行后续处理
 *
 * 【工作原理详解】
 *
 * 拦截流程示例（访问订单列表页）:
 *
 * Step 1: 用户手动输入 http://localhost:28089/orders
 * Step 2: Spring MVC调用 NewBeeMallLoginInterceptor.preHandle()
 * Step 3: interceptor获取 session.getAttribute("newBeeMallUser")
 *         （此key来自Constants.MALL_USER_SESSION_KEY）
 * Step 4: 如果返回null → 用户未登录
 *         - response.sendRedirect(request.getContextPath() + "/login")
 *         - return false (停止执行)
 * Step 5: 如果返回对象 → 用户已登录
 *         - return true (继续执行OrderController.orderListPage())
 *
 * 【配置文件关联】
 * 在 NeeBeeMallWebMvcConfigurer.java 中注册：
 *
 * @Configuration
 * public class NeeBeeMallWebMvcConfigurer implements WebMvcConfigurer {
 *     @Autowired
 *     private NewBeeMallLoginInterceptor loginInterceptor;
 *
 *     @Override
 *     public void addInterceptors(InterceptorRegistry registry) {
 *         registry.addInterceptor(loginInterceptor)
 *                 .addPathPatterns("/personal", "/orders", "/shop-cart/settle")
 *                 .excludePathPatterns("/login", "/register", "/shop-cart");
 *     }
 * }
 *
 * 【关键代码分析】
 *
 * // 获取Session中的用户对象
 * // 键名是 Constants.MALL_USER_SESSION_KEY = "newBeeMallUser"
 * User user = (User) request.getSession().getAttribute(Constants.MALL_USER_SESSION_KEY);
 *
 * if (user == null) {
 *     // 用户未登录，重定向到登录页
 *     response.sendRedirect(request.getContextPath() + "/login");
 *     return false;
 * } else {
 *     // 用户已登录，放行请求
 *     return true;
 * }
 *
 * 【实际业务中的应用场景】
 *
 * 场景1: 用户尝试直接访问订单页
 *   URL: http://host:port/orders
 *   → 拦截器检查session → 没newBeeMallUser → 重定向到 /login
 *   → 用户登录后再次访问，session有了用户信息 → 放行 → OrderController加载订单
 *
 * 场景2: 购物车结算页需要登录
 *   URL: http://host:port/shop-cart/settle
 *   → 拦截器检查session → 有用户 → 放行 → ShoppingCartController计算总价
 *   → 注意: /shop-cart本身不需要拦截（因为有数量更新逻辑，但不强依赖登录）
 *
 * 场景3: 前台首页不需要拦截
 *   URL: http://host:port/index
 *   → 不在拦截路径内 → 直接到达IndexController
 *   → 首页不需要登录即可浏览商品
 *
 * 【最佳实践建议】
 * 1. excludePathPatterns要足够宽，避免将登录、注册、首页等公共页面误拦截
 * 2. 对于需要频繁更新顶部购物车数量的页面（如/products），可以选择不拦截但也不强依赖登录状态
 * 3. Session中存储的对象类型是NewBeeMallUserVO，包含用户昵称、登录名等信息
 * 4. 拦截器应只负责权限检查，具体业务逻辑仍在Controller和Service层实现
 * 5. 配合@InitBinder或AOP可以统一添加current_user到Model中，方便模板直接使用
 * =====================================================================
 */
package ltd.newbee.mall.interceptor;

import ltd.newbee.mall.common.Constants;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote newbee-mall系统身份验证拦截器
 *          用于拦截前台商城中需要用户登录后才能访问的页面，
 *          如个人中心、订单列表、结算页等。未登录则重定向到登录页。
 */
@Component  // Spring组件扫描，将此拦截器注册为Spring Bean
public class NewBeeMallLoginInterceptor implements HandlerInterceptor {

    /**
     * 【功能】请求处理前拦截 - 前台用户登录检查
 * 在目标Controller方法执行前进行登录校验，确保只有登录用户才能访问受保护页面
 *
 * 【用途】
 * ① 权限控制：防止未登录用户访问受限资源（如订单详情、个人信息）
 * ② 自动跳转：未登录时自动重定向到登录页，提供良好用户体验
 * ③ 安全检查：在执行业务逻辑前先确认用户身份，避免空指针和安全漏洞
 *
 * 【参数说明】
 * @param request HTTP 请求对象，可用于获取Session、请求参数、头信息等
 * @param response HTTP 响应对象，用于发送重定向响应（sendRedirect）
 * @param handler 被处理的Handler对象（即对应的Controller方法）
 *
 * 【返回值说明】
 * @return boolean:
 *   - true: 拦截器链继续向下执行，进入下一个拦截器或直接到达目标Controller
 *   - false: 中断拦截器链，不再执行后续的Controller方法
 *             本拦截器中用于重定向到登录页的情况
 *
 * 【异常说明】
 * 方法声明 throws Exception，任何在preHandle中抛出的异常都会触发
 * afterCompletion并被最终的异常处理器捕获处理。
 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从Session中获取当前登录的用户对象
        // 键名来自 Constants.MALL_USER_SESSION_KEY = "newBeeMallUser"
        // 该对象在用户登录成功后由PersonalController存入Session
        Object userObj = request.getSession().getAttribute(Constants.MALL_USER_SESSION_KEY);

        // 如果用户对象为null，说明Session中没有登录用户
        if (userObj == null) {
            // 重定向到登录页面
            // request.getContextPath() 获取应用上下文路径（例如"/"或项目名称）
            response.sendRedirect(request.getContextPath() + "/login");

            // 返回false，表示中断拦截链，不再执行后续的Controller方法
            return false;
        } else {
            // 用户已登录，放行请求，继续执行后续的拦截器或目标Controller
            return true;
        }
    }

    /**
     * 【功能】请求处理后回调（前台）
 * 在Controller方法执行后、视图渲染前被调用
 *
 * 【设计考虑】
 * 前台拦截器不需要在此处做任何特殊处理，因为：
 * 1. 主要职责是preHandle中的登录检查，完成后使命题结束
 * 2. 如果需要向所有页面添加公共数据（如当前用户名），可以在postHandle中添加
 * 3. 本项目中购物车数量由另一个拦截器 NewBeeMallCartNumberInterceptor 单独处理
 */
    @Override
    public void postHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler, ModelAndView modelAndView) throws Exception {
        // 前台登录拦截器无需额外处理
    }

    /**
     * 【功能】请求完成后回调（前台）
 * 在整个请求完成（包括视图渲染后）被调用
 *
 * 【设计考虑】
 * 同样不需要实现清理工作，因为：
 * 1. 登录检查已在preHandle中完成，没有额外资源需要释放
 * 2. 如果需要在请求结束后记录日志或做统计，可以实现此方法
 * 3. 即使preHandle返回false导致提前退出，afterCompletion仍会被调用
 */
    @Override
    public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler, Exception e) throws Exception {
        // 前台登录拦截器无需清理工作
    }
}
