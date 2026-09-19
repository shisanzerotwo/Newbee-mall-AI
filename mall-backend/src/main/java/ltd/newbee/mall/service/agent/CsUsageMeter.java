package ltd.newbee.mall.service.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客服用量记账（M3-C，DESIGN §7.2「记录用量以估算成本」）。
 *
 * <h3>为什么需要</h3>
 * {@code /api/cs/chat} 匿名可达，每次调用都要打上游模型（真金白银 / 免费额度）。
 * 被限流的次数尤其关键 —— 只看到 429 而不知道规模，没法判断是"偶发"还是"有人在刷"。
 *
 * <h3>口径</h3>
 * <ul>
 *   <li>{@code requests}：进入接口的请求总数（含被拒的）；</li>
 *   <li>{@code deniedRate} / {@code deniedInFlight}：两类拒绝各自的次数；</li>
 *   <li>{@code completed} / {@code failed}：走完流的次数（成功 / 出错兜底）；</li>
 *   <li>{@code toolCalls}：工具调用次数 —— 它是模型调用次数的下界代理
 *       （每一轮工具调用都会伴随一次模型调用），比单纯数请求更能反映成本；</li>
 *   <li>{@code tokensInput} / {@code tokensOutput}：仅在模型返回 usage 时累加，否则保持 0
 *       （不猜、不估算成假数据）。</li>
 * </ul>
 *
 * <p>每累加 {@code cs.limit.report-every}（默认 20）个请求就打一条汇总日志，
 * 便于"用量可查"而不必翻遍日志。
 */
@Component
public class CsUsageMeter {

    private static final Logger log = LoggerFactory.getLogger(CsUsageMeter.class);

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong deniedRate = new AtomicLong();
    private final AtomicLong deniedInFlight = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong tokensInput = new AtomicLong();
    private final AtomicLong tokensOutput = new AtomicLong();

    private final long reportEvery;

    public CsUsageMeter(@Value("${cs.limit.report-every:20}") long reportEvery) {
        this.reportEvery = Math.max(1, reportEvery);
    }

    /** 每个进入接口的请求都记一次（包含随后被拒的）。 */
    public void recordRequest() {
        long n = requests.incrementAndGet();
        if (n % reportEvery == 0) {
            log.info("客服用量汇总：{}", summary());
        }
    }

    public void recordDenied(CsRateLimiter.DenyKind kind) {
        if (kind == CsRateLimiter.DenyKind.RATE) {
            deniedRate.incrementAndGet();
        } else if (kind == CsRateLimiter.DenyKind.IN_FLIGHT) {
            deniedInFlight.incrementAndGet();
        }
    }

    public void recordCompleted() {
        completed.incrementAndGet();
    }

    public void recordFailed() {
        failed.incrementAndGet();
    }

    /** 一轮客服可能调用多次工具（每次工具都意味着一次模型调用），所以要按次数累加。 */
    public void recordToolCalls(int count) {
        if (count > 0) {
            toolCalls.addAndGet(count);
        }
    }

    /** 仅在模型确实返回 usage 时调用（拿不到就别猜）。 */
    public void recordTokens(long input, long output) {
        if (input > 0) {
            tokensInput.addAndGet(input);
        }
        if (output > 0) {
            tokensOutput.addAndGet(output);
        }
    }

    /** 快照（供日志与只读接口使用）。返回不可变 Map，避免调用方改到内部状态。 */
    public Map<String, Long> summaryMap() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("requests", requests.get());
        m.put("deniedRate", deniedRate.get());
        m.put("deniedInFlight", deniedInFlight.get());
        m.put("completed", completed.get());
        m.put("failed", failed.get());
        m.put("toolCalls", toolCalls.get());
        m.put("tokensInput", tokensInput.get());
        m.put("tokensOutput", tokensOutput.get());
        return Map.copyOf(m);
    }

    /** 一行可读汇总（日志用）。 */
    public String summary() {
        Map<String, Long> m = summaryMap();
        return "请求=" + m.get("requests")
                + " 完成=" + m.get("completed")
                + " 失败=" + m.get("failed")
                + " 被限流=" + (m.get("deniedRate") + m.get("deniedInFlight"))
                + "（频率 " + m.get("deniedRate") + " / 在途 " + m.get("deniedInFlight") + "）"
                + " 工具调用=" + m.get("toolCalls")
                + " token(in/out)=" + m.get("tokensInput") + "/" + m.get("tokensOutput");
    }
}
