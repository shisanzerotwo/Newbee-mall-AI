package ltd.newbee.mall.config;

import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 客服的 LangChain4j 装配（M2-1 / M2-5）。
 *
 * <p>只负责把「模型通道」装配成两个 Bean，不做任何业务编排：
 * <ul>
 *   <li>{@code csChatModel}（{@link ChatModel}）—— 非流式：工具循环、质检</li>
 *   <li>{@code csStreamingChatModel}（{@link StreamingChatModel}）—— 流式：M2-5 的 SSE 逐字推送</li>
 * </ul>
 * 编排（工具循环、质检、SSE）在 service/controller 层实现。
 *
 * <p><b>为什么不用官方 starter</b>：{@code langchain4j-spring-boot-starter} 目前只有
 * beta 版（1.20.0-beta30），而 core 与 open-ai 是稳定版（1.20.0）。M2-1 先用
 * 稳定坐标 + 显式装配，避免 beta 自动配置的黑盒；RAG 需要 embeddings /
 * community-redis 时（M2-3）再评估是否引入 beta 组件。
 *
 * <p><b>模型通道说明</b>：本 Bean 构建时**不会**访问网络（只是装配对象）；
 * 真正的连通性由 M2-4 的 function calling 实测验证（已跑通，见 docs/STATUS.md §7）。
 */
@Configuration
public class CsAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(CsAgentConfig.class);

    /** 未配置密钥时的占位值（仅用于不阻断启动，不代表可用） */
    private static final String PLACEHOLDER_API_KEY = "not-configured";

    @Value("${cs.model.base-url}")
    private String baseUrl;

    /**
     * 密钥走环境变量。
     *
     * <p>默认给占位值只是「不阻断启动」，<b>并非 builder 校验需要</b> —— 实测
     * {@code OpenAiChatModel.builder()} 对 apiKey / modelName <b>完全不校验</b>
     * （不设、空串、占位串均能构建成功）。所以占位值挡不住任何构建期异常，
     * 它只是把「未配置」推迟到运行时才暴露。
     *
     * <p>因此 {@link #csChatModel()} 里加了显式启动告警，避免这种静默。
     */
    @Value("${cs.model.api-key:not-configured}")
    private String apiKey;

    @Value("${cs.model.name}")
    private String modelName;

    @Value("${cs.model.temperature:0.2}")
    private Double temperature;

    @Value("${cs.model.timeout-seconds:60}")
    private long timeoutSeconds;

    /** 保证「未配置密钥」的告警只打一次（两个 Bean 都会调 {@link #warnIfApiKeyMissing()}） */
    private final AtomicBoolean apiKeyWarned = new AtomicBoolean(false);

    /**
     * 「未配置密钥」不应静默到首次调用时才在网关侧报 401。
     *
     * <p>（同一模式在 M1 已踩过三次：DB 失联页面 200、尾斜杠错误页 200、compile 静默产出旧字节码）
     *
     * <p>注意：判断必须覆盖【未设置】与【空串】两种情形 —— 环境变量设为空串时
     * {@code ${cs.model.api-key:not-configured}} 会得到 {@code ""} 而非占位串（实测确认）。
     */
    private void warnIfApiKeyMissing() {
        if (apiKey != null && !apiKey.isBlank() && !PLACEHOLDER_API_KEY.equals(apiKey)) {
            return;
        }
        if (apiKeyWarned.compareAndSet(false, true)) {
            log.warn("CS_MODEL_API_KEY 未配置或为空（当前值 {}）—— 应用能启动，但任何模型调用都会失败。"
                    + "本机开发请用环境变量注入（WSL 下经 ops/mvn.sh 的 WSLENV 转发）。",
                    apiKey == null ? "(null)" : "[" + apiKey + "]");
        }
    }

    @Bean
    public ChatModel csChatModel() {
        warnIfApiKeyMissing();
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // ⚠️ 必须显式指定 OkHttp：LangChain4j 默认的 JDK HttpClient 调 OmniRoute 网关会报
                //   java.io.IOException: HTTP/1.1 header parser received no bytes
                // （同参数同 key 用 curl 正常；JdkHttpClient.Builder 无可配项，只能换客户端）
                .httpClientBuilder(new OkHttpClientBuilder())
                // 关闭 LangChain4j 的「内置重试」（HTTP 层）：Builder 的 maxRetries 默认是 2
                //   （javap 反编译构造器实测：Utils.getOrDefault(builder.maxRetries, 2)
                //     -> 单次调用最多 3 个 HTTP 请求），且是**指数退避、不可控**的。
                //   本项目的模型通道（OmniRoute -> agnes 免费额度）会高频 429（reset after 3s），
                //   我们自实现了更贴合的线性退避（cs.agent.max-model-retries，见 CsAgentService）。
                //   这里设为 0 把内置那层关掉，避免两者**叠加**（最坏 3 x 3 = 9 个请求）。
                //   行为证据：OpenAiRetryBehaviorTest（实测请求数 默认 3 / maxRetries(0) 1 / maxRetries(1) 2）。
                //   ⚠️ 历史教训：初版注释曾断言「Builder 没有 maxRetries」——那是错的，
                //     根因是 javap 过滤正则写成 retry|Retry 而方法名是 maxRetries（含 Retries）。
                .maxRetries(0)
                .build();
    }

    /**
     * 流式模型（M2-5）—— 供 {@code /api/cs/chat} 逐字推送。
     *
     * <p>与 {@link #csChatModel()} 同一套参数（baseUrl / key / model / 温度 / 超时 / OkHttp），
     * 只差「流式」与「非流式」。
     *
     * <p>⚠️ 这里<b>没有</b> {@code maxRetries}：{@code OpenAiStreamingChatModel} 的 Builder 上
     * <b>不存在</b>该方法，流式模型<b>也不做内置重试</b>（javap -c 全字节码中含 {@code etry} 的行数：
     * 流式 = <b>0</b>，非流式 = 2）。所以流式路径的重试只能自己实现，见 {@code CsStreamService}；
     * 也正因为没有内置重试，此处不存在「两层重试叠加」的问题。
     */
    @Bean
    public StreamingChatModel csStreamingChatModel() {
        warnIfApiKeyMissing();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // 同 csChatModel：LangChain4j 默认 JDK HttpClient 调 OmniRoute 会报
                //   java.io.IOException: HTTP/1.1 header parser received no bytes
                .httpClientBuilder(new OkHttpClientBuilder())
                .build();
    }
}
