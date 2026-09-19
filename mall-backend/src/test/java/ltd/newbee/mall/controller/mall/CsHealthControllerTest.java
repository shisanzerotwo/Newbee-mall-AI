package ltd.newbee.mall.controller.mall;

import ltd.newbee.mall.service.agent.CsRateLimiter;
import ltd.newbee.mall.service.agent.CsStreamService;
import ltd.newbee.mall.service.agent.CsUsageMeter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2 验收：{@code /api/cs/health} 默认**只**暴露健康必需项（R9 收敛）。
 *
 * <p>本测试的重点不是"字段全不全"，而是两件容易做成假动作的事：
 * <ol>
 *   <li><b>默认响应里确实没有内部信息</b>（用 {@code assertFalse(containsKey)} ——
 *       不是"我看了一眼"）；</li>
 *   <li><b>{@code status} 是真的探测出来的</b>，不是写死的 {@code "UP"}
 *       —— 用「依赖不可达 → DEGRADED」断言把它钉住（否则这个端点等于装饰）。</li>
 * </ol>
 */
class CsHealthControllerTest {

    private static DataSource dbUp() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.isValid(anyInt())).thenReturn(true);
        return ds;
    }

    private static DataSource dbDown() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new java.sql.SQLException("boom"));
        return ds;
    }

    private static StringRedisTemplate redisUp() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection conn = mock(RedisConnection.class);
        when(template.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(conn);
        when(conn.ping()).thenReturn("PONG");
        return template;
    }

    private static StringRedisTemplate redisDown() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(template.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenThrow(new RuntimeException("redis down"));
        return template;
    }

    private static CsStreamService streamService() {
        CsStreamService service = mock(CsStreamService.class);
        when(service.qaModeName()).thenReturn("audit");
        return service;
    }

    private static CsHealthController controller(DataSource ds, StringRedisTemplate redis,
                                                 CsUsageMeter meter, boolean detail) {
        return new CsHealthController(ds, redis, streamService(), meter,
                new CsRateLimiter(10, 1), 120000L, 500, detail);
    }

    @Test
    @DisplayName("默认（开关关）：只回 status / db / redis，内部信息一个都不暴露")
    void defaultIsMinimal() throws Exception {
        CsUsageMeter meter = new CsUsageMeter(1000);
        meter.recordRequest();
        Map<String, Object> body = controller(dbUp(), redisUp(), meter, false).health();

        assertEquals("UP", body.get("status"));
        assertEquals("UP", body.get("db"));
        assertEquals("UP", body.get("redis"));
        assertFalse(body.containsKey("usage"), "用量计数属于内部信息，默认不得暴露");
        assertFalse(body.containsKey("limits"), "限流配置默认不得暴露");
        assertFalse(body.containsKey("qaMode"), "质检模式默认不得暴露");
        assertFalse(body.containsKey("sseTimeoutMs"), "SSE 超时配置默认不得暴露");
        assertEquals(3, body.size(), "默认响应体只应有 3 个键，实得 " + body.keySet());
    }

    @Test
    @DisplayName("开关打开：内部信息恢复（收敛 ≠ 删掉），且值是真实快照")
    void detailEnabledRestoresInternalFields() throws Exception {
        CsUsageMeter meter = new CsUsageMeter(1000);
        meter.recordRequest();
        meter.recordCompleted();
        Map<String, Object> body = controller(dbUp(), redisUp(), meter, true).health();

        assertEquals("audit", body.get("qaMode"));
        assertEquals(120000L, body.get("sseTimeoutMs"));

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) body.get("usage");
        assertEquals(1L, usage.get("requests"), "usage 应是与传入 meter 的同一份快照");
        assertEquals(1L, usage.get("completed"));

        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) body.get("limits");
        assertEquals(10L, limits.get("requestsPerMinute"));
        assertEquals(500, limits.get("maxQuestionChars"));
    }

    @Test
    @DisplayName("依赖不可达 → DEGRADED（status 是探出来的，不是写死的 UP）")
    void statusReflectsProbes() throws Exception {
        CsUsageMeter meter = new CsUsageMeter(1000);

        Map<String, Object> dbBroken = controller(dbDown(), redisUp(), meter, false).health();
        assertEquals("DEGRADED", dbBroken.get("status"));
        assertEquals("DOWN", dbBroken.get("db"));
        assertEquals("UP", dbBroken.get("redis"), "DB 挂了不该把 Redis 也说成 DOWN");

        Map<String, Object> redisBroken = controller(dbUp(), redisDown(), meter, false).health();
        assertEquals("DEGRADED", redisBroken.get("status"));
        assertEquals("UP", redisBroken.get("db"));
        assertEquals("DOWN", redisBroken.get("redis"));

        // 阳性对照：两边都通时必须回到 UP —— 否则上面的 DEGRADED 可能只是恒真
        Map<String, Object> allUp = controller(dbUp(), redisUp(), meter, false).health();
        assertEquals("UP", allUp.get("status"));
        assertTrue(allUp.containsKey("status"));
    }
}
