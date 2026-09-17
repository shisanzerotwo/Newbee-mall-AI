/**
 * 严肃声明：
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！
 * 本系统已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！
 * Copyright (c) 2019-2020 十三 all rights reserved.
 * 版权所有，侵权必究！
 */
package ltd.newbee.mall.controller.common;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.ShearCaptcha;
import ltd.newbee.mall.common.Constants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 * @apiNote 通用控制器
 *         处理一些通用的请求，如验证码生成等公共功能
 */
@Controller
public class CommonController {

    /**
     * 管理员后台验证码获取接口
     * @param httpServletRequest HTTP 请求对象
     * @param httpServletResponse HTTP 响应对象
     * @throws Exception 可能出现的异常
     */
    @GetMapping("/common/kaptcha")
    public void defaultKaptcha(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws Exception {
        // 设置缓存控制头，防止浏览器缓存验证码图片
        httpServletResponse.setHeader("Cache-Control", "no-store");
        httpServletResponse.setHeader("Pragma", "no-cache");
        httpServletResponse.setDateHeader("Expires", 0);
        httpServletResponse.setContentType("image/png");

        // 创建ShearCaptcha对象（扭曲验证码），大小150x30，4个字符，2种干扰
        ShearCaptcha shearCaptcha = CaptchaUtil.createShearCaptcha(150, 30, 4, 2);

        // 将验证码存入session（键名为"verifyCode"）
        httpServletRequest.getSession().setAttribute("verifyCode", shearCaptcha);

        // 将验证码图片写入输出流
        shearCaptcha.write(httpServletResponse.getOutputStream());
    }

    /**
     * 前台商城验证码获取接口
     * @param httpServletRequest HTTP 请求对象
     * @param httpServletResponse HTTP 响应对象
     * @throws Exception 可能出现的异常
     */
    @GetMapping("/common/mall/kaptcha")
    public void mallKaptcha(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws Exception {
        // 设置缓存控制头，防止浏览器缓存验证码图片
        httpServletResponse.setHeader("Cache-Control", "no-store");
        httpServletResponse.setHeader("Pragma", "no-cache");
        httpServletResponse.setDateHeader("Expires", 0);
        httpServletResponse.setContentType("image/png");

        // 创建ShearCaptcha对象（扭曲验证码），大小110x40，4个字符，2种干扰
        ShearCaptcha shearCaptcha = CaptchaUtil.createShearCaptcha(110, 40, 4, 2);

        // 将验证码存入session（键名来自Constants.MALL_VERIFY_CODE_KEY）
        httpServletRequest.getSession().setAttribute(Constants.MALL_VERIFY_CODE_KEY, shearCaptcha);

        // 将验证码图片写入输出流
        shearCaptcha.write(httpServletResponse.getOutputStream());
    }
}
