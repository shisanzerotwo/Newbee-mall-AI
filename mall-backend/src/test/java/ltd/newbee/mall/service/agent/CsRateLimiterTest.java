package ltd.newbee.mall.service.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接入面防护的单元测试（M3-C）。
 *
 * <p>对应 TASK_M3-C §5 的验收项 1~4：
 * 限流触发 / in-flight 去重 / 不同会话互不影响 / <b>释放正确（不会永久锁死）</b>。
 *
 * <p>纯单元测试（不起 Spring）—— 限流逻辑必须能在毫秒级被反复验证。
 */
class CsRateLimiterTest {

    private static final String KEY = "c:conv-1";

    @Test
    @DisplayName("限流触发：容量 3 → 前 3 次放行，第 4 次以 RATE 拒绝且文案可读")
    void deniesAfterCapacityExhausted() {
        CsRateLimiter limiter = new CsRateLimiter(3, 100);

        for (int i = 1; i <= 3; i++) {
            assertTrue(limiter.tryAcquire(KEY).allowed(), "第 " + i + " 次应放行");
        }

        CsRateLimiter.Decision denied = limiter.tryAcquire(KEY);
        assertFalse(denied.allowed(), "第 4 次必须被拒绝");
        assertEquals(CsRateLimiter.DenyKind.RATE, denied.kind());
        assertNotNull(denied.message());
        assertTrue(denied.message().contains("每分钟最多 3 次"),
                "文案要让用户知道限制是什么，实际=" + denied.message());
    }

    @Test
    @DisplayName("不误伤：不同会话各有各的桶（A 被限流不影响 B）")
    void differentKeysAreIndependent() {
        CsRateLimiter limiter = new CsRateLimiter(2, 100);

        assertTrue(limiter.tryAcquire("c:A").allowed());
        assertTrue(limiter.tryAcquire("c:A").allowed());
        assertFalse(limiter.tryAcquire("c:A").allowed(), "A 应已耗尽");

        assertTrue(limiter.tryAcquire("c:B").allowed(), "B 不应受 A 影响");
        assertTrue(limiter.tryAcquire("u:42").allowed(), "登录用户是另一个维度，也不应受影响");
        assertTrue(limiter.tryAcquire("ip:1.2.3.4").allowed(), "IP 兜底键同理");
    }

    @Test
    @DisplayName("in-flight：同一会话同时只允许 1 个在途请求，且 release 后立刻恢复")
    void inFlightDedupAndRelease() {
        CsRateLimiter limiter = new CsRateLimiter(100, 1);

        assertTrue(limiter.tryAcquire(KEY).allowed(), "第一个应放行");
        assertEquals(1, limiter.inFlightCount(KEY));

        CsRateLimiter.Decision second = limiter.tryAcquire(KEY);
        assertFalse(second.allowed(), "上一条还在途，第二个必须被拒（不排队）");
        assertEquals(CsRateLimiter.DenyKind.IN_FLIGHT, second.kind());
        assertTrue(second.message().contains("还在回答中"),
                "文案要说清是「上一条还没答完」，实际=" + second.message());

        // ⭐ 释放正确 —— 这一条最重要：漏了它，该会话会被永久锁死
        limiter.release(KEY);
        assertEquals(0, limiter.inFlightCount(KEY), "释放后计数必须归零");
        assertTrue(limiter.tryAcquire(KEY).allowed(), "释放后同一会话必须能再次请求");
    }

    @Test
    @DisplayName("重复 release 是幂等的（不会把计数减成负数或误删别人的名额）")
    void releaseIsIdempotent() {
        CsRateLimiter limiter = new CsRateLimiter(100, 1);

        assertTrue(limiter.tryAcquire(KEY).allowed());
        limiter.release(KEY);
        limiter.release(KEY);           // 多释放一次不应抛异常
        limiter.release("不存在的键");    // 也不应抛异常

        assertEquals(0, limiter.inFlightCount(KEY));
        assertTrue(limiter.tryAcquire(KEY).allowed(), "状态应仍然可用");
    }

    @Test
    @DisplayName("检查顺序固定：先令牌、后在途 —— 令牌耗尽时报 RATE 而不是 IN_FLIGHT")
    void rateIsCheckedBeforeInFlight() {
        CsRateLimiter limiter = new CsRateLimiter(1, 1);

        assertTrue(limiter.tryAcquire(KEY).allowed());
        // 此时既没令牌、又有在途；顺序固定后必须是 RATE（否则日志/用量口径会飘）
        assertEquals(CsRateLimiter.DenyKind.RATE, limiter.tryAcquire(KEY).kind());
    }

    @Test
    @DisplayName("令牌会随时间恢复（容量 60/分钟 ≈ 每秒 1 个）")
    void tokensRefillOverTime() throws InterruptedException {
        CsRateLimiter limiter = new CsRateLimiter(60, 100);

        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.tryAcquire(KEY).allowed(), "前 60 次应放行，第 " + (i + 1) + " 次失败");
        }
        assertFalse(limiter.tryAcquire(KEY).allowed(), "已耗尽");

        Thread.sleep(1200);   // 等约 1.2 秒 → 应补回至少 1 个令牌
        assertTrue(limiter.tryAcquire(KEY).allowed(), "等待后应恢复额度，否则限流会变成永久封禁");
    }

    @Test
    @DisplayName("大量不同会话键不会让内部结构无限增长（惰性清理路径被真正走到）")
    void manyDistinctKeysDoNotBreak() {
        CsRateLimiter limiter = new CsRateLimiter(10, 1);

        // ⚠️ 这里必须显著超过内部的 MAX_BUCKETS(=10_000)——
        //    旧版只循环 1200 次、并断言 bucketCount() > 0，
        //    结果是“把清理逻辑删掉也能过”（假绿）。
        //    claude 复核指出后用“循环超过上限 + 断言不超上限”才能真正覆盖清理路径。
        int total = 12_000;
        for (int i = 0; i < total; i++) {
            assertTrue(limiter.tryAcquire("c:flood-" + i).allowed(), "新会话首次都应放行");
        }

        // 真正的不变量：桶数**有界**（惰性清理在起作用）。
        // ⚠️ 不能断言“≤ MAX_BUCKETS(10000)”：清理是每 SWEEP_EVERY(512) 次插入才跑一次，
        //    两次清理之间会自然长回来 → 正确上界是 MAX_BUCKETS + SWEEP_EVERY。
        //    （我第一版写成严格 ≤ 10000，实测 11760 就红了 —— 是断言错，不是实现错）
        int bound = 10_000 + 512;
        assertTrue(limiter.bucketCount() <= bound,
                "桶数应有界（≤" + bound + "），实际=" + limiter.bucketCount());
        assertTrue(limiter.bucketCount() > 0, "桶应被创建（清理不应把工作集清空）");

        // 清理之后，新的键仍然能正常工作（没有把功能一起清掉）
        assertTrue(limiter.tryAcquire("c:after-sweep").allowed(),
                "清理后新会话仍应正常放行");
    }
}
