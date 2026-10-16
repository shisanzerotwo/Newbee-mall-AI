package ltd.newbee.mall.entity;

import java.io.Serializable;
import java.util.Date;

/** 私聊消息（校园二手 M4）：买家-卖家围绕某件商品的对话。 */
public class ChatMessage implements Serializable {

    private Long id;
    /** 会话标识：c_{小userId}_{大userId}_{goodsId}，保证同一商品同一对用户只有一个会话 */
    private String conversationId;
    private Long senderId;
    private Long receiverId;
    private Long goodsId;
    private String content;
    private Integer isRead;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getSenderId() { return senderId; }
    public void setSenderId(Long senderId) { this.senderId = senderId; }
    public Long getReceiverId() { return receiverId; }
    public void setReceiverId(Long receiverId) { this.receiverId = receiverId; }
    public Long getGoodsId() { return goodsId; }
    public void setGoodsId(Long goodsId) { this.goodsId = goodsId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getIsRead() { return isRead; }
    public void setIsRead(Integer isRead) { this.isRead = isRead; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}