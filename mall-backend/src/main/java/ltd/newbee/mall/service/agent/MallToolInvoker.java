package ltd.newbee.mall.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * 工具分发（M2-5 从 {@code CsAgentService} 抽出）—— 把模型请求的工具名 + JSON 参数
 * 落到 {@link MallTools} 的具体方法上。
 *
 * <h3>为什么要抽出来</h3>
 * M2-5 的流式编排（{@link CsStreamService}）需要与 M2-4 的非流式编排
 * （{@link CsAgentService}）**执行同一套工具**。若各自维护一份 switch，
 * 两张表迟早会漂移（漏工具、默认值不一致）—— 所以分发只有这一处。
 *
 * <h3>为什么不注册成 Spring Bean</h3>
 * 它是**无状态的薄封装**（只持有 {@link MallTools} + 一个 ObjectMapper）。
 * 让两个编排类各自 {@code new} 一个，好处是 {@code CsAgentService} 的构造器签名
 * 保持不变（M2-4 的测试与调用方无需改动）。
 *
 * <h3>为什么不抛异常</h3>
 * 工具失败**不中断问答**：把错误文本<b>回传给模型</b>，让模型有机会改用正确的工具名或
 * 换一种查法；同时打 WARN 留痕（这是有日志的降级，不是静默吞掉）。
 * {@link ToolInvocation#ok()} 把「成功与否」显式带出来，供 SSE 的 {@code tool} 事件
 * 上报 —— 不去猜结果文本的前缀（那样一改文案就静默失效）。
 */
public class MallToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(MallToolInvoker.class);

    private static final TypeReference<Map<String, Object>> ARGS_TYPE = new TypeReference<>() {
    };

    /** 已知工具名（用于判定 {@link ToolInvocation#ok()}，不靠猜结果文案） */
    private static final Set<String> KNOWN_TOOLS = Set.of(
            "searchGoods", "getGoodsDetail", "checkStock", "queryOrder", "searchByCategory", "recommendGoods");

    private final MallTools mallTools;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public MallToolInvoker(MallTools mallTools) {
        this.mallTools = mallTools;
    }

    /** 一次工具调用的结果。{@code ok=false} 表示参数解析失败 / 执行失败 / 工具名未知 */
    public record ToolInvocation(String result, boolean ok) {
    }

    /**
     * 按工具名分发到 {@link MallTools}。
     *
     * <p>用显式 switch 而不是反射：工具集小且稳定，显式分发类型安全、默认值一目了然，
     * 也不必处理反射异常。未知工具名会<b>回传给模型</b>并打 WARN（而不是静默丢弃），
     * 让模型有机会改用正确的工具名。
     */
    public ToolInvocation invoke(String name, String argumentsJson) {
        Map<String, Object> args;
        try {
            args = (argumentsJson == null || argumentsJson.isBlank())
                    ? Map.of()
                    : objectMapper.readValue(argumentsJson, ARGS_TYPE);
        } catch (Exception e) {
            log.warn("工具 {} 的参数解析失败（已把错误回传给模型）：{}", name, e.toString());
            return new ToolInvocation("工具参数解析失败：" + e.getMessage(), false);
        }

        try {
            String result = switch (name) {
                case "searchGoods" -> mallTools.searchGoods(str(args, "keyword"), intArg(args, "limit", 5));
                case "getGoodsDetail" -> mallTools.getGoodsDetail(intArg(args, "goodsId", 0));
                case "checkStock" -> mallTools.checkStock(intArg(args, "goodsId", 0));
                case "queryOrder" -> mallTools.queryOrder(str(args, "orderNo"));
                case "searchByCategory" -> mallTools.searchByCategory(
                        str(args, "categoryName"), intArg(args, "limit", 5));
                case "recommendGoods" -> mallTools.recommendGoods(
                        str(args, "keyword"), strOr(args, "sort", "default"), intArg(args, "limit", 3));
                default -> {
                    log.warn("模型请求了未知工具：{}（已回传可用工具清单）", name);
                    yield "未知工具：" + name + "。可用工具：searchGoods、getGoodsDetail、checkStock、"
                            + "queryOrder、searchByCategory、recommendGoods。";
                }
            };
            // 未知工具走的是 default 分支，上面已 yield 了错误文案；这里用白名单判定 ok，
            // 避免"看文案猜成功"。
            return new ToolInvocation(result, KNOWN_TOOLS.contains(name));
        } catch (Exception e) {
            log.warn("工具 {} 执行失败（已把错误回传给模型）：{}", name, e.toString());
            return new ToolInvocation("工具执行失败：" + e.getMessage(), false);
        }
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOr(Map<String, Object> args, String key, String fallback) {
        String value = str(args, key);
        return value.isBlank() ? fallback : value;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
