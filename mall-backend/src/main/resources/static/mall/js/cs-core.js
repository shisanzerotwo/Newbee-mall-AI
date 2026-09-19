/*!
 * cs-core.js —— 客服前端核心（M3-A），浮窗与 /cs 完整页<b>共用</b>这一份，不写两套。
 *
 * 三条硬约束（DESIGN §8.1 / §8.2 / §8.4），改代码前先读：
 *
 *   1. 【XSS 强制】模型流式文本、工具返回的商品名，渲染时<b>一律经 Cs.escapeHtml</b>，
 *      或者只走 textContent。本文件<b>全程只用 createElement + textContent 构造节点</b>，
 *      不拼接任何 HTML 字符串 —— 这样就不存在"忘了转义"的可能（也便于用 grep 守住）。
 *      escapeHtml 覆盖 & < > " ' 五个字符。注意<b>必须包含单引号</b>：
 *      Python 基准（web/index.html:219）的 esc() 漏了它，在属性语境下是真实缺口，本项目不继承。
 *
 *   2. 【数据来源】卡片里的商品信息只从 SSE 的 `tool` 事件（args.goodsId）取，
 *      <b>绝不从模型文本里正则抠数</b>—— 模型会幻觉出不存在的商品名/价格（DESIGN §8.4）。
 *      本文件因此不显示任何价格/库存数字：这些只有工具结果才有，
 *      而当前 tool 事件只带 name/args/ms/ok（不带 result 正文），所以卡片只给
 *      "商品链接 + 工具名 + 耗时"，把权威数字留给商品详情页这一真实数据源。
 *
 *   3. 【时序】`done` <b>不是</b>终止事件（DESIGN §4.2）：audit 模式下 done 先发、review 后到。
 *      所以客户端只在收到 `review` 或连接真正关闭后才停止读取。
 */
