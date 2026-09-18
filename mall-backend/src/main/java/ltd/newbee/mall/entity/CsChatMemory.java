package ltd.newbee.mall.entity;

import java.util.Date;

/**
 * 客服会话记忆（M2-5，对应 DESIGN §7.4）。
 *
 * <p>一行 = 一条消息（用户提问或客服回答），按 {@code id} 自增即天然的时间序。
 *
 * <p><b>会话维度</b>：登录用户以 {@code user_id} 为会话维度；未登录用户以
 * {@code conversation_id}（前端 localStorage 生成、随请求携带）为维度。
 * 两列都落库，便于将来做「匿名会话在登录后合并」这类分析，但<b>查询时只按其中之一</b>。
 *
 * <p>⚠️ <b>刻意不用 sessionId</b>：本项目未启用 Spring Session，session 是 Tomcat
 * 内存态、重启即变 —— 那样「记忆跨刷新/重启保留」在匿名场景永远无法满足。
 */
public class CsChatMemory {

    private Long id;

    /** 前端持久化的会话标识（未登录时的会话维度） */
    private String conversationId;

    /** 登录用户 id（登录时的会话维度）；匿名场景为 null */
    private Long userId;

    /** {@code user} 或 {@code assistant}（见 CsChatMemoryService 的常量） */
    private String role;

    private String content;

    private Date createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
