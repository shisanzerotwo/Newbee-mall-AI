/**
 * 严肃声明：
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！
 * 本系统已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！
 * Copyright (c) 2019-2020 十三 all rights reserved.
 * 版权所有，侵权必究！
 */
package ltd.newbee.mall.controller.common;

import ltd.newbee.mall.common.NewBeeMallException;
import ltd.newbee.mall.util.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * newbee-mall全局异常处理
 */
@RestControllerAdvice
public class NewBeeMallExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(NewBeeMallExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception e, HttpServletRequest req, HttpServletResponse resp) {
        // ================================================================
        // ⚠️ SSE（text/event-stream）请求必须**提前返回**，不能往下走。
        //
        // 背景（M3 压测时真实踩到）：SSE 端点的响应头已被固定为 text/event-stream，
        // 而下面的判定会看请求的 Content-Type: application/json（SSE 客户端的正常写法）
        // → 被当成 ajax 请求 → 返回 Result → 没有任何 HttpMessageConverter 能写
        // → 抛 HttpMessageNotWritableException，导致「异常处理器自身失败」：
        //      Failure in @ExceptionHandler ... No converter for [class Result]
        //      with preset Content-Type 'text/event-stream'
        // 二次异常 + 堆栈误导（指向处理器而非真因），比原始异常难排得多。
        //
        // SSE 端点已在流内用 error 事件兜底（CsController / CsStreamService / CsSseWriter），
        // 所以这里只需要「不再添乱」：不改响应、写日志留痕即可。
        // ================================================================
        if (isSseRequest(req)) {
            log.warn("SSE 请求发生异常（响应体已由流内 error 事件兜底，此处不再写）：uri={}，原因={}",
                    req.getRequestURI(), e.toString());
            // ⚠️ 必须显式置 500：返回 null 会让容器当「已处理完毕」→ 客户端看到
            //   200 + 空体，把“出错”伪装成“成功”—— 正是本项目反复强调要避免的
            //   「200 掩盖错误」（M1 就踩过：DB 失联页面 200、尾斜杠错误页 200）。
            //   已提交（headers 已发出）时 setStatus 无效，此时只能靠流内 error 事件传达。
            if (!resp.isCommitted()) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            return null;
        }

        Result result = new Result();
        result.setResultCode(500);
        //区分是否为自定义异常
        if (e instanceof NewBeeMallException) {
            result.setMessage(e.getMessage());
        } else {
            e.printStackTrace();
            result.setMessage("未知异常");
        }
        //检查请求是否为ajax, 如果是 ajax 请求则返回 Result json串, 如果不是 ajax 请求则返回 error 视图
        String contentTypeHeader = req.getHeader("Content-Type");
        String acceptHeader = req.getHeader("Accept");
        String xRequestedWith = req.getHeader("X-Requested-With");
        if ((contentTypeHeader != null && contentTypeHeader.contains("application/json"))
                || (acceptHeader != null && acceptHeader.contains("application/json"))
                || "XMLHttpRequest".equalsIgnoreCase(xRequestedWith)) {
            return result;
        } else {
            ModelAndView modelAndView = new ModelAndView();
            modelAndView.addObject("message", e.getMessage());
            modelAndView.addObject("url", req.getRequestURL());
            modelAndView.addObject("stackTrace", e.getStackTrace());
            modelAndView.addObject("author", "十三");
            modelAndView.addObject("ltd", "新蜂商城");
            modelAndView.setViewName("error/error");
            return modelAndView;
        }
    }

    /**
     * 判定是否 SSE 请求。
     *
     * <p>两道判据（任一成立即可）：
     * <ol>
     *   <li>{@code Accept} 或 {@code Content-Type} 含 {@code text/event-stream}；</li>
     *   <li>请求路径落在客服流式端点前缀 {@code /api/cs/} 下。</li>
     * </ol>
     * <p>只看 {@code Accept} 不够可靠 —— 有些客户端（如 curl 默认）发 {@code Accept: *}{@code /*}，
     * 只按 {@code Content-Type: application/json} 会被误判为 ajax，这正是当初踩坑的场景。
     */
    private static boolean isSseRequest(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        String contentType = req.getHeader("Content-Type");
        if ((accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE))
                || (contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE))) {
            return true;
        }
        // 收窄到具体的流式端点，而不是 /api/cs/ 整个前缀：
        // /api/cs/health 是普通 JSON 端点，被当成 SSE 会让它的异常不再返回 Result（行为退化）。
        String uri = req.getRequestURI();
        return "/api/cs/chat".equals(uri);
    }
}
