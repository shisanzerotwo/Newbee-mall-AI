package ltd.newbee.mall.service.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.PreDestroy;
import ltd.newbee.mall.dao.CsChatMemoryMapper;
import ltd.newbee.mall.entity.CsChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 客服会话记忆（M2-5，DESIGN §7.4）。
 *
 * <h3>会话维度（刻意不用 sessionId）</h3>
 * 登录用户按 {@code user_id} 归集；未登录按前端持久的 {@code conversationId} 归集。
 * 本项目<b>未启用 Spring Session</b>，session 是 Tomcat 内存态、重启即变 ——
 * 用它的话「记忆跨刷新/重启保留」在匿名场景永远不可能满足。
 *
 * <h3>两条不变量</h3>
 * <ol>
 *   <li><b>读写都不阻塞回答</b>：读在问答开始前同步做（很短的一次索引查询），
 *       写在回答结束后<b>异步</b>落库（{@link #appendTurnAsync}）——
 *       流式响应已经推完还被 DB 拖住是本末倒置。</li>
 *   <li><b>记忆故障不影响问答</b>：任何异常都<b>看得到地</b>降级（WARN + 空历史 / 跳过落库），
 *       绝不因为记忆把回答吞掉，也不静默 —— 这是有日志的降级。</li>
 * </ol>
 *
 * <h3>保留策略</h3>
 * 单会话注入上限 {@code cs.memory.max-injected-messages}（默认 20 条 = 10 轮）；
 * 超过 {@code cs.memory.retention-days}（默认 30 天）的行由每日定时任务归档删除。
 */
@Service
public class CsChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(CsChatMemoryService.class);

    /** 角色常量：与表里 role 列的取值一一对应 */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private final CsChatMemoryMapper mapper;

    private final boolean enabled;
    private final int maxInjectedMessages;
    private final int retentionDays;

    /** 落库用的执行器：一次落库就是两次 INSERT，虚拟线程最合适（Java 21），且不占请求线程 */
    private final ExecutorService writerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CsChatMemoryService(CsChatMemoryMapper mapper,
                               @Value("${cs.memory.enabled:true}") boolean enabled,
                               @Value("${cs.memory.max-injected-messages:20}") int maxInjectedMessages,
                               @Value("${cs.memory.retention-days:30}") int retentionDays) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.maxInjectedMessages = Math.max(2, maxInjectedMessages);
        this.retentionDays = Math.max(1, retentionDays);
        log.info("会话记忆就绪：enabled={}，单会话注入上限={} 条，保留 {} 天", enabled,
                this.maxInjectedMessages, this.retentionDays);
    }

    @PreDestroy
    public void shutdown() {
        writerExecutor.shutdown();
    }

    /**
     * 取该会话最近的一批消息，转成 LangChain4j 的对话历史（从旧到新）。
     *
     * <p>查询维度：有 {@code userId} 时按用户维度（登录用户跨设备也能续上），
     * 否则按 {@code conversationId}。失败时返回空历史 + WARN（问答继续，只是没有记忆）。
     */
    public List<ChatMessage> loadHistory(String conversationId, Long userId) {
        if (!enabled) {
            return List.of();
        }
        if (userId == null && isBlank(conversationId)) {
            log.warn("既无登录用户也无 conversationId，本次问答不注入历史（记忆维度缺失）");
            return List.of();
        }
        try {
            List<CsChatMemory> rows = (userId != null)
                    ? mapper.selectRecentByUser(userId, maxInjectedMessages)
                    : mapper.selectRecentByConversation(conversationId, maxInjectedMessages);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            List<ChatMessage> messages = new ArrayList<>(rows.size());
            for (CsChatMemory row : rows) {
                if (ROLE_USER.equalsIgnoreCase(row.getRole())) {
                    messages.add(UserMessage.from(row.getContent()));
                } else if (ROLE_ASSISTANT.equalsIgnoreCase(row.getRole())) {
                    messages.add(AiMessage.from(row.getContent()));
                } else {
                    // 未知角色不猜测语义：跳过并留痕（否则可能把系统文本当成用户发言注入）
                    log.warn("会话记忆中出现未知 role={}（id={}），已跳过该条", row.getRole(), row.getId());
                }
            }
            return messages;
        } catch (Exception e) {
            log.warn("读取会话记忆失败，本次问答降级为『无历史』：{}", e.toString());
            return List.of();
        }
    }

    /**
     * 一轮问答结束后<b>异步</b>落库（用户问题 + 客服回答各一行）。
     *
     * <p>不阻塞流式响应，也不把异常抛给调用方：落库失败只 WARN。
     */
    public void appendTurnAsync(String conversationId, Long userId, String question, String answer) {
        if (!enabled) {
            return;
        }
        if (userId == null && isBlank(conversationId)) {
            log.warn("既无登录用户也无 conversationId，本轮对话不落库（不影响本次回答）");
            return;
        }
        try {
            writerExecutor.execute(() -> {
                try {
                    Date now = new Date();
                    mapper.insert(row(conversationId, userId, ROLE_USER, question, now));
                    mapper.insert(row(conversationId, userId, ROLE_ASSISTANT, answer, now));
                } catch (Exception e) {
                    log.warn("会话记忆落库失败（不影响已完成的回答）：{}", e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            // 只可能发生在应用关停期间；此时丢弃记忆比让请求报错合理，但必须留痕
            log.warn("会话记忆落库任务被拒绝（应用正在关停？）：{}", e.toString());
        }
    }

    /**
     * 归档删除超过保留期的会话，返回删除条数。
     *
     * <p>包级可见以便直接测（{@link #purgeExpiredDaily()} 是它的定时外壳）。
     */
    int purgeExpired() {
        if (!enabled) {
            return 0;
        }
        Date cutoff = new Date(System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000);
        int deleted = mapper.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("会话记忆归档：删除 {} 条早于 {} 的记录（保留 {} 天）", deleted, cutoff, retentionDays);
        }
        return deleted;
    }

    /** 每日归档（03:30，避开 03:00 的知识库重建，避免同一时段双写） */
    @Scheduled(cron = "0 30 3 * * ?")
    void purgeExpiredDaily() {
        try {
            purgeExpired();
        } catch (Exception e) {
            // 定时任务里的异常若不接住会被调度器吞成日志噪音；这里显式记录
            log.warn("会话记忆归档任务失败：{}", e.toString());
        }
    }

    private static CsChatMemory row(String conversationId, Long userId, String role, String content, Date at) {
        CsChatMemory row = new CsChatMemory();
        row.setConversationId(isBlank(conversationId) ? null : conversationId);
        row.setUserId(userId);
        row.setRole(role);
        row.setContent(content);
        row.setCreatedAt(at);
        return row;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
