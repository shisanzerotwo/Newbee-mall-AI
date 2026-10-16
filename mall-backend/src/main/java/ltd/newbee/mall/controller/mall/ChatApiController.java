package ltd.newbee.mall.controller.mall;

import jakarta.servlet.http.HttpSession;
import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.controller.vo.NewBeeMallUserVO;
import ltd.newbee.mall.dao.ChatMessageMapper;
import ltd.newbee.mall.dao.MallUserMapper;
import ltd.newbee.mall.dao.NewBeeMallGoodsMapper;
import ltd.newbee.mall.entity.ChatMessage;
import ltd.newbee.mall.entity.MallUser;
import ltd.newbee.mall.entity.NewBeeMallGoods;
import ltd.newbee.mall.util.Result;
import ltd.newbee.mall.util.ResultGenerator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 私聊 IM 接口（M4）：买家 ↔ 卖家，围绕某件商品。
 *
 * <p>一期为<b>同步 REST 短轮询</b>（前端 3s 一次），不引入 WebSocket —— 与设计文档 §7 的
 * 「一期轮询 / 二期 WebSocket」一致。鉴权沿用 HttpSession；未登录返回 416。</p>
 *
 * <p>会话标识规则：{@code c_{minUid}_{maxUid}_{goodsId}}，保证同一对用户在同一商品下只有一个会话，
 * 且与传入顺序无关（防止前端拼错导致重复会话）。</p>
 */
@RestController
@RequestMapping("/api/im")
public class ChatApiController {

    private static final int MAX_CONTENT_LEN = 500;
    private static final int DEFAULT_LIMIT = 50;

    private final ChatMessageMapper chatMapper;
    private final MallUserMapper userMapper;
    private final NewBeeMallGoodsMapper goodsMapper;

    public ChatApiController(ChatMessageMapper chatMapper,
                             MallUserMapper userMapper,
                             NewBeeMallGoodsMapper goodsMapper) {
        this.chatMapper = chatMapper;
        this.userMapper = userMapper;
        this.goodsMapper = goodsMapper;
    }

    /** 会话列表（含对方昵称、商品标题与缩略、最后一条消息、未读数） */
    @GetMapping("/sessions")
    public Result sessions(HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : chatMapper.selectSessions(userId, DEFAULT_LIMIT)) {
            String conversationId = String.valueOf(row.get("conversationId"));
            Long lastId = toLong(row.get("lastId"));
            ChatMessage last = lastId == null ? null : chatMapper.selectLastMessage(lastId);
            Long peerId = last == null ? null
                    : (userId.equals(last.getSenderId()) ? last.getReceiverId() : last.getSenderId());

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("conversationId", conversationId);
            node.put("peerId", peerId);
            node.put("peerName", nickNameOf(peerId));
            node.put("goodsId", last == null ? null : last.getGoodsId());
            node.put("goodsTitle", goodsTitleOf(last == null ? null : last.getGoodsId()));
            node.put("lastContent", last == null ? "" : last.getContent());
            node.put("lastTime", last == null || last.getCreateTime() == null
                    ? "" : new SimpleDateFormat("MM-dd HH:mm").format(last.getCreateTime()));
            node.put("unread", unreadIn(conversationId, userId));
            out.add(node);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", out);
        data.put("unreadTotal", chatMapper.countUnread(userId));
        return ResultGenerator.genSuccessResult(data);
    }

    /** 拉取某个会话的消息（升序，最多 limit 条） */
    @GetMapping("/messages")
    public Result messages(@RequestParam String conversationId,
                           @RequestParam(defaultValue = "100") int limit,
                           HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        List<ChatMessage> list = chatMapper.selectByConversation(conversationId,
                Math.min(Math.max(1, limit), 200));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : list) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", m.getId());
            node.put("senderId", m.getSenderId());
            node.put("receiverId", m.getReceiverId());
            node.put("content", m.getContent());
            node.put("goodsId", m.getGoodsId());
            node.put("createTime", m.getCreateTime() == null ? null
                    : new SimpleDateFormat("HH:mm").format(m.getCreateTime()));
            out.add(node);
        }
        return ResultGenerator.genSuccessResult(out);
    }

    /**
     * 发送消息。入参 {@code {peerId, goodsId, content}}；服务端自行拼装规范化 conversationId。
     */
    @PostMapping("/messages")
    public Result send(@RequestBody SendRequest body, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        if (body == null || body.peerId() == null || body.content() == null || body.content().isBlank()) {
            return ResultGenerator.genFailResult("消息内容不能为空");
        }
        if (userId.equals(body.peerId())) {
            return ResultGenerator.genFailResult("不能给自己发消息");
        }
        if (body.content().length() > MAX_CONTENT_LEN) {
            return ResultGenerator.genFailResult("单条消息不得超过 " + MAX_CONTENT_LEN + " 字");
        }
        if (body.goodsId() != null && goodsMapper.selectByPrimaryKey(body.goodsId()) == null) {
            return ResultGenerator.genFailResult("商品不存在");
        }
        ChatMessage m = new ChatMessage();
        m.setConversationId(conversationId(userId, body.peerId(), body.goodsId()));
        m.setSenderId(userId);
        m.setReceiverId(body.peerId());
        m.setGoodsId(body.goodsId());
        m.setContent(body.content().trim());
        m.setIsRead(0);
        chatMapper.insert(m);
        return ResultGenerator.genSuccessResult(Map.of(
                "id", m.getId(),
                "conversationId", m.getConversationId(),
                "content", m.getContent()));
    }

    /** 进入会话即已读 */
    @PostMapping("/read")
    public Result markRead(@RequestParam String conversationId, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResultGenerator.genErrorResult(416, "请先登录");
        }
        return ResultGenerator.genSuccessResult(Map.of("updated", chatMapper.markRead(conversationId, userId)));
    }

    /** 生成规范化会话 id：与参数顺序无关 */
    public static String conversationId(Long a, Long b, Long goodsId) {
        long min = Math.min(a, b), max = Math.max(a, b);
        return "c_" + min + "_" + max + "_" + (goodsId == null ? 0 : goodsId);
    }

    // ------------------------------------------------------------------

    private int unreadIn(String conversationId, Long userId) {
        List<ChatMessage> list = chatMapper.selectByConversation(conversationId, 200);
        int n = 0;
        for (ChatMessage m : list) {
            if (userId.equals(m.getReceiverId()) && m.getIsRead() != null && m.getIsRead() == 0) {
                n++;
            }
        }
        return n;
    }

    private String nickNameOf(Long userId) {
        if (userId == null) {
            return "同学";
        }
        MallUser u = userMapper.selectByPrimaryKey(userId);
        return u == null || u.getNickName() == null ? "同学" : u.getNickName();
    }

    private String goodsTitleOf(Long goodsId) {
        if (goodsId == null) {
            return null;
        }
        NewBeeMallGoods g = goodsMapper.selectByPrimaryKey(goodsId);
        return g == null ? null : g.getGoodsName();
    }

    private static Long toLong(Object o) {
        return o == null ? null : Long.valueOf(String.valueOf(o));
    }

    private Long currentUserId(HttpSession session) {
        Object attr = session == null ? null : session.getAttribute(Constants.MALL_USER_SESSION_KEY);
        return attr instanceof NewBeeMallUserVO vo ? vo.getUserId() : null;
    }

    public record SendRequest(Long peerId, Long goodsId, String content) {
    }
}