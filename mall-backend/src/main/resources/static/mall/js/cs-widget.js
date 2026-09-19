/**
 * 客服浮窗（M3-B）：把客服做成**全站原生入口**（右下角），替代旧的 iframe 外挂方案。
 *
 * <h3>设计约束（DESIGN §8.1 / §8.4）</h3>
 * <ul>
 *   <li><b>不用 iframe</b>：直接操作本页 DOM。这是本项目取代旧方案的核心诉求。</li>
 *   <li><b>XSS 强制</b>：所有文本写入一律走 {@code textContent}（<b>这才是防线</b>）；本文件<b>不新写转义函数</b>，
 *       也<b>没有调用</b> {@code Cs.escapeHtml}。模型输出永不拼 HTML。</li>
 *   <li><b>文本与卡片不能同层</b>：{@code textContent} 的 setter 会清空子节点，
 *       所以流式文本写在独立的 {@code span}（{@code botText}）里，商品卡片另占一个子节点
 *       —— 否则 onTool 刚挂上的卡片会被 onDelta 抹掉（M3-A 踩过这个真 bug）。</li>
 *   <li><b>不自动打开</b>：只在用户点击气泡按钮后展开。</li>
 *   <li><b>上下文</b>：页面放一个 {@code #cs-context}（带 data-goods-id / data-order-no）
 *       即可把「当前咨询商品/订单」带进来，见 DESIGN §4.3。</li>
 * </ul>
 */
