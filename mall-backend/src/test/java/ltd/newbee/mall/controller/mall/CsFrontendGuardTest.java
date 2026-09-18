package ltd.newbee.mall.controller.mall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * M3-A：前端输出编码的「守门」测试（DESIGN §8.4 的 DoD 要求：可 grep 核查）。
 *
 * <h3>为什么用静态检查而不是跑 JS</h3>
 * JDK 21 起没有内置 JS 引擎（Nashorn 已在 15 移除），真跑 JS 得引入 GraalVM JS 之类的依赖 ——
 * 为一条转义断言引入一个 JS 运行时并不划算。于是把可核查的性质写成断言：
 * <b>① 转义表必须覆盖 5 个字符；② 这两个文件里不得出现 HTML 字符串拼接入口</b>。
 * 后者是本文件的重点：只要<b>根本不拼 HTML</b>，就<b>不存在</b>「忘了转义」这种 bug
 * （这也是 cs-core.js 全程只用 createElement + textContent 的原因）。
 *
 * <p>注意断言的是<b>代码</b>而非注释：{@code innerHTML} 这个词在注释里出现是允许的，
 * 因此匹配的是带调用后缀的形态（{@code .innerHTML =} / {@code innerHTML +=}），
 * 注释里不会出现这种写法。
 */
class CsFrontendGuardTest {

