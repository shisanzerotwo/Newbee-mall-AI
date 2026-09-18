package ltd.newbee.mall.controller.mall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-B 静态守卫测试：浮窗（全站原生入口）+ 上下文入口。
 *
 * <p>这些断言都是**源码级**的（读 classpath 里的模板与 JS 原文）。它们证明不了浏览器真实行为，
 * 但能挡住三类回归：
 * <ol>
 *   <li>红线被绕过（iframe、裸 innerHTML、新写一份转义）；</li>
 *   <li>把全站共用的 footer 改坏（影响所有页面）；</li>
 *   <li>重蹈 M3-A 的坑（流式文本与商品卡片同层 → 卡片被抹掉）。</li>
 * </ol>
 *
 * <p>真机行为由编排者统一验证（截图 + 冒烟），与本文互补。
 */
class CsWidgetGuardTest {

    private static String readClasspath(String path) throws IOException {
        try (InputStream in = CsWidgetGuardTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "类路径上找不到 " + path + "（打包/资源目录是否正确？）");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // footer.html：只追加，不得改坏（全站共用模板）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("footer 既有结构与文案必须原样保留（它是全站共用模板）")
    void footerKeepsExistingElements() throws IOException {
        String html = readClasspath("/templates/mall/footer.html");

        assertTrue(html.contains("th:fragment=\"footer-fragment\""),
                "footer 片段名不能改（所有页面都按这个名字 th:replace）");
        assertTrue(html.contains("site-footer"), "既有根类名 site-footer 必须保留");
        assertTrue(html.contains("footer-links"), "既有 footer-links 区块必须保留");
        assertTrue(html.contains("footer-info"), "既有 footer-info 区块必须保留");
        assertTrue(html.contains("796794009"), "既有版权/群号文案必须保留");
        assertTrue(html.contains("新蜂商城"), "既有友情链接文案必须保留");
    }

    @Test
    @DisplayName("footer 注入了浮窗资源，且是追加在既有结构之后")
    void footerInjectsWidgetAssets() throws IOException {
        // ⚠️ 分工说明（claude 复核建议写清，防止后人只跑这一份就误判）：
        //    本用例是**静态断言**，只能证明「footer.html 文件里有这三行」。
        //    它**证明不了**「渲染时会带上」—— Thymeleaf 的 th:replace="mall/footer::footer-fragment"
        //    只提取 fragment 范围内的节点，把 <link>/<script> 写在 fragment 外会被**静默丢弃**
        //   （M3-B 真机踩过：浮窗在所有页面都不出现，而这份静态断言全绿）。
        //    「注入是否真的到达页面」由 **CsWidgetInjectionTest** 以真实 HTTP 端到端负责，两者互补。
        String html = readClasspath("/templates/mall/footer.html");

        assertTrue(html.contains("cs-core.js"), "必须引入共用核心 cs-core.js");
        assertTrue(html.contains("cs-widget.js"), "必须引入浮窗脚本 cs-widget.js");
        assertTrue(html.contains("cs-widget.css"), "必须引入浮窗样式 cs-widget.css");

        // 追加而非插入：浮窗资源应在既有 footer 结构之后
        assertTrue(html.indexOf("site-footer") < html.indexOf("cs-widget.js"),
                "浮窗脚本应追加在既有 footer 结构之后（避免改动既有布局）");
    }

    // ------------------------------------------------------------------
    // 上下文入口（DESIGN §4.3）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("商品详情页有「问客服」入口且带 goodsId（走链接与 dataset，无注入面）")
    void detailPageHasAskCsEntry() throws IOException {
        String html = readClasspath("/templates/mall/detail.html");

        assertTrue(html.contains("问客服"), "详情页必须有「问客服」入口");
        assertTrue(html.contains("/cs(goodsId="), "入口必须带 goodsId 跳到 /cs");
        assertTrue(html.contains("id=\"cs-context\""), "必须有浮窗的上下文容器 cs-context");
        assertTrue(html.contains("data-goods-id="), "上下文必须走 dataset（Thymeleaf 会转义属性值）");
    }

    @Test
    @DisplayName("订单详情页有「问客服」入口且带 orderNo")
    void orderDetailPageHasAskCsEntry() throws IOException {
        String html = readClasspath("/templates/mall/order-detail.html");

        assertTrue(html.contains("问客服"), "订单页必须有「问客服」入口");
        assertTrue(html.contains("/cs(orderNo="), "入口必须带 orderNo 跳到 /cs");
        assertTrue(html.contains("id=\"cs-context\""), "必须有浮窗的上下文容器 cs-context");
        assertTrue(html.contains("data-order-no="), "上下文必须走 dataset");
    }

    // ------------------------------------------------------------------
    // 浮窗 JS：红线守卫
    // ------------------------------------------------------------------

    @Test
    @DisplayName("浮窗不得使用 iframe（本项目就是要取代旧 iframe 方案）")
    void widgetNeverUsesIframe() throws IOException {
        String js = readClasspath("/static/mall/js/cs-widget.js");

        assertFalse(js.contains("<iframe"), "浮窗不得引入 iframe");
        assertFalse(js.contains("createElement('iframe'"), "浮窗不得创建 iframe");
        assertFalse(js.contains("createElement(\"iframe\""), "浮窗不得创建 iframe");
    }

    @Test
    @DisplayName("浮窗 XSS 守卫：不拼 HTML、复用核心的转义，不新写一份")
    void widgetJsIsXssSafe() throws IOException {
        String js = readClasspath("/static/mall/js/cs-widget.js");

        assertFalse(js.contains(".innerHTML"), "浮窗不得使用 innerHTML（模型输出绝不能拼 HTML）");
        assertFalse(js.contains("insertAdjacentHTML"), "浮窗不得使用 insertAdjacentHTML");
        assertFalse(js.contains("document.write"), "浮窗不得使用 document.write");
        assertFalse(js.contains("th:utext"), "浮窗不得使用 th:utext");

        assertTrue(js.contains("textContent"), "文本必须经 textContent 写入");
        assertTrue(js.contains("createElement"), "节点必须用 createElement 构造");

        // 复用而非复制：不得自己再实现一份转义函数
        assertFalse(js.contains("function escapeHtml"),
                "不得在浮窗里另写一份 escapeHtml —— 必须复用 cs-core.js 的（否则两份实现会漂移）");
        assertTrue(js.contains("Cs."), "必须通过 Cs.* 复用共用核心");
    }

    @Test
    @DisplayName("浮窗不得自动打开（只在用户点击后展开）")
    void widgetDoesNotAutoOpen() throws IOException {
        String js = readClasspath("/static/mall/js/cs-widget.js");

        assertTrue(js.contains("hidden = true"),
                "面板初始必须 hidden（不自动打开，避免干扰商品浏览）");
    }

    @Test
    @DisplayName("流式文本必须写独立子容器，不得与商品卡片同层（M3-A 踩过的真 bug）")
    void deltaTextGoesIntoOwnContainer() throws IOException {
        String js = readClasspath("/static/mall/js/cs-widget.js");

        // 同 /cs 页的教训：textContent 的 setter 会清空子节点，把 onTool 挂上的卡片抹掉
        assertTrue(js.contains("botText"), "必须为流式文本建独立子节点");
        assertTrue(js.contains("botText.textContent"), "onDelta / onFinish 应写入 botText.textContent");
        assertFalse(java.util.regex.Pattern
                        .compile("bot\\.textContent\\s*(=|[+])=?")
                        .matcher(js).find(),
                "不得给 bot.textContent 赋值 —— 会清空子节点、抹掉商品卡片元素");
    }

    @Test
    @DisplayName("商品卡片只来自 tool 事件（不从模型文本解析 —— 防幻觉，DESIGN §8.4）")
    void goodsCardComesFromToolEvent() throws IOException {
        String js = readClasspath("/static/mall/js/cs-widget.js");

        assertTrue(js.contains("Cs.renderGoodsCard"), "卡片必须用核心的 renderGoodsCard 渲染");
        // 卡片只能在 onTool 回调里挂载
        int onToolAt = js.indexOf("onTool");
        int cardAt = js.indexOf("Cs.renderGoodsCard");
        assertTrue(onToolAt >= 0 && cardAt >= 0 && onToolAt < cardAt,
                "renderGoodsCard 必须出现在 onTool 回调内（只信工具事件，不信模型措辞）");
    }

    @Test
    @DisplayName("/cs 完整页不得再叠加浮窗（避免双份入口与两份并发会话）")
    void widgetSkipsFullPage() throws IOException {
        String js = readClasspath("/static/mall/js/cs-widget.js");

        assertTrue(js.contains("/cs"), "必须有 /cs 路径判断");
        assertTrue(js.contains("return;"), "在 /cs 页应直接返回、不初始化浮窗");
    }

    @Test
    @DisplayName("浮窗样式必须复用商城 CSS 变量（不另起色板，才能跟主题换肤）")
    void widgetCssReusesMallVariables() throws IOException {
        String css = readClasspath("/static/mall/css/cs-widget.css");

        assertTrue(css.contains("var(--brand-500"), "主色必须取商城变量 --brand-500");
        assertTrue(css.contains("var(--bg-card") || css.contains("var(--bg-page"),
                "背景必须取商城变量，而不是写死的色值");
        assertTrue(css.contains("380px") && css.contains("560px"),
                "浮窗尺寸应为 380x560（DESIGN §8.1）");
        assertTrue(css.contains(".cs-fab"), "必须有右下角气泡按钮样式");
        assertTrue(css.contains(".cs-float"), "必须有浮窗面板样式");
    }
}
