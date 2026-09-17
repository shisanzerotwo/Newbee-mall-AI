/**
 * 严肃声明：
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！
 * 本系统已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！
 * Copyright (c) 2019-2020 十三 all rights reserved.
 * 版权所有，侵权必究！
 */
package ltd.newbee.mall.controller.admin;

import cn.hutool.captcha.ShearCaptcha;
import ltd.newbee.mall.common.ServiceResultEnum;
import ltd.newbee.mall.entity.AdminUser;
import ltd.newbee.mall.service.AdminUserService;

import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 * @apiNote 管理员后台控制器
 *         处理后台管理系统的登录、页面跳转和个人信息修改等请求
 *         所有API路径均以/admin为前缀
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    @Resource // 注入用户管理服务
    private AdminUserService adminUserService;

    /**
     * 管理员登录页面
     * @return 视图名称 "admin/login"，指向登录模板
     */
    @GetMapping({"/login"})
    public String login() {
        return "admin/login";
    }

    /**
     * 测试页面（调试用）
     * @return 视图名称 "admin/test"，指向测试模板
     */
    @GetMapping({"/test"})
    public String test() {
        return "admin/test";
    }

    /**
     * 管理员首页/仪表盘
     * @param request HTTP 请求对象
     * @return 视图名称 "admin/index"，指向首页模板
     */
    @GetMapping({"", "/", "/index", "/index.html"})
    public String index(HttpServletRequest request) {
        request.setAttribute("path", "index"); // 设置当前页面路径用于菜单高亮
        return "admin/index";
    }

    /**
     * 管理员登录验证
     * @param userName 用户名
     * @param password 密码
     * @param verifyCode 验证码
     * @param session HttpSession对象，用于存储登录状态和错误信息
     * @return 重定向到首页或返回登录页
     */
    @PostMapping(value = "/login")
    public String login(@RequestParam("userName") String userName,
                        @RequestParam("password") String password,
                        @RequestParam("verifyCode") String verifyCode,
                        HttpSession session) {
        if (!StringUtils.hasText(verifyCode)) {
            session.setAttribute("errorMsg", "验证码不能为空");
            return "admin/login";
        }
        if (!StringUtils.hasText(userName) || !StringUtils.hasText(password)) {
            session.setAttribute("errorMsg", "用户名或密码不能为空");
            return "admin/login";
        }
        // 从session获取验证码并验证
        ShearCaptcha shearCaptcha = (ShearCaptcha) session.getAttribute("verifyCode");
        if (shearCaptcha == null || !shearCaptcha.verify(verifyCode)) {
            session.setAttribute("errorMsg", "验证码错误");
            return "admin/login";
        }
        // 调用服务层进行登录验证
        AdminUser adminUser = adminUserService.login(userName, password);
        if (adminUser != null) {
            // 登录成功，将用户昵称和ID存入session
            session.setAttribute("loginUser", adminUser.getNickName());
            session.setAttribute("loginUserId", adminUser.getAdminUserId());
            // session过期时间设置为7200秒即两小时
            // session.setMaxInactiveInterval(60 * 60 * 2);
            return "redirect:/admin/index";
        } else {
            session.setAttribute("errorMsg", "登录失败");
            return "admin/login";
        }
    }

    /**
     * 管理员个人中心页面
     * @param request HTTP 请求对象
     * @return 视图名称 "admin/profile"，指向个人主页模板
     */
    @GetMapping("/profile")
    public String profile(HttpServletRequest request) {
        Integer loginUserId = (Integer) request.getSession().getAttribute("loginUserId");
        AdminUser adminUser = adminUserService.getUserDetailById(loginUserId);
        if (adminUser == null) {
            return "admin/login"; // 未登录则跳转到登录页
        }
        request.setAttribute("path", "profile"); // 设置当前页面路径
        request.setAttribute("loginUserName", adminUser.getLoginUserName());
        request.setAttribute("nickName", adminUser.getNickName());
        return "admin/profile";
    }

    /**
     * 管理员修改密码
     * @param request HTTP 请求对象
     * @param originalPassword 原密码
     * @param newPassword 新密码
     * @return JSON字符串，返回修改结果
     */
    @PostMapping("/profile/password")
    @ResponseBody
    public String passwordUpdate(HttpServletRequest request, @RequestParam("originalPassword") String originalPassword,
                                 @RequestParam("newPassword") String newPassword) {
        if (!StringUtils.hasText(originalPassword) || !StringUtils.hasText(newPassword)) {
            return "参数不能为空";
        }
        Integer loginUserId = (Integer) request.getSession().getAttribute("loginUserId");
        if (adminUserService.updatePassword(loginUserId, originalPassword, newPassword)) {
            // 修改成功后清空session中的数据，前端控制跳转至登录页
            request.getSession().removeAttribute("loginUserId");
            request.getSession().removeAttribute("loginUser");
            request.getSession().removeAttribute("errorMsg");
            return ServiceResultEnum.SUCCESS.getResult();
        } else {
            return "修改失败";
        }
    }

    /**
     * 管理员修改昵称/登录名
     * @param request HTTP 请求对象
     * @param loginUserName 新的登录名
     * @param nickName 新的昵称
     * @return JSON字符串，返回修改结果
     */
    @PostMapping("/profile/name")
    @ResponseBody
    public String nameUpdate(HttpServletRequest request, @RequestParam("loginUserName") String loginUserName,
                             @RequestParam("nickName") String nickName) {
        if (!StringUtils.hasText(loginUserName) || !StringUtils.hasText(nickName)) {
            return "参数不能为空";
        }
        Integer loginUserId = (Integer) request.getSession().getAttribute("loginUserId");
        if (adminUserService.updateName(loginUserId, loginUserName, nickName)) {
            return ServiceResultEnum.SUCCESS.getResult();
        } else {
            return "修改失败";
        }
    }

    /**
     * 管理员退出登录
     * @param request HTTP 请求对象
     * @return 视图名称 "admin/login"，跳转到登录页
     */
    @GetMapping("/logout")
    public String logout(HttpServletRequest request) {
        request.getSession().removeAttribute("loginUserId");
        request.getSession().removeAttribute("loginUser");
        request.getSession().removeAttribute("errorMsg");
        return "admin/login";
    }
}
