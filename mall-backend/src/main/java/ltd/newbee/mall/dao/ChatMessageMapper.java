package ltd.newbee.mall.dao;

import ltd.newbee.mall.entity.ChatMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 私聊消息 DAO（M4） */
public interface ChatMessageMapper {

    int insert(ChatMessage message);

    List<ChatMessage> selectByConversation(@Param("conversationId") String conversationId,
                                          @Param("limit") int limit);

    List<Map<String, Object>> selectSessions(@Param("userId") Long userId,
                                             @Param("limit") int limit);

    ChatMessage selectLastMessage(@Param("id") Long id);

    int countUnread(@Param("userId") Long userId);

    int markRead(@Param("conversationId") String conversationId, @Param("userId") Long userId);
}