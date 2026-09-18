package ltd.newbee.mall.dao;

import jakarta.annotation.Resource;
import ltd.newbee.mall.entity.CsChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **真实 MySQL** 验证 {@link CsChatMemoryMapper} 的 SQL（M2-5）。
 *
 * <p>存在理由：单元测试用 mock Mapper，看不见 SQL。而这里有两处<b>只有真库才会暴露</b>的细节：
 * <ol>
 *   <li><b>「最近 N 条」的顺序</b>：子查询倒序取 N、外层正序 —— 若把 limit 直接写在正序查询上，
 *       会取到<b>最旧</b>的 N 条（模型看到的对话就反了）</li>
 *   <li><b>维度隔离</b>：匿名（conversation_id）与登录（user_id）两套维度不能互相串</li>
 * </ol>
 *
 * <p>命名以 {@code IT} 结尾（Surefire 默认只跑 {@code *Test}），所以不会被 {@code mvn test}
 * 全量跑到（它需要真库）。需要时手动跑：
 * <pre>
 *   export DB_PASSWORD=... &amp;&amp; bash ops/mvn.sh test -Dtest=CsChatMemoryMapperIT
 * </pre>
 *
 * <p>前置：本机 MySQL 已有 {@code cs_chat_memory} 表（{@code ops/init.sql} 末尾的 DDL；
 * 已存在的库需手工执行一次）。
 */
@SpringBootTest(properties = {
        // 关掉知识库异步建库：本测试只关心记忆表，没必要加载 90MB 嵌入模型
        "cs.rag.enabled=false"
})
class CsChatMemoryMapperIT {

    /** 本测试专用的 conversationId 前缀，便于精确清理（绝不误删他人数据） */
    private static final String PREFIX = "it-m25-";

    private static final String CONV_A = PREFIX + "conv-a";
    private static final String CONV_B = PREFIX + "conv-b";

    @Resource
    private CsChatMemoryMapper mapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        // 只删本测试造的数据；deleteOlderThan 是全局策略，不拿它做清理
        jdbcTemplate.update("delete from cs_chat_memory where conversation_id like ?", PREFIX + "%");
    }

    private void insert(String conversationId, Long userId, String role, String content) {
        CsChatMemory row = new CsChatMemory();
        row.setConversationId(conversationId);
        row.setUserId(userId);
        row.setRole(role);
        row.setContent(content);
        row.setCreatedAt(new Date());
        assertEquals(1, mapper.insert(row), "insert 应影响 1 行");
    }

    @Test
    @DisplayName("selectRecentByConversation：取「最近 N 条」且按时间升序（不是最旧的 N 条）")
    void shouldReturnMostRecentNInAscendingOrder() {
        for (int i = 1; i <= 7; i++) {
            insert(CONV_A, null, "user", "问题" + i);
        }

        List<CsChatMemory> recent = mapper.selectRecentByConversation(CONV_A, 4);

        assertEquals(4, recent.size());
        // 取到的必须是最后 4 条（问题 4~7），并且是升序（旧 → 新）
        assertEquals(List.of("问题4", "问题5", "问题6", "问题7"),
                recent.stream().map(CsChatMemory::getContent).toList(),
                "顺序必须是「最近 N 条、从旧到新」—— 顺序错了模型看到的对话就是反的");
    }

    @Test
    @DisplayName("维度隔离：匿名会话之间互不串（conversation_id 维度）")
    void shouldIsolateAnonymousConversations() {
        insert(CONV_A, null, "user", "A 的问题");
        insert(CONV_B, null, "user", "B 的问题");

        List<CsChatMemory> onlyA = mapper.selectRecentByConversation(CONV_A, 20);

        assertEquals(1, onlyA.size());
        assertEquals("A 的问题", onlyA.get(0).getContent());
    }

    @Test
    @DisplayName("维度隔离：登录用户按 user_id 取（跨 conversationId 也能续上）")
    void shouldSelectByUserDimension() {
        long userId = 987654321L;
        try {
            insert(PREFIX + "phone", userId, "user", "手机上的问题");
            insert(PREFIX + "desktop", userId, "assistant", "电脑上的回答");
            insert(CONV_A, null, "user", "匿名的问题");

            List<CsChatMemory> byUser = mapper.selectRecentByUser(userId, 20);

            assertEquals(2, byUser.size(), "同一 user_id 的跨会话消息都应取到");
            assertEquals("手机上的问题", byUser.get(0).getContent(), "应升序（旧 → 新）");
            assertEquals("电脑上的回答", byUser.get(1).getContent());
        } finally {
            jdbcTemplate.update("delete from cs_chat_memory where user_id = ?", userId);
        }
    }

    @Test
    @DisplayName("保留策略：deleteOlderThan 删掉过期行、留下未过期行")
    void shouldDeleteOnlyExpiredRows() {
        String expiredConv = PREFIX + "expired";
        String freshConv = PREFIX + "fresh";
        Timestamp old = new Timestamp(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000);
        jdbcTemplate.update("insert into cs_chat_memory (conversation_id, role, content, created_at) "
                + "values (?, 'user', '过期对话', ?)", expiredConv, old);
        insert(freshConv, null, "user", "新对话");

        // 注意：这是全局策略（按时间归档），会删除表内所有超过 30 天的行 —— 这正是设计意图
        int deleted = mapper.deleteOlderThan(new Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000));

        assertTrue(deleted >= 1, "至少应删掉刚插入的过期行");
        assertTrue(mapper.selectRecentByConversation(expiredConv, 10).isEmpty(), "过期行应已删除");
        assertEquals(1, mapper.selectRecentByConversation(freshConv, 10).size(), "未过期行必须保留");
    }

    @Test
    @DisplayName("conversation_id 列宽足够（64 字符的 UUID 不能被截断）")
    void conversationIdShouldHoldUuid() {
        String uuid = java.util.UUID.randomUUID().toString();   // 36 字符
        insert(uuid, null, "user", "uuid 会话");

        List<CsChatMemory> rows = mapper.selectRecentByConversation(uuid, 5);
        assertEquals(1, rows.size());
        assertEquals(uuid, rows.get(0).getConversationId(), "conversation_id 必须原样存取");

        jdbcTemplate.update("delete from cs_chat_memory where conversation_id = ?", uuid);
    }
}
