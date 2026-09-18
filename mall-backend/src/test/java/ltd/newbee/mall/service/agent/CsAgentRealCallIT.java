package ltd.newbee.mall.service.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * **真实模型**端到端调用（不用 Mock）。
 *
 * <p>存在理由：Mock 只能验证编排逻辑，<b>看不见真实报文</b>。而 OpenAI 协议要求
 * 「{@code tool} 消息必须紧跟其对应的 {@code assistant(tool_calls)}」——
 * 这类缺陷只有真机调用才会以 HTTP 400 暴露。本测试专门守住它。
 *
 * <p>命名以 {@code IT} 结尾（Surefire 默认只跑 {@code *Test}），因此
 * <b>不会</b>被 {@code mvn test} 全量跑到、也不会拖累 CI —— 需要真实模型时手动跑：
 * <pre>
 *   export DB_PASSWORD=... &amp;&amp; bash ops/mvn.sh test -Dtest=CsAgentRealCallIT
 * </pre>
 *
 * <p>前置：OmniRoute 网关在跑（{@code http://localhost:20128/v1}），模型 id 为
 * {@code agnes/agnes-2.0-flash}（见 {@code .env} / {@code docs/STATUS.md}）。
 * 免费额度会 429，{@code CsAgentService} 自带重试（退避 1s×n）。
 */
@SpringBootTest
class CsAgentRealCallIT {

    @Resource
    private CsAgentService csAgentService;

    @Test
    void realModelShouldAnswerAndUseTools() {
        // 该问题必然要查库存 -> 触发工具循环 -> 走到第二轮请求（正是 P1 修复的验证点）
        CsAgentService.CsAnswer ans = csAgentService.answer("无印良品的化妆水有货吗？");

        System.out.println("===== 真实调用结果 =====");
        System.out.println("回答: " + ans.answer());
        System.out.println("工具轨迹: " + ans.toolCalls());
        System.out.println("质检模式: " + ans.qaMode());
        System.out.println("=======================");

        assertNotNull(ans.answer(), "回答不应为 null");
        assertFalse(ans.answer().isBlank(), "真实模型应返回非空回答");
        assertFalse(ans.toolCalls().isEmpty(),
                "该问题应触发工具调用；若为空说明工具循环没能走到第二轮（P1 类缺陷）");
    }
}
