package ltd.newbee.mall.service.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用量记账的单元测试（M3-C，DESIGN §7.2「记录用量以估算成本」）。
 *
 * <p>验收点：计数正确、能给出可读汇总、汇报频率配置不会把调用方搞炸。
 */
class CsUsageMeterTest {

    @Test
    @DisplayName("各项计数与汇总映射一致")
    void countsAreRecorded() {
        CsUsageMeter meter = new CsUsageMeter(1000);

        for (int i = 0; i < 5; i++) {
            meter.recordRequest();
        }
        meter.recordDenied(CsRateLimiter.DenyKind.RATE);
        meter.recordDenied(CsRateLimiter.DenyKind.RATE);
        meter.recordDenied(CsRateLimiter.DenyKind.IN_FLIGHT);
        meter.recordDenied(CsRateLimiter.DenyKind.OK);        // OK 不应计入任何拒绝
        meter.recordCompleted();
        meter.recordFailed();
        meter.recordToolCalls(3);
        meter.recordToolCalls(0);                             // 0 不应改变计数

        Map<String, Long> m = meter.summaryMap();
        assertEquals(5L, m.get("requests"));
        assertEquals(2L, m.get("deniedRate"));
        assertEquals(1L, m.get("deniedInFlight"));
        assertEquals(1L, m.get("completed"));
        assertEquals(1L, m.get("failed"));
        assertEquals(3L, m.get("toolCalls"));

        // ⭐ 刻意不再采集 token 用量（claude 复核指出：流式调用下拿不到可靠 usage，
        //    而对外暴露恒 0 的字段比不显示更误导 —— 读的人会以为"没花 token"）。
        //    这里断言那两个字段**确实不存在**，防止将来又被加回来变成假指标。
        assertFalse(m.containsKey("tokensInput"),
                "不应再对外暴露恒 0 的 tokensInput（假指标比没有数据更误导）");
        assertFalse(m.containsKey("tokensOutput"), "同上");
    }

    @Test
    @DisplayName("汇总是一行可读文本，且含关键字段（便于日志里直接看）")
    void summaryIsReadable() {
        CsUsageMeter meter = new CsUsageMeter(1000);
        meter.recordRequest();
        meter.recordDenied(CsRateLimiter.DenyKind.RATE);
        meter.recordToolCalls(2);

        String line = meter.summary();
        assertTrue(line.contains("请求=1"), "实际=" + line);
        assertTrue(line.contains("被限流=1"), "实际=" + line);
        assertTrue(line.contains("工具调用=2"), "实际=" + line);
    }

    @Test
    @DisplayName("summaryMap 不可变：调用方改不到内部状态")
    void summaryMapIsImmutable() {
        CsUsageMeter meter = new CsUsageMeter(1000);
        meter.recordRequest();

        Map<String, Long> snapshot = meter.summaryMap();
        try {
            snapshot.put("requests", 999L);
            assertTrue(false, "应当抛出不可修改异常");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }
        assertEquals(1L, meter.summaryMap().get("requests"), "内部计数不应被外部改动影响");
    }

    @Test
    @DisplayName("汇报频率配置：每 N 次打一条，不会因为取模而异常")
    void reportEveryDoesNotBlowUp() {
        CsUsageMeter meter = new CsUsageMeter(3);      // 每 3 次汇报一次
        for (int i = 1; i <= 10; i++) {
            meter.recordRequest();                    // 走到第 3/6/9 次时触发汇总日志
        }
        assertEquals(10L, meter.summaryMap().get("requests"));
    }

    @Test
    @DisplayName("report-every 配成 0 或负数会被兜成 1（不会除零）")
    void reportEveryIsClamped() {
        CsUsageMeter meter = new CsUsageMeter(0);
        meter.recordRequest();
        meter.recordRequest();
        assertEquals(2L, meter.summaryMap().get("requests"));
    }
}
