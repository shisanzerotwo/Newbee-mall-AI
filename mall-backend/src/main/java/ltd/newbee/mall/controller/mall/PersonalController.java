/**
 * 严肃声明：
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！
 * 本系统已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！
 * Copyright (c) 2019-2020 十三 all rights reserved.
 * 版权所有，侵权必究！
 */
package ltd.newbee.mall.controller.mall;

import cn.hutool.captcha.ShearCaptcha;
import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.common.ServiceResultEnum;
import ltd.newbee.mall.controller.vo.NewBeeMallUserVO;
import ltd.newbee.mall.entity.MallUser;
import ltd.newbee.mall.service.NewBeeMallUserService;
import ltd.newbee.mall.util.MD5Util;
import ltd.newbee.mall.util.Result;
import ltd.newbee.mall.util.ResultGenerator;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * 前台个人中心控制器
 * 处理用户注册、登录、个人信息修改、登出等个人相关功能请求
 *
 * @author 13
 */
@Controller
public class PersonalController {

    @Resource // 注入用户服务
    private NewBeeMallUserService newBeeMallUserService;

    /**
     * 个人中心页面
     * @param request HTTP 请求对象
     * @param httpSession HttpSession对象
     * @return 视图名称 "mall/personal"，指向个人中心模板
     */
    @GetMapping("/personal")
    public String personalPage(HttpServletRequest request,
                               HttpSession httpSession) {
        request.setAttribute("path", "personal");
        return "mall/personal";
    }

    /**
     * 用户退出登录
     * @param httpSession HttpSession对象
     * @return 视图名称 "mall/login"，跳转到登录页
     */
    @GetMapping("/logout")
    public String logout(HttpSession httpSession) {
        httpSession.removeAttribute(Constants.MALL_USER_SESSION_KEY);
        return "mall/login";
    }

    /**
     * 登录页面（GET）
     * @return 视图名称 "mall/login"，指向登录模板
     */
    @GetMapping({"/login", "login.html"})
    public String loginPage() {
        return "mall/login";
    }

    /**
     * 注册页面（GET）
     * @return 视图名称 "mall/register"，指向注册模板
     */
    @GetMapping({"/register", "register.html"})
    public String registerPage() {
        return "mall/register";
    }

    /**
     * 收货地址管理页面
     * @return 视图名称 "mall/addresses"，指向地址管理模板
     */
    @GetMapping("/personal/addresses")
    public String addressesPage() {
        return "mall/addresses";
    }

    /**
     * 用户登录（POST接口）
     * @param loginName 用户名
     * @param verifyCode 验证码
     * @param password 密码
     * @param httpSession HttpSession对象
     * @return Result对象，包含登录结果
     */
    @PostMapping("/login")
    @ResponseBody
    public Result login(@RequestParam("loginName") String loginName,
                        @RequestParam("verifyCode") String verifyCode,
                        @RequestParam("password") String password,
                        HttpSession httpSession) {
        if (!StringUtils.hasText(loginName)) {
            return ResultGenerator.genFailResult(ServiceResultEnum.LOGIN_NAME_NULL.getResult());
        }
        if (!StringUtils.hasText(password)) {
            return ResultGenerator.genFailResult(ServiceResultEnum.LOGIN_PASSWORD_NULL.getResult());
        }
        if (!StringUtils.hasText(verifyCode)) {
            return ResultGenerator.genFailResult(ServiceResultEnum.LOGIN_VERIFY_CODE_NULL.getResult());
        }
        ShearCaptcha shearCaptcha = (ShearCaptcha) httpSession.getAttribute(Constants.MALL_VERIFY_CODE_KEY);

        if (shearCaptcha == null || !shearCaptcha.verify(verifyCode)) {
            return ResultGenerator.genFailResult(ServiceResultEnum.LOGIN_VERIFY_CODE_ERROR.getResult());
        }
        String loginResult = newBeeMallUserService.login(loginName, MD5Util.MD5Encode(password, "UTF-8"), httpSession);
        // 登录成功
        if (ServiceResultEnum.SUCCESS.getResult().equals(loginResult)) {
            // 删除session中的verifyCode
            httpSession.removeAttribute(Constants.MALL_VERIFY_CODE_KEY);
            return ResultGenerator.genSuccessResult();
        }
        // 登录失败
        return ResultGenerator.genFailResult(loginResult);
    }

    /**
     * 用户注册（POST接口）
     * @param loginName 用户名
     * @param verifyCode 验证码
     * @param password 密码
     * @param httpSession HttpSession对象
     * @return Result对象，包含注册结果
     */
    @PostMapping("/register")
    @ResponseBody
    public Result register(@RequestParam("loginName") String loginName,
                           @RequestParam("verifyCode") String verifyCode,
                           @RequestParam("password") String password,
                           HttpSession httpSession) {
        if (!StringUtils.hasText(loginName)) {
            return ResultGenerator.genFailResult(ServiceResultEnum.LOGIN_NAME_NULL.getResult());
        }
        if (!StringUtils.hasText(password)) {
            return ResultGenerator.genFailResult(ServiceResultEnum.LOGIN_PASSWORD_NULL.getResult());
        }
        if (!StringUtils.hasText(verifyCode)) {
            return ResultGenerator.genFailResult(ServiceResultEnum.LOGIN_VERIFY_CODE_NULL.getResult());
        }
        ShearCaptcha shearCaptcha = (ShearCaptcha) httpSession.getAttribute(Constants.MALL_VERIFY_CODE_KEY);
        if (shearCaptcha == null || !shearCaptcha.verify(verifyCode)) {
            return ResultGenerator.genFailResult(ServiceResultEnum.LOGIN_VERIFY_CODE_ERROR.getResult());
        }
        String registerResult = newBeeMallUserService.register(loginName, password);
        // 注册成功
        if (ServiceResultEnum.SUCCESS.getResult().equals(registerResult)) {
            // 删除session中的verifyCode
            httpSession.removeAttribute(Constants.MALL_VERIFY_CODE_KEY);
            return ResultGenerator.genSuccessResult();
        }
        // 注册失败
        return ResultGenerator.genFailResult(registerResult);
    }

    /**
     * 更新用户个人信息（POST接口）
     * @param mallUser MallUser实体对象
     * @param httpSession HttpSession对象
     * @return Result对象，更新结果
     */
    @PostMapping("/personal/updateInfo")
    @ResponseBody
    public Result updateInfo(@RequestBody MallUser mallUser, HttpSession httpSession) {
        NewBeeMallUserVO mallUserTemp = newBeeMallUserService.updateUserInfo(mallUser, httpSession);
        if (mallUserTemp == null) {
            Result result = ResultGenerator.genFailResult("修改失败");
            return result;
        } else {
            Result result = ResultGenerator.genSuccessResult();
            return result;
        }
    }
}