    private static String readClasspath(String path) throws IOException {
        try (InputStream in = CsFrontendGuardTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "类路径上找不到 " + path + "（打包/资源目录是否正确？）");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("cs-core.js：escapeHtml 必须覆盖 & < > \" ' 五个字符（含单引号）")
    void escapeMapCoversAllFiveCharacters() throws IOException {
        String js = readClasspath("/static/mall/js/cs-core.js");

        assertTrue(js.contains("function escapeHtml"), "必须导出 escapeHtml 供所有渲染路径复用");

        // 五个字符的转义产物，缺一不可。单引号那条是刻意强调的：
        // Python 基准（web/index.html:219）的 esc() 只覆盖 & < > "，漏了 '，
        // 在属性语境下是真实缺口，本项目不继承。
        List<String> required = List.of("&amp;", "&lt;", "&gt;", "&quot;", "&#39;");
        for (String entity : required) {
            assertTrue(js.contains(entity),
                    "escapeHtml 缺少 " + entity + " —— 转义必须覆盖 & < > \" ' 五个字符");
        }

        // 转义必须是「扫一遍原文按表替换」，而不是几个 replace 链里漏掉某个字符
        assertTrue(js.contains("[&<>\"']"),
                "应有一个覆盖全部五个字符的字符类正则作为替换依据");
    }

    @Test
    @DisplayName("cs-core.js：不得有任何 HTML 字符串拼接入口（不拼 HTML 就不存在忘记转义）")
    void coreNeverAssignsInnerHtml() throws IOException {
        String js = readClasspath("/static/mall/js/cs-core.js");

        assertFalse(js.contains(".innerHTML ="), "cs-core.js 不得给 innerHTML 赋值");
        assertFalse(js.contains(".innerHTML="), "cs-core.js 不得给 innerHTML 赋值");
        assertFalse(js.contains("innerHTML +="), "cs-core.js 不得用 += 累积 HTML");
        assertFalse(js.contains("insertAdjacentHTML"), "cs-core.js 不得用 insertAdjacentHTML 注入");
        assertFalse(js.contains("document.write"), "cs-core.js 不得用 document.write");

        // 正向确认它确实走了安全的 DOM API（避免"因为文件是空的所以通过"这种假绿）
        assertTrue(js.contains("createElement"), "应使用 createElement 构造节点");
        assertTrue(js.contains("textContent"), "文本应通过 textContent 写入");
    }

    @Test
    @DisplayName("/cs 页面模板：不使用 HTML 注入（th:utext / innerHTML / iframe）")
    void pageTemplateAvoidsUnsafeRendering() throws IOException {
        String html = readClasspath("/templates/mall/cs.html");

        assertFalse(html.contains("th:utext"),
                "模板不得用 th:utext（不做转义输出）——用户可控的 orderNo 会被注入");
        assertFalse(html.contains(".innerHTML ="), "页面脚本不得给 innerHTML 赋值");
        assertFalse(html.contains(".innerHTML="), "页面脚本不得给 innerHTML 赋值");
        assertFalse(html.contains("<iframe"), "本项目正是为取代 iframe 方案，不得再引入");

        // 上下文必须经 th:text / dataset 进入页面（自动转义），而不是内联 JS 变量
        assertTrue(html.contains("th:text=\"${orderNo}\""),
                "orderNo 必须走 th:text（自动转义）渲染");
        assertTrue(html.contains("th:attr="),
                "上下文应通过 data-* 属性传给前端（Thymeleaf 会转义属性值）");
    }

    @Test
    @DisplayName("商品卡片不会被流式文本抹掉：delta 必须写独立的 botText，不能写 bot.textContent")
    void deltaMustNotOverwriteMessageContainer() throws IOException {
        String html = readClasspath("/templates/mall/cs.html");

        // 回归（claude 审查发现的真 bug）：onDelta 曾写成 bot.textContent = bot.textContent + text，
        // 而 textContent 的 setter 会**移除该节点的全部子节点**，把 onTool 挂进来的商品卡片一并抹掉。
        // 服务端先发 tool、之后必有 delta，所以几乎必然触发（卡片刚出现就消失）。
        assertFalse(html.contains("bot.textContent = bot.textContent"),
                "onDelta 不得给 bot.textContent 赋值 —— textContent 的 setter 会清空子节点，"
                        + "把 onTool 挂进来的商品卡片元素抹掉（历史上真发生过）");
        // 正则拦全部赋值变体：= '' / = otherVar / += 都会清空或重写子节点
        // （claude 复核指出：只用 contains 拦不住这两个变体）
        // ⚠️ Java 字符串里写 \\. 才能得到正则的 \\.（字面点）；写成 \\\\. 会变成匹配"字面反斜杠"
        //    从而永不命中 —— 那会让本条断言恒真（假绿），必须用阴性对照验证
        assertFalse(java.util.regex.Pattern
                        .compile("bot\\.textContent\\s*(=|[+])=?")
                        .matcher(html).find(),
                "bot.textContent 的任何赋值/自增（= / +=）都会清空子节点、抹掉商品卡片元素");

        // 正面要求：流式文本必须有自己的容器，与商品卡片各占一个子节点
        assertTrue(html.contains("var botText"),
                "必须为流式文本创建独立子节点 botText（与商品卡片互不干扰）");
        assertTrue(html.contains("bot.appendChild(botText)"),
                "botText 必须挂到消息气泡下（否则文本不显示）");
        assertTrue(html.contains("botText.textContent"),
                "onDelta / onFinish 应写入 botText.textContent");
    }

    @Test
    @DisplayName("组件样式真的会被 /cs 加到（cs.css 必须 @import 共用的 cs-widget.css）")
    void pageStylesheetImportsTheSharedComponentCss() throws IOException {
        String css = readClasspath("/static/mall/styles/cs.css");

        // 背景：head 片段只按 path 加载 /mall/styles/<path>.css，且在模板里额外写的 <link>
        // 会因为 th:replace 整个 <head> 而被丢弃。所以两条样式表必须用 @import 串起来，
        // 否则 /cs 会静默变成无样式页面（静默失效正是本项目反复踩的坑）。
        assertTrue(css.contains("@import") && css.contains("/mall/css/cs-widget.css"),
                "cs.css 应 @import /mall/css/cs-widget.css，否则组件样式不会生效");

        // @import 必须在其他规则之前才有效
        assertTrue(css.indexOf("@import") < css.indexOf(".cs-page"),
                "@import 必须出现在规则之前（否则浏览器忽略它）");
    }
}
