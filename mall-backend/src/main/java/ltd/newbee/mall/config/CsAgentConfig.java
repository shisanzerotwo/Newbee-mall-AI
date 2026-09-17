package ltd.newbee.mall.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI 客服的 LangChain4j 装配（M2-1）。
 *
 * <p>只负责把「模型通道」装配成一个 {@link ChatModel} Bean，不做任何业务编排。
 * 编排（工具循环、质检、SSE）在后续模块里实现。
 *
 * <p><b>为什么不用官方 starter</b>：{@code langchain4j-spring-boot-starter} 目前只有
 * beta 版（1.20.0-beta30），而 core 与 open-ai 是稳定版（1.20.0）。M2-1 先用
 * 稳定坐标 + 显式装配，避免 beta 自动配置的黑盒；RAG 需要 embeddings /
 * community-redis 时（M2-3）再评估是否引入 beta 组件。
 *
 * <p><b>模型通道说明</b>：本项目当前网络下，Agnes AI Hub 配额已耗尽、OmniRoute
 * 网关未运行，因此本 Bean 构建时**不会**访问网络（只是装配对象）；
 * 真正的连通性由 M2-4 前的 function calling spike 验证。
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

    @Bean
    public ChatModel csChatModel() {
        // 显式告警：“未配置密钥”不应静默到 M2-2 首次调用时才在网关侧报 401。
        // （同一模式在 M1 已踩过三次：DB 失联页面 200、尾斜杠错误页 200、compile 静默产出旧字节码）
        if (PLACEHOLDER_API_KEY.equals(apiKey)) {
            log.warn("CS_MODEL_API_KEY 未配置（当前为占位值 {}）—— 应用能启动，但任何模型调用都会失败。"
                    + "本机开发请用环境变量注入（WSL 下经 ops/mvn.sh 的 WSLENV 转发）。", PLACEHOLDER_API_KEY);
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
