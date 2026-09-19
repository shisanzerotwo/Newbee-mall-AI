package ltd.newbee.mall.controller.mall;

import ltd.newbee.mall.service.agent.CsRateLimiter;
import ltd.newbee.mall.service.agent.CsStreamService;
import ltd.newbee.mall.service.agent.CsUsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客服健康检查（P2 收敛后）。
 *
 * <h3>为什么单独一个类</h3>
 * 这个端点是**运维视角**的只读探针，与 {@link CsController} 的问答链路不共享状态，
 * 但依赖面完全不同（它要探 DB / Redis 可达性）。拆开后问答那个类不必被迫依赖
 * {@link DataSource}，改动面也小。
 *
 * <h3>R9：默认最小化（收敛 ≠ 删掉）</h3>
 * 原实现把 {@code qaMode / sseTimeoutMs / limits / usage} 一并挂在**匿名可达**的 GET 上。
 * 现在：
 * <ul>
 *   <li><b>默认只回健康必需项</b>：整体状态 + DB / Redis 可达性（这两项以前根本没有，
 *       {@code status} 是写死的 {@code "UP"} —— 那样的健康检查没有信息量）；</li>
 *   <li>用量计数、限流配置、质检模式等**内部信息**，只在
 *       {@code cs.health.detail-enabled=true} 时附加。开关打开后原来字段一个不少（本地排障用）。</li>
 * </ul>
 *
 * <h3>口径</h3>
 * 任一依赖不可达 → {@code status=DEGRADED}，**仍返回 200**（监控只解析响应体即可；
 * 将来若接 K8s 探针再改成 503）。
 *
 * <p>⚠️ 已知代价：DB 探测走 {@code DataSource.getConnection()}，数据库不可达时最坏会等
 * 连接池的 {@code connectionTimeout}（Hikari 默认 30s）才返回。健康检查调用频率低，暂不引入超时线程。
 */
@Controller
@RequestMapping("/api/cs")
public class CsHealthController {

    private static final Logger log = LoggerFactory.getLogger(CsHealthController.class);

    private final DataSource dataSource;

    private final StringRedisTemplate redisTemplate;

    private final CsStreamService csStreamService;

    private final CsUsageMeter usageMeter;

    private final CsRateLimiter rateLimiter;

    private final long emitterTimeoutMs;

    private final int maxQuestionChars;

    /** 内部信息开关：默认关。打开后 {@code /api/cs/health} 恢复成完整版。 */
    private final boolean detailEnabled;

    public CsHealthController(DataSource dataSource,
                              StringRedisTemplate redisTemplate,
                              CsStreamService csStreamService,
                              CsUsageMeter usageMeter,
                              CsRateLimiter rateLimiter,
                              @Value("${cs.stream.emitter-timeout-ms:120000}") long emitterTimeoutMs,
                              @Value("${cs.limit.max-question-chars:500}") int maxQuestionChars,
                              @Value("${cs.health.detail-enabled:false}") boolean detailEnabled) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.csStreamService = csStreamService;
        this.usageMeter = usageMeter;
        this.rateLimiter = rateLimiter;
        this.emitterTimeoutMs = emitterTimeoutMs;
        this.maxQuestionChars = maxQuestionChars;
        this.detailEnabled = detailEnabled;
    }

    /**
     * 健康检查：默认只回「整体状态 + DB / Redis 可达性」。
     *
     * <p>存在的意义：SSE 接口不便用普通 curl 一眼看出配置是否生效（要读事件流），
     * 这个端点让「服务与依赖到底活着没有」可以一步验证。不含任何密钥。
     */
    @GetMapping("/health")
    @ResponseBody
    public Map<String, Object> health() {
        boolean dbUp = probeDb();
        boolean redisUp = probeRedis();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp && redisUp ? "UP" : "DEGRADED");
        body.put("db", dbUp ? "UP" : "DOWN");
        body.put("redis", redisUp ? "UP" : "DOWN");

        if (detailEnabled) {
            body.put("qaMode", csStreamService.qaModeName());
            body.put("sseTimeoutMs", emitterTimeoutMs);
            Map<String, Object> limits = new LinkedHashMap<>();
            limits.put("requestsPerMinute", rateLimiter.capacityPerMinute());
            limits.put("maxQuestionChars", maxQuestionChars);
            body.put("limits", limits);
            body.put("usage", usageMeter.summaryMap());
        }
        return body;
    }

    /** DB 可达性：拿到连接即视为可达（不跑 SQL，避免又依赖表结构）。 */
    private boolean probeDb() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(1);
        } catch (Exception e) {
            // 用 debug：健康检查会被监控高频轮询，DB 挂时用 WARN 会刷爆日志
            log.debug("健康检查：DB 不可达 —— {}", e.toString());
            return false;
        }
    }

    /** Redis 可达性：发一次 PING。 */
    private boolean probeRedis() {
        try (RedisConnection conn = redisTemplate.getConnectionFactory().getConnection()) {
            return "PONG".equalsIgnoreCase(conn.ping());
        } catch (Exception e) {
            log.debug("健康检查：Redis 不可达 —— {}", e.toString());
            return false;
        }
    }
}
