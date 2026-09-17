/**
 * =====================================================================
 * AdminLoginInterceptor.java - 后台管理登录拦截器
 * 【项目背景】
 * 在后台管理系统（/admin/*）中，需要确保只有登录的管理员才能访问各种管理页面和API。
 * 如果没有经过验证就允许访问，会导致安全风险（如直接通过URL跳转到订单管理页）。
 * AdminLoginInterceptor 就是一个实现此安全控制的拦截器。
 *
 * 【核心功能】
 * 1. 自动拦截所有以/admin开头的请求
 * 2. 检查用户是否已登录（session中是否存在loginUser属性）
 * 3. 未登录时重定向到登录页，并设置错误信息
 * 4. 已登录时放行，允许后续处理继续执行
 * 5. 清除可能的错误消息，避免干扰正常页面展示
 *
 * 【工作原理详解】
 *
 * Spring MVC拦截器的生命周期：
 *
 *                         +---------------------+
 *                         |    Controller方法   |
 *                         |      (被调用)       |
 *                         +----------+----------+
 *                                    ↓
 *         preHandle() <-------------+  (返回false则中断流程)
 *                                    ↓
 *                         +---------------------+
 *                         |   ModelAndView视图  |
 *                         |     (渲染模板)      |
 *         postHandle() <--+  (可选修改模型数据) |
 *                                    ↓
 *         afterCompletion()  (请求完成后的清理)
 *
 * AdminLoginInterceptor 的工作流程：
 *
 * Step 1: 用户发起请求 http://localhost:28089/admin/carousels
 * Step 2: Spring MVC框架调用 AdminLoginInterceptor.preHandle()
 * Step 3: interceptor获取 request.getServletPath() → "/admin/carousels"
 * Step 4: 判断 path.startsWith("/admin") AND session中没有 loginUser → 未登录！
 * Step 5:
 *        - 设置 error_msg: "请登陆"
 *        - 重定向到: /admin/login
 *        - 返回 false: 停止后续Controller调用
 * Step 6: 用户在登录页输入账号密码，成功登录后session设了loginUser
 * Step 7: 再次请求/admin路径时:
 *        - 判断 path.startsWith("/admin") AND session中有 loginUser → 已登录！
 *        - 清除errorMsg
 *        - 返回 true: 请求继续传递到Admin中的对应Controller方法
 *
 * 【配置文件关联】
 * 该拦截器需要在 NeeBeeMallWebMvcConfigurer.java 中注册:
 *
 * @Configuration
 * public class NeeBeeMallWebMvcConfigurer implements WebMvcConfigurer {
 *     @Autowired
 *     private AdminLoginInterceptor adminLoginInterceptor;
 *
 *     @Override
 *     public void addInterceptors(InterceptorRegistry registry) {
 *         registry.addInterceptor(adminLoginInterceptor)
 *                 .addPathPatterns("/admin/**")           // 拦截/admin下所有路径
 *                 .excludePathPatterns("/admin/login",   // 排除登录页（否则死循环）
 *                                  "/admin/test",    // 排除测试页
 *                                  "/admin/logout"); // 排除登出接口
 *     }
 * }
 *
 * 【关键代码分析】
 *
 * // 获取请求的Servlet路径（不包括contextPath）
 * String requestServletPath = request.getServletPath();  // e.g., "/admin/carousels"
 *
 * // 判断是否以/admin开头，并且session中没有loginUser
 * if (requestServletPath.startsWith("/admin") && null == request.getSession().getAttribute("loginUser")) {
 *     // 设置错误消息到session，用于登录页提示
 *     request.getSession().setAttribute("errorMsg", "请登陆");
 *     // 重定向到登录页
 *     response.sendRedirect(request.getContextPath() + "/admin/login");
 *     return false;  // 阻止Controller继续执行
 * } else {
 *     // 已登录或不是/admin路径，清除之前的errorMsg
 *     request.getSession().removeAttribute("errorMsg");
 *     return true;   // 允许请求继续
 * }
 *
 * 【注意事项】
 * 1. excludePathPatterns非常重要！否则会形成登录页的重定向死循环
 * 2. login用户存储在session中的键名是"loginUser"（来自AdminController设置）
 * 3. errorMsg会显示在admin/login页面的错误提示区域
 * 4. 只拦截preHandle，postHandle和afterCompletion为空，因为不需要额外操作
 * 5. 此拦截器只对后台管理员生效，前台商城使用另一个拦截器 NewBeeMallLoginInterceptor
 * =====================================================================
 */