(function (global) {
    'use strict';

    var ESCAPE_MAP = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;'
    };

    /**
     * HTML 转义，覆盖 & < > " ' 五个字符。
     * @param {*} value 任意值（null/undefined 一律返回空串，避免渲染出 "null"）
     * @returns {string}
     */
    function escapeHtml(value) {
        if (value === null || value === undefined) {
            return '';
        }
        return String(value).replace(/[&<>"']/g, function (ch) {
            return ESCAPE_MAP[ch];
        });
    }

    /** 会话标识：localStorage 自持（不用 sessionId —— 见 DESIGN §7.4） */
    function conversationId() {
        var KEY = 'nb-cs-conversation-id';
        try {
            var existing = global.localStorage.getItem(KEY);
            if (existing) {
                return existing;
            }
            var fresh = 'c-' + Date.now().toString(36) + '-' +
                Math.random().toString(36).slice(2, 10);
            global.localStorage.setItem(KEY, fresh);
            return fresh;
        } catch (e) {
            // 隐私模式等场景下 localStorage 不可用：退化为「本次页面会话」
            if (!conversationId.fallback) {
                conversationId.fallback = 'c-tmp-' + Math.random().toString(36).slice(2, 10);
            }
            return conversationId.fallback;
        }
    }

    /**
     * 解析一个 SSE 帧（事件名 + 原始 data 文本）成 {event, data}。
     * data 解析失败时返回 {event, data:null, raw}，由调用方决定怎么忽略。
     */
    function parseFrame(frame) {
        var eventName = 'message';
        var dataLines = [];
        frame.split(/\r?\n/).forEach(function (line) {
            if (line.indexOf('event:') === 0) {
                eventName = line.slice(6).trim();
            } else if (line.indexOf('data:') === 0) {
                dataLines.push(line.slice(5).replace(/^ /, ''));
            }
        });
        if (!dataLines.length) {
            return { event: eventName, data: null, raw: '' };
        }
        var raw = dataLines.join('\n');
        try {
            return { event: eventName, data: JSON.parse(raw), raw: raw };
        } catch (e) {
            return { event: eventName, data: null, raw: raw };
        }
    }

    /**
     * 发起一次流式问答（POST + fetch ReadableStream；EventSource 不支持 POST，故手写解析）。
     *
     * @param {Object} opts
     * @param {string} opts.question 用户问题
     * @param {number} [opts.goodsId] 当前咨询的商品
     * @param {string} [opts.orderNo] 当前咨询的订单
     * @param {string} [opts.conversationId] 缺省用 conversationId()
     * @param {string} [opts.url] 缺省 /api/cs/chat
     * @param {Object} opts.handlers 事件回调：onStage/onTool/onRag/onDelta/onReview/onDone/onError/onFinish
     * @returns {{abort: function}}
     */
    function streamChat(opts) {
        var handlers = opts.handlers || {};
        var controller = new AbortController();
        var finished = false;

        function emit(name, arg) {
            if (typeof handlers[name] === 'function') {
                handlers[name](arg);
            }
        }

        function finish(reason) {
            if (finished) {
                return;
            }
            finished = true;
            emit('onFinish', reason);
        }

        var payload = {
            question: opts.question,
            conversationId: opts.conversationId || conversationId(),
            goodsId: opts.goodsId === undefined ? null : opts.goodsId,
            orderNo: opts.orderNo === undefined ? null : opts.orderNo
        };

        fetch(opts.url || '/api/cs/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
            body: JSON.stringify(payload),
            signal: controller.signal
        }).then(function (response) {
            if (!response.ok || !response.body) {
                // 429（限流 / 上一条还在回答）时，后端仍会发一帧 SSE error，**里面写着可读的中文原因**
                //（"上一条还在回答中，请等它答完再问"）。若不读出来，用户只能看到 "HTTP 429"，
                // 后端特意写的话术就白写了。这里把响应体读出来抽 message。
                if (response.status === 429) {
                    return response.text().then(function (raw) {
                        var msg = null;
                        try {
                            var m = /"message"\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(raw || '');
                            if (m) { msg = JSON.parse('"' + m[1] + '"'); }
                        } catch (e) { /* 解析失败就用兜底文案 */ }
                        throw new Error(msg || '请求过于频繁，请稍后再试');
                    });
                }
                throw new Error('HTTP ' + response.status);
            }
            var reader = response.body.getReader();
            var decoder = new TextDecoder('utf-8');
            var buffer = '';

            function pump() {
                return reader.read().then(function (result) {
                    if (result.done) {
                        // 连接关闭即结束（服务端只会在发完 review 后 complete）
                        finish('closed');
                        return;
                    }
                    buffer += decoder.decode(result.value, { stream: true });

                    var boundary = buffer.indexOf('\n\n');
                    while (boundary !== -1) {
                        var frame = buffer.slice(0, boundary);
                        buffer = buffer.slice(boundary + 2);
                        dispatch(parseFrame(frame));
                        if (finished) {
                            return;
                        }
                        boundary = buffer.indexOf('\n\n');
                    }
                    return pump();
                });
            }

            function dispatch(parsed) {
                var d = parsed.data;
                switch (parsed.event) {
                    case 'stage':
                        emit('onStage', d || {});
                        break;
                    case 'rag':
                        emit('onRag', (d && d.sources) || []);
                        break;
                    case 'tool':
                        emit('onTool', d || {});
                        break;
                    case 'delta':
                        emit('onDelta', (d && d.text) || '');
                        break;
                    case 'review':
                        emit('onReview', d || {});
                        // ⭐ done 不是终止事件：真正的结束信号是 review
                        finish('review');
                        if (controller.abort) {
                            controller.abort();
                        }
                        break;
                    case 'done':
                        emit('onDone', d || {});
                        // 故意不 finish —— audit 模式下 review 还在后面
                        break;
                    case 'error':
                        emit('onError', (d && d.message) || '服务暂时不可用');
                        break;
                    default:
                        // 未知事件：忽略（保证老客户端对新事件的向后兼容）
                        break;
                }
            }

            return pump();
        }).catch(function (err) {
            if (err && err.name === 'AbortError') {
                finish('aborted');
                return;
            }
            emit('onError', '网络异常，请稍后再试');
            finish('failed');
        });

        return {
            abort: function () {
                controller.abort();
                finish('aborted');
            }
        };
    }

    /**
     * 商品卡片：<b>只</b>用 SSE `tool` 事件里的数据构造。
     *
     * 当前 tool 事件带 name/args/ms/ok，不带 result 正文，所以卡片展示
     * 「工具名 + 真实 goodsId + 耗时 + 商品链接」，<b>不编造价格/库存</b>。
     *
     * @param {{name: string, args: Object, ms: number, ok: boolean}} toolEvent
     * @returns {HTMLElement|null} 无 goodsId 时返回 null（该工具调用不产生商品卡片）
     */
    function renderGoodsCard(toolEvent) {
        var args = (toolEvent && toolEvent.args) || {};
        var goodsId = args.goodsId;
        if (goodsId === undefined || goodsId === null || goodsId === '') {
            return null;
        }
        var card = document.createElement('div');
        card.className = 'cs-goods-card';
        card.setAttribute('data-goods-id', String(goodsId));

        var title = document.createElement('a');
        title.className = 'cs-goods-card__title';
        title.setAttribute('href', '/goods/detail/' + encodeURIComponent(String(goodsId)));
        title.setAttribute('target', '_blank');
        title.setAttribute('rel', 'noopener noreferrer');
        // 文案来自工具名与 goodsId（非模型文本），仍统一走 textContent
        title.textContent = '商品 #' + String(goodsId) + '（点开看权威价格/库存）';
        card.appendChild(title);

        var meta = document.createElement('div');
        meta.className = 'cs-goods-card__meta';
        meta.textContent = toolEvent.name + ' · ' +
            (typeof toolEvent.ms === 'number' ? toolEvent.ms + 'ms' : '') +
            (toolEvent.ok === false ? ' · 调用失败' : '');
        card.appendChild(meta);

        return card;
    }

    /**
     * 上下文面板（DESIGN §8.2 六区块）：把事件流累积成可观测信息。
     *
     * <p>面板里出现的一切文本都用 textContent 写入，天然免疫 XSS。
     */
    function ContextPanel(root) {
        this.root = root;
        this.blocks = {};
        this._init();
    }

    var BLOCK_DEFS = [
        ['intent', '识别意图'],
        ['tools', '工具调用'],
        ['goods', '命中商品'],
        ['order', '关联订单'],
        ['review', '质检复核'],
        ['sources', '引用来源']
    ];

    ContextPanel.prototype._init = function () {
        var self = this;
        this.root.textContent = '';
        BLOCK_DEFS.forEach(function (def) {
            var block = document.createElement('section');
            block.className = 'cs-block';
            block.setAttribute('data-block', def[0]);

            var heading = document.createElement('h4');
            heading.className = 'cs-block__h';
            heading.textContent = def[1];
            block.appendChild(heading);

            var body = document.createElement('div');
            body.className = 'cs-block__body';
            body.textContent = '—';
            block.appendChild(body);

            self.root.appendChild(block);
            self.blocks[def[0]] = body;
        });
    };

    ContextPanel.prototype._set = function (key, text) {
        var body = this.blocks[key];
        if (!body) {
            return;
        }
        body.textContent = '';
        var line = document.createElement('div');
        line.className = 'cs-line';
        line.textContent = text;
        body.appendChild(line);
    };

    ContextPanel.prototype._append = function (key, node) {
        var body = this.blocks[key];
        if (!body) {
            return;
        }
        if (body.textContent === '—') {
            body.textContent = '';
        }
        body.appendChild(node);
    };

    /** 记录本轮上下文（意图即「我们送进去的问题 + 带入的上下文字段」） */
    ContextPanel.prototype.setIntent = function (question, goodsId, orderNo) {
        var parts = ['问题：' + question];
        if (goodsId) {
            parts.push('带入商品 #' + goodsId);
        }
        if (orderNo) {
            parts.push('带入订单 ' + orderNo);
        }
        this._set('intent', parts.join('　|　'));
    };

    /** 订单区块（订单号是透传值，展示即可，不声称已核验） */
    ContextPanel.prototype.setOrder = function (orderNo) {
        this._set('order', orderNo ? String(orderNo) + '（未核验归属，以工具查询为准）' : '本次未带入订单');
    };

    ContextPanel.prototype.addTool = function (toolEvent) {
        var line = document.createElement('div');
        line.className = 'cs-line';
        line.textContent = toolEvent.name + ' ' +
            JSON.stringify(toolEvent.args || {}) + ' · ' +
            (typeof toolEvent.ms === 'number' ? toolEvent.ms + 'ms' : '') +
            (toolEvent.ok === false ? ' · 失败' : '');
        this._append('tools', line);

        var card = renderGoodsCard(toolEvent);
        if (card) {
            card.setAttribute('data-goods-id', String((toolEvent.args || {}).goodsId));
            this._append('goods', card);
        }
    };

    ContextPanel.prototype.addStage = function (stageEvent) {
        var line = document.createElement('div');
        line.className = 'cs-line cs-line--muted';
        line.textContent = (stageEvent.stage || '') + ' · ' + (stageEvent.elapsed || 0) + 's';
        this._append('tools', line);
    };

    ContextPanel.prototype.setSources = function (sources) {
        if (!sources || !sources.length) {
            this._set('sources', '本次无检索命中');
            return;
        }
        var self = this;
        if (this.blocks.sources.textContent === '—') {
            this.blocks.sources.textContent = '';
        }
        sources.forEach(function (s) {
            var line = document.createElement('div');
            line.className = 'cs-line';
            // 融合分（RRF）量级很小，按原值展示，不当百分比
            line.textContent = (s.title || '(无标题)') + ' · 融合分 ' +
                (typeof s.score === 'number' ? s.score.toFixed(4) : String(s.score));
            self._append('sources', line);
        });
    };

    ContextPanel.prototype.setReview = function (review) {
        if (!review) {
            this._set('review', '未收到质检结论');
            return;
        }
        var text;
        if (review.qualified === true) {
            text = '合格' + (review.reason ? '：' + review.reason : '');
        } else if (review.qualified === false) {
            text = '不合格' + (review.reason ? '：' + review.reason : '');
        } else {
            // qualified === null 表示"没能得出结论"（质检超时），不是"不合格"
            text = review.reason || '质检未得出结论';
        }
        this._set('review', text);
    };

    global.Cs = {
        escapeHtml: escapeHtml,
        conversationId: conversationId,
        streamChat: streamChat,
        renderGoodsCard: renderGoodsCard,
        parseFrame: parseFrame,
        ContextPanel: ContextPanel,
        BLOCK_DEFS: BLOCK_DEFS
    };
})(window);
