package ltd.newbee.mall.controller.mall;

import jakarta.servlet.http.HttpServletRequest;
import ltd.newbee.mall.service.agent.AiAssistService;
import ltd.newbee.mall.service.agent.CsRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 发布辅助 AI（校园二手平台 M3）：标题/描述生成与估价。
 *
 * <p>复用客服模块的 {@code csChatModel} 通道（{@link AiAssistService}），
 * 与 {@code /api/cs/chat} 相同的防护思路：先限流再校验入参；
 * 但此处是普通 REST（非流式），失败语义用状态码表达（429 限流 / 503 模型不可用）。</p>
 *
 * <p>限流键用客户端 IP（辅助操作低频、无会话概念），调用后必须 finally release。</p>
 */
@RestController
@RequestMapping("/api/ai")
public class AiAssistController {

    private static final Logger log = LoggerFactory.getLogger(AiAssistController.class);

    private final AiAssistService aiAssistService;
    /** 单 IP 维度限流：容量大于客服（辅助操作低频但也会连点） */
    private final CsRateLimiter ipLimiter;

    public AiAssistController(AiAssistService aiAssistService,
                              @Value("${ai.assist.ip-requests-per-minute:30}") long ipRequestsPerMinute) {
        this.aiAssistService = aiAssistService;
        this.ipLimiter = new CsRateLimiter(Math.max(1, ipRequestsPerMinute), 1);
    }

    /**
     * 生成标题 + 描述。
     * 响应：200 {"resultCode":200,"message":"SUCCESS","data":{"title":"...","desc":"..."}}
     */
    @PostMapping("/title")
    public ResponseEntity<Map<String, Object>> title(@RequestBody AssistRequest body, HttpServletRequest request) {
        String key = "ip:" + clientIp(request);
        CsRateLimiter.Decision decision = ipLimiter.tryAcquire(key);
        if (!decision.allowed()) {
            return rateLimited(decision.message());
        }
        try {
            String error = validate(body);
            if (error != null) {
                return ResponseEntity.badRequest().body(bodyOf(400, error));
            }
            AiAssistService.TitleResult r = aiAssistService.generateTitle(body.category(), body.condition(), body.title());
            return ResponseEntity.ok(ok(Map.of("title", r.title(), "desc", r.desc())));
        } catch (RuntimeException e) {
            log.warn("AI 标题生成失败：{}", e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(bodyOf(503, "AI 服务暂时不可用，请稍后再试"));
        } finally {
            ipLimiter.release(key);
        }
    }

    /**
     * 估价区间。
     * 响应：200 {"resultCode":200,"message":"SUCCESS","data":{"min":180,"max":260,"reason":"..."}}
     */
    @PostMapping("/estimate")
    public ResponseEntity<Map<String, Object>> estimate(@RequestBody AssistRequest body, HttpServletRequest request) {
        String key = "ip:" + clientIp(request);
        CsRateLimiter.Decision decision = ipLimiter.tryAcquire(key);
        if (!decision.allowed()) {
            return rateLimited(decision.message());
        }
        try {
            String error = validate(body);
            if (error != null) {
                return ResponseEntity.badRequest().body(bodyOf(400, error));
            }
            AiAssistService.EstimateResult r = aiAssistService.estimate(body.category(), body.condition(), body.title());
            return ResponseEntity.ok(ok(Map.of(
                    "min", r.min(), "max", r.max(),
                    "reason", r.reason() == null || r.reason().isBlank() ? "" : r.reason())));
        } catch (RuntimeException e) {
            log.warn("AI 估价失败：{}", e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(bodyOf(503, "AI 服务暂时不可用，请稍后再试"));
        } finally {
            ipLimiter.release(key);
        }
    }

    /** 入参校验：允许 category/condition/title 任一非空即可（生成任务信息越少输出越通用） */
    private String validate(AssistRequest body) {
        if (body == null) return "请求体不能为空";
        boolean anyPresent = !isBlank(body.category()) || !isBlank(body.condition()) || !isBlank(body.title());
        return anyPresent ? null : "请至少提供品类、成色或标题中的一项";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> ok(Object data) {
        return Map.of("resultCode", 200, "message", "SUCCESS", "data", data);
    }

    private static Map<String, Object> bodyOf(int code, String message) {
        return Map.of("resultCode", code, "message", message, "data", Map.of());
    }

    private static ResponseEntity<Map<String, Object>> rateLimited(String message) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(bodyOf(429, message == null ? "操作太频繁，请稍后再试" : message));
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            int idx = ip.indexOf(',');
            return (idx > 0 ? ip.substring(0, idx) : ip).trim();
        }
        return request.getRemoteAddr();
    }

    public record AssistRequest(String category, String condition, String title) {
    }
}