(function (global) {
    'use strict';

    var doc = global.document;
    var path = (global.location && global.location.pathname) || '';

    // /cs 完整页本身就是客服界面，不再叠加浮窗（避免双份入口与两份并发会话）
    if (path === '/cs' || path.indexOf('/cs/') === 0) {
        return;
    }
    // cs-core.js 未加载时静默退出，绝不让浮窗把整页 JS 拖崩
    if (!global.Cs) {
        return;
    }

    /** 3 条快捷 chips（DESIGN §8.1：浮窗含 3 条 chips） */
    var QUICK_QUESTIONS = ['这个有货吗？', '这个多少钱？', '怎么退货？'];

    /** 读取页面上的上下文（商品/订单），没有就是 null */
    function readContext() {
        var node = doc.getElementById('cs-context');
        var goodsId = null;
        var orderNo = null;
        var goodsName = null;
        if (node && node.dataset) {
            if (node.dataset.goodsId) {
                goodsId = parseInt(node.dataset.goodsId, 10);
                if (isNaN(goodsId)) {
                    goodsId = null;
                }
            }
            orderNo = node.dataset.orderNo || null;
            goodsName = node.dataset.goodsName || null;
        }
        return { goodsId: goodsId, orderNo: orderNo, goodsName: goodsName };
    }

    /** 建节点：文本一律 textContent（XSS 红线），绝不接受 HTML 片段 */
    function el(tag, className, text) {
        var node = doc.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined && text !== null) {
            node.textContent = text;
        }
        return node;
    }

    function build() {
        var ctx = readContext();

        // ---- 触发按钮 ----
        var fab = el('button', 'cs-fab', '在线客服');
        fab.type = 'button';
        fab.id = 'cs-fab';
        fab.setAttribute('aria-label', '打开在线客服');

        // ---- 面板 ----
        var panel = el('section', 'cs-float');
        panel.id = 'cs-float';
        panel.hidden = true;                 // 不自动打开（红线）
        panel.setAttribute('role', 'dialog');
        panel.setAttribute('aria-label', '在线客服');

        var head = el('header', 'cs-float__h');
        head.appendChild(el('span', 'cs-float__title', '智能客服'));
        var expand = el('a', 'cs-float__expand', '展开完整页');
        expand.href = ctx.goodsId ? '/cs?goodsId=' + encodeURIComponent(ctx.goodsId)
            : (ctx.orderNo ? '/cs?orderNo=' + encodeURIComponent(ctx.orderNo) : '/cs');
        head.appendChild(expand);
        var close = el('button', 'cs-float__close', '×');
        close.type = 'button';
        close.setAttribute('aria-label', '收起客服');
        head.appendChild(close);
        panel.appendChild(head);

        // ---- 上下文条（有商品/订单时才显示）----
        var contextBar = null;
        if (ctx.goodsName || ctx.orderNo) {
            contextBar = el('div', 'cs-context-bar');
            contextBar.appendChild(doc.createTextNode(ctx.goodsName ? '正在咨询：' : '正在咨询订单：'));
            contextBar.appendChild(el('b', null, ctx.goodsName || ctx.orderNo));
            panel.appendChild(contextBar);
        }

        // ---- 消息区 ----
        var messages = el('div', 'cs-messages');
        messages.id = 'cs-float-messages';
        messages.appendChild(el('div', 'cs-msg cs-msg--bot',
            '你好，我是新蜂商城的智能客服。可以问我商品有没有货、多少钱，或者订单进度。'));
        panel.appendChild(messages);

        // ---- 快捷 chips ----
        var chips = el('div', 'cs-chips');
        QUICK_QUESTIONS.forEach(function (q) {
            var chip = el('button', 'cs-chip', q);
            chip.type = 'button';
            chip.addEventListener('click', function () {
                ask(q);
            });
            chips.appendChild(chip);
        });
        panel.appendChild(chips);

        // ---- 输入区 ----
        var form = el('form', 'cs-composer');
        form.id = 'cs-float-form';
        form.setAttribute('autocomplete', 'off');
        var input = doc.createElement('textarea');
        input.id = 'cs-float-input';
        input.rows = 1;
        input.placeholder = '想问点什么？（Enter 发送 / Shift+Enter 换行）';
        var send = el('button', null, '发送');
        send.type = 'submit';
        send.id = 'cs-float-send';
        form.appendChild(input);
        form.appendChild(send);
        panel.appendChild(form);

        doc.body.appendChild(fab);
        doc.body.appendChild(panel);

        return {
            fab: fab, panel: panel, close: close, expand: expand,
            messages: messages, input: input, send: send, form: form, ctx: ctx
        };
    }

    /** 追加一条消息；返回消息节点（便于把流式文本写进它的独立子节点） */
    function appendMessage(ui, kind, text) {
        var node = el('div', 'cs-msg cs-msg--' + kind, text);
        ui.messages.appendChild(node);
        ui.messages.scrollTop = ui.messages.scrollHeight;
        return node;
    }

    function init() {
        var ui = build();
        var streaming = false;

        function setStreaming(active) {
            streaming = active;
            ui.send.disabled = active;
            ui.send.textContent = active ? '回答中…' : '发送';
        }

        function open() {
            ui.panel.hidden = false;
            ui.fab.classList.add('cs-fab--hidden');
            ui.input.focus();
        }

        function close() {
            ui.panel.hidden = true;
            ui.fab.classList.remove('cs-fab--hidden');
        }

        function ask(question) {
            if (streaming || !question) {
                return;
            }
            appendMessage(ui, 'user', question);

            var bot = appendMessage(ui, 'bot', '');
            bot.classList.add('cs-caret');
            // ⚠️ 文本必须有自己的容器：textContent 赋值会清空子节点，
            //    否则 onTool 挂进来的商品卡片会被 onDelta 立刻抹掉（M3-A 的真 bug）
            var botText = el('span', 'cs-msg__text');
            bot.appendChild(botText);

            setStreaming(true);

            Cs.streamChat({
                question: question,
                goodsId: ui.ctx.goodsId,
                orderNo: ui.ctx.orderNo,
                handlers: {
                    onTool: function (evt) {
                        // 商品卡片只来自 tool 事件（不从模型文本解析 —— 防幻觉，DESIGN §8.4）
                        var card = Cs.renderGoodsCard(evt);
                        if (card) {
                            bot.appendChild(card);
                            ui.messages.scrollTop = ui.messages.scrollHeight;
                        }
                    },
                    onDelta: function (text) {
                        botText.textContent = botText.textContent + text;
                        ui.messages.scrollTop = ui.messages.scrollHeight;
                    },
                    onDone: function () {
                        // done 不是终止事件（audit 下 review 还在后面），这里只去掉光标
                        bot.classList.remove('cs-caret');
                    },
                    onError: function (message) {
                        bot.classList.remove('cs-caret');
                        appendMessage(ui, 'error', message);
                    },
                    onFinish: function () {
                        bot.classList.remove('cs-caret');
                        if (!botText.textContent) {
                            botText.textContent = '（没有收到回答，请稍后再试）';
                        }
                        setStreaming(false);
                        ui.input.focus();
                    }
                }
            });
        }

        ui.fab.addEventListener('click', open);
        ui.close.addEventListener('click', close);
        ui.form.addEventListener('submit', function (e) {
            e.preventDefault();
            var q = ui.input.value.trim();
            if (!q) {
                return;
            }
            ui.input.value = '';
            ask(q);
        });
        ui.input.addEventListener('keydown', function (e) {
            // Enter 发送 / Shift+Enter 换行（与 /cs 页一致）
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                if (typeof ui.form.requestSubmit === 'function') {
                    ui.form.requestSubmit();
                } else {
                    ui.form.dispatchEvent(new Event('submit', { cancelable: true }));
                }
            }
        });

        global.CsWidget = { open: open, close: close, ask: ask };
    }

    if (doc.readyState === 'loading') {
        doc.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(window);
