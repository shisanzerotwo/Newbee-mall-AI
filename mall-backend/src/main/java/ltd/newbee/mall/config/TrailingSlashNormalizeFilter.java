package ltd.newbee.mall.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * 尾斜杠规范化：把 <code>GET /xxx/</code> 重定向到 <code>/xxx</code>。
 *
 * <p>背景：Spring Framework 6.0 起，尾斜杠匹配（trailing slash match）的默认值
 * 由 <code>true</code> 改为 <code>false</code>。该配置项同时被标记为 deprecated，
 * 但<b>截至 Spring 6.2 仍然存在且仍然生效</b>（字节码中可见
 * <code>WebMvcConfigurationSupport</code> 仍在调用
 * <code>PathMatchConfigurer#isUseTrailingSlashMatch()</code> 与
 * <code>RequestMappingHandlerMapping#setUseTrailingSlashMatch(boolean)</code>）。
 * 本项目原先运行在 Spring 5.3（Boot 2.7.5）上、默认匹配尾斜杠，因此升级到 Boot 3.5 后，
 * 形如 <code>/search/</code> 的请求不再匹配任何映射，会落到错误页 —— 而 HTTP 状态码
 * 仍然是 <b>200</b>（属「200 掩盖错误」）。
 *
 * <p>本 Filter 以重定向方式<b>恢复可用性</b>，与项目内 <code>/admin/login/</code>
 * 既有的 302 表现保持一致。仅处理 GET，不影响表单 POST。
 *
 * <p><b>与升级前行为并非完全等价</b>：Boot 2.7.5 是内部匹配（URL 保持
 * <code>/search/</code>、直接返回 200），这里是 302 重定向（URL 变为 <code>/search</code>）。
 * 对浏览器等价，对<b>不跟随重定向</b>的脚本 / 爬虫 / API 客户端不等价。
 * 另一可选方案是
 * <code>configurePathMatch(c -&gt; c.setUseTrailingSlashMatch(true))</code>（3 行、
 * 可达原行为）；未选它的理由是：它依赖已 deprecated 的 API，而重定向与项目内
 * 既有行为一致、语义更显式。
 */
@Component
public class TrailingSlashNormalizeFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest req
                && response instanceof HttpServletResponse resp) {
            String uri = req.getRequestURI();
            if ("GET".equalsIgnoreCase(req.getMethod())
                    && uri != null && uri.length() > 1 && uri.endsWith("/")) {
                String target = uri.substring(0, uri.length() - 1);
                String query = req.getQueryString();
                resp.sendRedirect(StringUtils.hasLength(query) ? target + "?" + query : target);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
