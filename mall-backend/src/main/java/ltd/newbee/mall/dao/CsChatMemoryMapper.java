package ltd.newbee.mall.dao;

import ltd.newbee.mall.entity.CsChatMemory;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 客服会话记忆 Mapper（M2-5）。
 *
 * <p>{@code selectRecent*} 两个方法都返回 <b>按时间升序</b>的「最近 N 条」——
 * 子查询先倒序取 N 条、外层再正序，保证直接喂给模型时顺序正确
 * （模型看到的对话必须是从旧到新）。
 */
public interface CsChatMemoryMapper {

    int insert(CsChatMemory record);

    /** 匿名会话维度：按 conversationId 取最近 N 条（升序） */
    List<CsChatMemory> selectRecentByConversation(@Param("conversationId") String conversationId,
                                                  @Param("limit") int limit);

    /** 登录用户维度：按 userId 取最近 N 条（升序） */
    List<CsChatMemory> selectRecentByUser(@Param("userId") Long userId,
                                          @Param("limit") int limit);

    /** 归档删除：删除 createdAt 早于 cutoff 的行，返回删除条数（DESIGN §7.4 保留策略） */
    int deleteOlderThan(@Param("cutoff") Date cutoff);
}
