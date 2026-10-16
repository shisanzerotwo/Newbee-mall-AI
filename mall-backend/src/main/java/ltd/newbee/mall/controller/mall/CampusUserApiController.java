package ltd.newbee.mall.controller.mall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.controller.vo.NewBeeMallUserVO;
import ltd.newbee.mall.dao.CampusWantMapper;
import ltd.newbee.mall.dao.MallUserMapper;
import ltd.newbee.mall.entity.MallUser;
import ltd.newbee.mall.util.MD5Util;
import ltd.newbee.mall.util.Result;
import ltd.newbee.mall.util.ResultGenerator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 校园二手用户 REST 接口（M4）。
 *
 * <p>鉴权沿用项目既有机制：<b>HttpSession + Cookie</b>（无 JWT，见设计文档 v1.1 变更记录）。
 * 与既有 {@code POST /login}（表单 + 图形验证码）的区别：这里是 JSON API，<b>不含验证码</b>
 * —— 便于移动端与自动化测试；如用于生产，需补验证码或设备指纹（安全项见设计文档 §9）。</p>
 */
@RestController
@RequestMapping("/api/user")
public class CampusUserApiController {

    private final MallUserMapper userMapper;
    private final CampusWantMapper wantMapper;

    public CampusUserApiController(MallUserMapper userMapper, CampusWantMapper wantMapper) {
        this.userMapper = userMapper;
        this.wantMapper = wantMapper;
    }

    /**
     * 登录（学号/手机号 + 密码）。请求体：
     * {@code {"loginName":"13800000001","password":"xxx"}}
     * 成功返回 {@code {userId, nickName, loginName, campusVerified, creditScore, ...}}
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginRequest body, HttpServletRequest request) {
        if (body == null || body.loginName() == null || body.loginName().isBlank()
                || body.password() == null || body.password().isBlank()) {
            return ResultGenerator.genFailResult("请输入账号和密码");
        }
        String passwordMD5 = MD5Util.MD5Encode(body.password(), "UTF-8");
        MallUser user = userMapper.selectByLoginNameAndPasswd(body.loginName().trim(), passwordMD5);
        if (user == null) {
            return ResultGenerator.genFailResult("账号或密码错误");
        }
        if (user.getLockedFlag() != null && user.getLockedFlag() == 1) {
            return ResultGenerator.genFailResult("账号已被锁定，请联系管理员");
        }
        NewBeeMallUserVO vo = new NewBeeMallUserVO();
        vo.setUserId(user.getUserId());
        vo.setNickName(user.getNickName());
        vo.setLoginName(user.getLoginName());
        vo.setIntroduceSign(user.getIntroduceSign());
        request.getSession().setAttribute(Constants.MALL_USER_SESSION_KEY, vo);
        return ResultGenerator.genSuccessResult(profile(vo, request.getSession()));
    }

    /** 当前登录用户资料（含校园认证、信用分、统计）；未登录返回 416 */
    @GetMapping("/info")
    public Result info(HttpSession session) {
        NewBeeMallUserVO vo = currentUser(session);
        if (vo == null) {
            return ResultGenerator.genErrorResult(416, "未登录");
        }
        return ResultGenerator.genSuccessResult(profile(vo, session));
    }

    /** 退出登录 */
    @PostMapping("/logout")
    public Result logout(HttpSession session) {
        if (session != null) {
            session.removeAttribute(Constants.MALL_USER_SESSION_KEY);
        }
        return ResultGenerator.genSuccessResult();
    }

    // ------------------------------------------------------------------

    /**
     * 组装前端所需的用户资料。
     * 说明：校园认证 / 信用分 / 交易统计目前是演示口径（二期落库为独立字段与统计表）；
     * 「我想要」数量来自真实表 {@code tb_campus_want}。
     */
    private Map<String, Object> profile(NewBeeMallUserVO vo, HttpSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", vo.getUserId());
        data.put("nickname", vo.getNickName());
        data.put("loginName", vo.getLoginName());
        data.put("campusVerified", true);
        data.put("creditScore", 780);
        data.put("school", "伊犁师范大学 · 本部");
        data.put("balance", 326.00);
        data.put("praiseRate", 100);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("published", 8);
        stats.put("bought", 15);
        stats.put("wanted", wantMapper.countByUser(vo.getUserId()));
        stats.put("seeking", 4);
        data.put("stats", stats);
        return data;
    }

    private NewBeeMallUserVO currentUser(HttpSession session) {
        Object attr = session == null ? null : session.getAttribute(Constants.MALL_USER_SESSION_KEY);
        return attr instanceof NewBeeMallUserVO vo ? vo : null;
    }

    public record LoginRequest(String loginName, String password) {
    }
}
