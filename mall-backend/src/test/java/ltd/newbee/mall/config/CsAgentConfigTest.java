package ltd.newbee.mall.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2-1 验收测试：LangChain4j 的 {@link ChatModel} Bean 能被正确装配，**且配置真的绑上了**。
 *
 * <p><b>为什么用 {@code locations} 而不是 {@code properties}</b>：早先版本用
 * {@code @TestPropertySource(properties = ...)} 自带全部属性，与
 * {@code application.properties} 完全脱钩 —— 那样即使配置文件里的键名写错
 * （例如把 {@code cs.model.temperature} 写成 {@code cs.model.temperatur}），
 * {@code @Value} 会静默回落到默认值，测试照样绿。改用真实配置文件后，
 * 本测试验证的是**交付物**，而不是测试自带的输入。
 *
 * <p><b>为什么要读回绑定值</b>：只断言 {@code assertNotNull} 无法发现上面那种
 * 静默回落。这里通过 {@link OpenAiChatModel#defaultRequestParameters()} 读回
 * 实际生效的 modelName / temperature，才能证明键名与绑定都正确。
 *
 * <p>只加载 {@link CsAgentConfig}（不启完整 Spring Boot 上下文），因此不依赖
 * MySQL / Redis，也不需要模型真实可达 —— 本测试验证「装配与配置绑定」，
 * 模型连通性由 function calling spike 负责。
 *
 * <p>注意：断言 {@code auto} 依赖运行环境未设置 {@code CS_MODEL_NAME}
 * 环境变量（{@code application.properties} 里该项为 {@code ${CS_MODEL_NAME:auto}}）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CsAgentConfig.class)
@TestPropertySource(locations = "classpath:application.properties")
class CsAgentConfigTest {

    @Autowired
    private ChatModel csChatModel;

    @Test
    void chatModelBeanShouldBeCreated() {
        assertNotNull(csChatModel, "csChatModel Bean 应被创建（装配正确即可，不要求模型可达）");
    }

    @Test
    void propertiesShouldBeActuallyBound() {
        ChatRequestParameters params =
                ((OpenAiChatModel) csChatModel).defaultRequestParameters();

        assertEquals("auto", params.modelName(),
                "cs.model.name 应绑定为 ${CS_MODEL_NAME:auto} 的默认值 auto "
                        + "（若为 null 或其它值，说明键名写错或环境变量被设置）");
        assertEquals(0.2, params.temperature(),
                "cs.model.temperature 应绑定为 0.2（若静默回落到其它值，说明键名写错）");
    }
}