package ltd.newbee.mall.interceptor;

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
 * @apiNote 后台系统身份验证拦截器
 *          拦截所有/admin路径的请求，检查用户是否已登录，
 *          未登录则重定向到登录页面。这是后台管理系统的第一道安全防线。
 */
@Component  // Spring组件扫描，将此拦截器注册为Spring Bean
public class AdminLoginInterceptor implements HandlerInterceptor {

    /**
     * 【功能】请求处理前拦截
 * 这是拦截器中最关键的回调方法，用于在目标Controller方法执行前进行检查
 *
 * 【用途】
 * ① 权限验证：检查用户是否有权限访问当前资源
 * ② 登录检查：验证用户是否已登录
 * ③ 日志记录：记录请求开始时间等信息
 * ④ 参数预处理：对请求参数进行统一过滤或转换
 *
 * 【参数说明】
 * @param request HTTP 请求对象，包含客户端请求的所有信息
 * @param response HTTP 响应对象，用于向客户端发送响应
 * @param handler 被处理的Handler对象（即对应的Controller方法）
 *
 * 【返回值说明】
 * @return boolean:
 *   - true: 表示拦截器链继续向下执行（进入下一个拦截器或最终到达Controller）
 *   - false: 表示中断拦截器链，不再执行后续的Controller方法
 *             此时通常需要做重定向或返回错误页面
 *
 * 【异常处理】
 * 如果方法中抛出异常，会触发整个拦截器链的afterCompletion以及最终的异常处理器
 * 注意：该方法签名声明了 throws Exception，因此需要处理可能抛出的异常
 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取当前请求的Servlet路径（不包含上下文路径，如"/admin/carousels"）
        String requestServletPath = request.getServletPath();

        // 检查两个条件：
        // 1. 请求路径以/admin开头（属于后台管理范围）
        // 2. Session中不存在loginUser（表示用户未登录）
        // 如果同时满足这两个条件，说明是未登录的用户试图访问后台页面
        if (requestServletPath.startsWith("/admin") && null == request.getSession().getAttribute("loginUser")) {
            // 设置错误消息到Session，登录页会读取此消息并向用户提示
            request.getSession().setAttribute("errorMsg", "请登陆");

            // 重定向到登录页面
            // request.getContextPath() 获取应用的上下文路径（通常是"/"或项目名称）
            // 例如: 完整URL = "/newbee-mall/admin/login"
            response.sendRedirect(request.getContextPath() + "/admin/login");

            // 返回false，表示拦截请求，不继续执行后续的处理程序（即不调用AdminController）
            return false;
        } else {
            // 如果用户已登录，或者这不是/admin路径的请求
            // 清除之前可能存在的errorMsg，防止错误信息残留到正确页面
            request.getSession().removeAttribute("errorMsg");

            // 返回true，表示请求可以继续传递给下一个拦截器或目标Controller
            return true;
        }
    }

    /**
     * 【功能】请求处理后回调
 * 在Controller方法执行之后、视图渲染之前被调用
 *
 * 【主要用途】
 * 1. 可以对ModelAndView中的模型数据进行修改（如添加通用数据）
 * 2. 可以在视图渲染前做最后的数据准备
 * 3. 可以记录请求处理耗时
 *
 * 【参数说明】
 * @param httpServletRequest 原始请求对象
 * @param httpServletResponse 原始响应对象
 * @param handler 被处理的Handler对象
 * @param modelAndView 视图对象，包含模型数据和视图名称
 *                   如果是返回String类型的视图名称，modelView为null
 *
 * 【注意】
 * 本拦截器中未实现此方法，因为不需要在Controller后做任何特殊处理。
 * 如果需要添加公共数据到所有后台页面的Model，可以在此方法中添加。
 */
    @Override
    public void postHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler, ModelAndView modelAndView) throws Exception {
        // 暂未实现，留作扩展
    }

    /**
     * 【功能】请求完成后回调
 * 在整个请求完成后被调用（包括视图渲染完毕后）
 *
 * 【主要用途】
 * 1. 资源清理（关闭数据库连接、释放文件句柄等）
 * 2. 请求统计（计算总耗时记录日志）
 * 3. 审计日志（记录完整的请求处理过程）
 *
 * 【参数说明】
 * @param httpServletRequest 请求对象
 * @param httpServletResponse 响应对象
 * @param handler 被处理的Handler对象
 * @param e 如果在preHandle或postHandle中抛出的异常，此处为非null
 *         如果正常完成，则为null
 *
 * 【注意】
 * 即使preHandle返回false导致流程中断，afterCompletion仍会被调用。
 * 这是一个很好的时机来做全局的资源清理工作。
 */
    @Override
    public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler, Exception e) throws Exception {
        // 暂未实现，留作扩展
    }
}
