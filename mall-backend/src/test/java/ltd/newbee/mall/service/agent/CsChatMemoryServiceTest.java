package ltd.newbee.mall.service.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import ltd.newbee.mall.dao.CsChatMemoryMapper;
import ltd.newbee.mall.entity.CsChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2-5 验收测试：会话记忆（维度选择 / 角色映射 / 注入上限 / 保留策略 / 不阻塞与降级）。
 *
 * <p>用 mock 的 Mapper，不依赖 MySQL —— 这里验证的是<b>记忆逻辑</b>；
 * SQL 本身的正确性（最近 N 条升序、按维度取、按时间删除）由 {@code CsChatMemoryMapperIT}
 * 打真实库验证。
 */
class CsChatMemoryServiceTest {

    private static final int MAX_INJECTED = 20;
    private static final int RETENTION_DAYS = 30;

    private CsChatMemoryMapper mapper;
    private CsChatMemoryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CsChatMemoryMapper.class);
        service = new CsChatMemoryService(mapper, true, MAX_INJECTED, RETENTION_DAYS);
    }

    private static CsChatMemory row(String conversationId, Long userId, String role, String content) {
        CsChatMemory row = new CsChatMemory();
        row.setId(1L);
        row.setConversationId(conversationId);
        row.setUserId(userId);
        row.setRole(role);
        row.setContent(content);
        row.setCreatedAt(new Date());
        return row;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    @Test
    @DisplayName("注入历史：角色映射成 UserMessage / AiMessage，且保持从旧到新")
    void shouldMapRolesInOrder() {
        when(mapper.selectRecentByConversation(eq("conv-1"), anyInt())).thenReturn(List.of(
                row("conv-1", null, CsChatMemoryService.ROLE_USER, "第一轮问题"),
                row("conv-1", null, CsChatMemoryService.ROLE_ASSISTANT, "第一轮回答"),
                row("conv-1", null, CsChatMemoryService.ROLE_USER, "第二轮问题"),
                row("conv-1", null, CsChatMemoryService.ROLE_ASSISTANT, "第二轮回答")));

        List<ChatMessage> history = service.loadHistory("conv-1", null);

        assertEquals(4, history.size());
        assertTrue(history.get(0) instanceof UserMessage);
        assertEquals("第一轮问题", ((UserMessage) history.get(0)).singleText());
        assertTrue(history.get(1) instanceof AiMessage);
        assertEquals("第一轮回答", ((AiMessage) history.get(1)).text());
        assertTrue(history.get(2) instanceof UserMessage);
        assertTrue(history.get(3) instanceof AiMessage);
        assertEquals("第二轮回答", ((AiMessage) history.get(3)).text());
    }

    @Test
    @DisplayName("维度：登录用户按 userId 取（跨设备也能续上），匿名才按 conversationId")
    void shouldPreferUserDimensionWhenLoggedIn() {
        when(mapper.selectRecentByUser(eq(7L), anyInt())).thenReturn(List.of());

        service.loadHistory("conv-1", 7L);

        verify(mapper).selectRecentByUser(7L, MAX_INJECTED);
        verify(mapper, never()).selectRecentByConversation(any(), anyInt());
    }

    @Test
    @DisplayName("维度：匿名按 conversationId 取，且不认识 sessionId（本项目不启用 Spring Session）")
    void shouldUseConversationDimensionWhenAnonymous() {
        when(mapper.selectRecentByConversation(eq("conv-1"), anyInt())).thenReturn(List.of());

        service.loadHistory("conv-1", null);

        verify(mapper).selectRecentByConversation("conv-1", MAX_INJECTED);
        verify(mapper, never()).selectRecentByUser(any(), anyInt());
    }

    @Test
    @DisplayName("注入上限：查询就带上限 N 条（10 轮 = 20 条，不能让历史无限增长）")
    void shouldCapInjectedMessages() {
        service.loadHistory("conv-1", null);
        verify(mapper).selectRecentByConversation("conv-1", MAX_INJECTED);
    }

    @Test
    @DisplayName("存储失败只降级：读取异常 → 空历史 + 不抛（问答必须继续）")
    void shouldDegradeWhenReadFails() {
        when(mapper.selectRecentByConversation(any(), anyInt()))
                .thenThrow(new RuntimeException("Table 'cs_chat_memory' doesn't exist"));

        List<ChatMessage> history = assertDoesNotThrow(() -> service.loadHistory("conv-1", null));

        assertTrue(history.isEmpty(), "读不到历史就当作没有历史");
    }

    @Test
    @DisplayName("未知 role 不猜测语义：跳过该条（否则可能把系统文本当成用户发言注入）")
    void shouldSkipUnknownRole() {
        when(mapper.selectRecentByConversation(any(), anyInt())).thenReturn(List.of(
                row("conv-1", null, "system", "内部提示词"),
                row("conv-1", null, CsChatMemoryService.ROLE_USER, "真问题")));

        List<ChatMessage> history = service.loadHistory("conv-1", null);

        assertEquals(1, history.size(), "未知角色应被跳过");
        assertEquals("真问题", ((UserMessage) history.get(0)).singleText());
    }

    @Test
    @DisplayName("既无 conversationId 又未登录：跳过读取并留痕（不静默）")
    void shouldSkipReadWhenNoDimension() {
        assertTrue(service.loadHistory(null, null).isEmpty());
        verify(mapper, never()).selectRecentByConversation(any(), anyInt());
        verify(mapper, never()).selectRecentByUser(any(), anyInt());
    }

    @Test
    @DisplayName("开关关闭时完全不碰数据库（便于本地关掉记忆）")
    void disabledShouldSkipEverything() {
        CsChatMemoryService off = new CsChatMemoryService(mapper, false, MAX_INJECTED, RETENTION_DAYS);

        assertTrue(off.loadHistory("conv-1", null).isEmpty());
        off.appendTurnAsync("conv-1", null, "问题", "回答");
        assertEquals(0, off.purgeExpired());

        verify(mapper, never()).selectRecentByConversation(any(), anyInt());
        verify(mapper, never()).insert(any());
        verify(mapper, never()).deleteOlderThan(any());
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("落库：一问一答各一行，异步执行（不阻塞流式响应）")
    void shouldInsertUserAndAssistantRows() {
        service.appendTurnAsync("conv-1", null, "化妆水有货吗", "有的，库存 1000 件");

        ArgumentCaptor<CsChatMemory> rows = ArgumentCaptor.forClass(CsChatMemory.class);
        verify(mapper, timeout(2000).times(2)).insert(rows.capture());

        List<CsChatMemory> inserted = rows.getAllValues();
        assertEquals(CsChatMemoryService.ROLE_USER, inserted.get(0).getRole());
        assertEquals("化妆水有货吗", inserted.get(0).getContent());
        assertEquals(CsChatMemoryService.ROLE_ASSISTANT, inserted.get(1).getRole());
        assertEquals("有的，库存 1000 件", inserted.get(1).getContent());
        assertEquals("conv-1", inserted.get(0).getConversationId());
        assertEquals("conv-1", inserted.get(1).getConversationId());
    }

    @Test
    @DisplayName("登录用户落库时带上 userId（会话维度用它）")
    void shouldPersistUserIdWhenLoggedIn() {
        service.appendTurnAsync("conv-1", 7L, "问题", "回答");

        ArgumentCaptor<CsChatMemory> rows = ArgumentCaptor.forClass(CsChatMemory.class);
        verify(mapper, timeout(2000).times(2)).insert(rows.capture());
        assertEquals(7L, rows.getAllValues().get(0).getUserId());
    }

    @Test
    @DisplayName("落库失败不抛给调用方（回答已经推给用户了，不能因为写库失败而报错）")
    void shouldNotThrowWhenInsertFails() {
        when(mapper.insert(any())).thenThrow(new RuntimeException("deadlock"));

        assertDoesNotThrow(() -> service.appendTurnAsync("conv-1", null, "问题", "回答"));

        // 确认真的尝试过写入（不是悄悄跳过）
        verify(mapper, timeout(2000).atLeastOnce()).insert(any());
    }

    @Test
    @DisplayName("没有会话维度时不落库（无法归集），但也不报错")
    void shouldSkipWriteWhenNoDimension() {
        assertDoesNotThrow(() -> service.appendTurnAsync(null, null, "问题", "回答"));
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("空白 conversationId 归一为 NULL 落库（避免脏数据出现 '' 这种维度值）")
    void blankConversationIdShouldBecomeNull() {
        service.appendTurnAsync("   ", 7L, "问题", "回答");

        ArgumentCaptor<CsChatMemory> rows = ArgumentCaptor.forClass(CsChatMemory.class);
        verify(mapper, timeout(2000).times(2)).insert(rows.capture());
        assertNull(rows.getAllValues().get(0).getConversationId());
    }

    // ------------------------------------------------------------------
    // 保留策略
    // ------------------------------------------------------------------

    @Test
    @DisplayName("归档：按「保留 30 天」计算截止时间，删除并返回条数")
    void purgeShouldUseRetentionCutoff() {
        when(mapper.deleteOlderThan(any())).thenReturn(5);

        assertEquals(5, service.purgeExpired());

        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(mapper).deleteOlderThan(cutoff.capture());
        long days = (System.currentTimeMillis() - cutoff.getValue().getTime()) / (24L * 60 * 60 * 1000);
        assertEquals(RETENTION_DAYS, days, "截止时间应为「现在 - 30 天」");
    }

    @Test
    @DisplayName("归档任务失败不冒泡（定时任务里抛出只会变成调度器噪音）")
    void purgeDailyShouldSwallowFailure() {
        when(mapper.deleteOlderThan(any())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service.purgeExpiredDaily());
    }

    @Test
    @DisplayName("写入用的是虚拟线程执行器（异步），调用方立即返回")
    void writeShouldBeAsync() {
        List<Long> callThreadIds = new ArrayList<>();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            callThreadIds.add(Thread.currentThread().threadId());
            return 1;
        });

        long callerThread = Thread.currentThread().threadId();
        service.appendTurnAsync("conv-1", null, "问题", "回答");

        // 落库发生在另一个线程（虚拟线程）上，即「不阻塞流式响应」
        verify(mapper, timeout(2000).times(2)).insert(any());
        assertTrue(callThreadIds.stream().noneMatch(id -> id == callerThread),
                "落库不应发生在调用线程上");
    }
}
