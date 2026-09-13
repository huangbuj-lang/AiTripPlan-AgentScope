/* ============================================================
 * 对话式改行程（纯新增文件，不改动 app.js 的任何既有函数逻辑）
 *
 * 用法（由 app.js 在"结果区有行程显示"时调用一行）：
 *   window.TripChat.attach(viewingPlanId, currentPlanData);
 *   window.TripChat.detach();
 *
 * 只做三件事：
 *   ① 一条输入框 + 发送 + 4 个快捷胶囊 → POST /api/trip-chat-edit
 *   ② 重排期间把目标那一天卡片置灰 + 提示"正在重排第 X 天…"
 *   ③ 成功后重绘那一天的行程 + 顶部「变更小结」卡片 + 一键撤销
 *
 * 变更小结口径（用户反馈后重做）：
 *   ① 去掉（只地点；时间放括号）② 新增（同）③ 时间调整（地点 原时间→新时间）
 *   全部按地点去重；没有变化的那段不显示。
 *   若后端 success:false 或 hardChecks/continuity 里有未通过项 → 用【警告样式】
 *   明确写「没做到：…」「冲突：…」，绝不显示成绿色成功。
 *
 * 保存行程联动：重排成功后把后端权威数据（markdown + 整份 plan）写回 app.js 的模块内状态
 * （window.TripChatState.set），否则"保存到我的行程"会读到空文本报"暂无结果可保存"。
 *
 * 数据真实性：后端查不到的高德字段会回"暂无"，前端只是原样展示，不编造。
 * ============================================================ */
(function () {
    var HOST_ID = 'tripChatBar';
    var CHIPS = [
        '第三天太赶了，少排一个点',
        '第三天想住得舒服点',
        '第三天要下雨，换成室内的',
        '整体再加一天'
    ];
    /* 整体性要求（本功能只改一天，这类要求如实回绝、不假装支持） */
    var WHOLE_RE = /整体|全程|整条|全部|所有天|每一天|加一天|多一天|少一天|减一天|加两天|减两天/;

    var state = {
        planId: null,
        plan: null,
        renderedFor: '__none__',
        busy: false,
        last: null            // {sectionIdx, prevSection, dayIndex, ...} 供撤销
    };

    /* ---------------- 样式（沿用项目 CSS 变量；只作用于本组件） ---------------- */
    function injectStyle() {
        if (document.getElementById('tripchatStyle')) return;
        var css = [
            '#tripChatBar{margin:18px 0 6px}',
            '.tripchat-card{background:#fff;border:1px solid var(--sand-200);border-radius:16px;padding:14px 16px}',
            '.tripchat-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px}',
            '.tripchat-title{font-family:var(--serif);font-size:15px;font-weight:700;color:var(--forest-900)}',
            '.tripchat-sub{font-size:12px;color:var(--muted)}',
            '.tripchat-chips{display:flex;gap:7px;flex-wrap:wrap;margin-bottom:10px}',
            '.tripchat-chip{font-size:12.5px;padding:5px 13px;border-radius:9999px;border:1px solid var(--sand-200);',
            '  background:var(--sand-50);color:var(--forest-900);cursor:pointer;font-family:inherit;transition:.15s}',
            '.tripchat-chip:hover:enabled{background:var(--green-100);border-color:var(--green-100)}',
            '.tripchat-inrow{display:flex;gap:9px}',
            '.tripchat-input{flex:1;font-family:inherit;font-size:14px;padding:11px 15px;border-radius:9999px;',
            '  border:1.5px solid var(--sand-200);outline:none;background:var(--sand-50);color:var(--forest-900)}',
            '.tripchat-input:focus{border-color:var(--green-600);background:#fff}',
            '.tripchat-send{padding:11px 22px;border-radius:9999px;border:none;background:var(--green-600);color:#fff;',
            '  font-size:14px;font-family:inherit;cursor:pointer}',
            '.tripchat-send:disabled{background:var(--sand-200);color:var(--muted);cursor:not-allowed}',
            '.tripchat-msg{font-size:12.5px;color:var(--muted);margin-top:8px;min-height:17px}',
            '.tripchat-msg.ok{color:var(--green-600)}',
            '.tripchat-msg.warn{color:var(--rose)}',
            /* 变更小结卡片（成功=绿框；未做到/冲突=警告红框） */
            '.tripchat-summary{display:none;background:#fff;border:1.5px solid var(--green-600);border-radius:12px;',
            '  padding:13px 16px;margin-bottom:14px}',
            '.tripchat-summary.on{display:block}',
            '.tripchat-summary.warn{border-color:var(--rose);background:#fff7f6}',
            '.tripchat-summary .h{display:flex;align-items:center;gap:8px;font-size:13.5px;font-weight:600;',
            '  color:var(--green-600);margin-bottom:7px;flex-wrap:wrap}',
            '.tripchat-summary .h.warn{color:var(--rose)}',
            '.tripchat-summary .h em{font-style:normal;font-weight:400;color:var(--muted);font-size:12.5px}',
            '.tripchat-summary ul{list-style:none;margin:0;padding:0}',
            '.tripchat-summary li{font-size:13px;color:var(--forest-900);padding:2px 0 2px 16px;position:relative}',
            '.tripchat-summary li:before{content:"·";position:absolute;left:3px;color:var(--gold);font-weight:700}',
            '.tripchat-summary li.warn:before{color:var(--rose)}',
            '.tripchat-summary li.warn{color:var(--rose)}',
            '.tripchat-summary li.dim{color:var(--muted)}',
            '.tripchat-undo{margin-top:9px}',
            '.tripchat-undo button{font-size:12.5px;padding:5px 14px;border-radius:9999px;border:1px solid var(--sand-200);',
            '  background:var(--sand-50);color:var(--forest-900);cursor:pointer;font-family:inherit}',
            '.tripchat-undo button:hover{background:var(--sand-100)}',
            /* 重排中的那天卡片置灰 */
            '#resultBody .day-card.tripchat-pending{opacity:.45;filter:grayscale(.25)}'
        ].join('\n');
        var el = document.createElement('style');
        el.id = 'tripchatStyle';
        el.textContent = css;
        document.head.appendChild(el);
    }

    /* ---------------- 对外入口 ---------------- */
    function attach(planId, plan) {
        var host = document.getElementById(HOST_ID);
        if (!host) return;
        if (state.busy) return;                       // 重排中不动界面（否则会把用户输入/小结清掉）
        var hasDays = !!(plan && plan.sections && plan.sections.some(function (s) {
            return s && s.type === 'timeline';
        }));
        if (!hasDays) { detach(); return; }
        if (state.renderedFor === planId && state.plan === plan) { host.style.display = ''; return; }
        injectStyle();
        state.planId = planId || null;
        state.plan = plan;
        state.renderedFor = planId;
        host.innerHTML = barHtml();
        bind(host);
        host.style.display = '';
        if (!state.planId) {
            notice('这条行程还没保存到「我的行程」，保存后才能对话式修改', 'warn');
        }
    }

    function detach() {
        var host = document.getElementById(HOST_ID);
        if (host) { host.style.display = 'none'; host.innerHTML = ''; }
        state.renderedFor = '__none__';
        state.planId = null;
        state.plan = null;
        state.last = null;
    }

    function barHtml() {
        return '<div class="tripchat-summary" id="tripchatSummary"></div>'
            + '<div class="tripchat-card">'
            + '<div class="tripchat-head"><span class="tripchat-title">✦ 对话式改行程</span>'
            + '<span class="tripchat-sub">只重排你点名的那一天，其他天不动</span></div>'
            + '<div class="tripchat-chips">' + CHIPS.map(function (c, i) {
                return '<button type="button" class="tripchat-chip" data-chip="' + i + '">' + esc(c) + '</button>';
            }).join('') + '</div>'
            + '<div class="tripchat-inrow">'
            + '<input type="text" class="tripchat-input" id="tripchatInput" '
            + 'placeholder="说说你想改什么，例如：第三天太赶了，少排一个点…">'
            + '<button type="button" class="tripchat-send" id="tripchatSend">发送</button>'
            + '</div>'
            + '<div class="tripchat-msg" id="tripchatMsg"></div>'
            + '</div>';
    }

    function bind(host) {
        var input = host.querySelector('#tripchatInput');
        var sendBtn = host.querySelector('#tripchatSend');
        if (sendBtn) sendBtn.addEventListener('click', function () { submit(input ? input.value : ''); });
        if (input) {
            input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') { e.preventDefault(); submit(input.value); }
            });
        }
        Array.prototype.forEach.call(host.querySelectorAll('.tripchat-chip'), function (b) {
            b.addEventListener('click', function () {
                var t = CHIPS[Number(b.getAttribute('data-chip'))] || '';
                if (input) input.value = t;
                submit(t);
            });
        });
    }

    /* ---------------- 发送 ---------------- */
    function submit(text) {
        if (state.busy) return;
        text = (text || '').trim();
        if (!text) return;
        //整体性要求：本接口只改一天，如实回绝（不假装支持）
        if (WHOLE_RE.test(text)) {
            notice('「整体调整」（增减天数 / 改全行程）请重新生成行程', 'warn');
            return;
        }
        var pid = state.planId || readViewingPlanId();
        if (!pid) {
            notice('这条行程还没保存到「我的行程」，保存后才能对话式修改', 'warn');
            return;
        }
        state.planId = pid;
        var dayGuess = guessDay(text);           // 1 起的第几天；识别不到 0
        state.busy = true;
        hideSummary();                            // 上一次的小结先收起来（避免旧绿框误导）
        setBusyUi(true, dayGuess);
        if (dayGuess > 0) markPending(dayGuess - 1, true);

        var url = '/api/trip-chat-edit?planId=' + encodeURIComponent(pid)
            + '&instruction=' + encodeURIComponent(text);
        fetch(url, { method: 'POST', headers: { 'Accept': 'application/json' } })
            .then(function (r) {
                return r.json().catch(function () {
                    return { success: false, message: '后端返回了非 JSON 响应（HTTP ' + r.status + '）' };
                });
            })
            .then(function (d) {
                state.busy = false;
                setBusyUi(false, dayGuess);
                if (dayGuess > 0) markPending(dayGuess - 1, false);
                if (!d || !d.success) {
                    var msg = (d && d.message) || '重排失败，请稍后重试';
                    notice(msg, 'warn');
                    showSummary(d || { success: false, message: msg });   //★失败也用小结卡片（警告样式）
                    return;
                }
                applyResult(d);
            })
            .catch(function (e) {
                state.busy = false;
                setBusyUi(false, dayGuess);
                if (dayGuess > 0) markPending(dayGuess - 1, false);
                notice('网络异常：' + e, 'warn');
            });
    }

    /* ---------------- 成功后：重绘那一天 + 变更小结 ---------------- */
    function applyResult(d) {
        //★先把后端权威数据写回 app.js 的模块内状态（保存行程用）——必须在重绘之前做，
        //  且必须在"本地找不到那一天"的兜底之前做：库里已经改了，本地状态不能还留着旧数据。
        if (window.TripChatState && typeof window.TripChatState.set === 'function') {
            window.TripChatState.set(d.markdown, d.plan, state.planId);
        }
        if (d.plan && d.plan.sections && d.plan.sections.length) {
            state.plan = d.plan;     //与库里的（后端权威）行程保持一致
        }
        var idx = sectionIndexForDay(d.dayIndex);
        if (idx < 0) {
            notice('后端返回的第 ' + (d.dayIndex + 1) + ' 天在本地行程里找不到，未改动显示（请刷新页面）', 'warn');
            return;
        }
        state.last = {
            sectionIdx: idx,
            prevSection: d.prevSection,
            dayIndex: d.dayIndex
        };
        if (d.section) state.plan.sections[idx] = d.section;
        rerender();
        showSummary(d);
        notice('已重排第 ' + (d.dayIndex + 1) + ' 天' + (d.dayTitle ? '：' + d.dayTitle : ''), 'ok');
    }

    /** 沿用 app.js 现有的渲染函数重绘整份行程（不改它的任何逻辑） */
    function rerender() {
        var rb = document.getElementById('resultBody');
        if (!rb || !state.plan) return;
        var fn = (typeof window.renderStructuredPlan === 'function') ? window.renderStructuredPlan : null;
        if (!fn) return;
        rb.innerHTML = fn(state.plan);
    }

    /* ---------------- 变更小结 ---------------- */

    /** 主入口：成功 → 绿框三段式小结；没做到/冲突 → 警告样式 */
    function showSummary(d) {
        var box = document.getElementById('tripchatSummary');
        if (!box) return;
        d = d || {};
        var hardFail = failTexts(d.hardChecks, 'note');
        var contFail = failTexts(d.continuity, 'detail');
        var failed = (d.success === false) || hardFail.length > 0 || contFail.length > 0;
        var dayNo = (typeof d.dayIndex === 'number' && d.dayIndex >= 0) ? (d.dayIndex + 1) : '';

        var lines = [];
        if (failed) {
            hardFail.forEach(function (t) { lines.push({ html: '没做到：' + esc(t), warn: true }); });
            contFail.forEach(function (t) { lines.push({ html: '冲突：' + esc(t), warn: true }); });
            if (!lines.length) {
                lines.push({ html: '没做到/冲突：' + esc(d.message || '这次改动没有满足硬性要求'), warn: true });
            } else if (d.message) {
                lines.push({ html: esc(d.message), warn: false, dim: true });
            }
            lines.push({ html: '这一天已保留原样（没有写库），其他天未改动', warn: false, dim: true });
        } else {
            var diff = d.diff || {};
            lines.push({ html: '点位数 ' + n(diff.pointsBefore) + ' → <b>' + n(diff.pointsAfter) + '</b>', warn: false });
            lines.push({
                html: '全天车程（沿用现有校验口径）' + esc(n(diff.driveBefore)) + ' → <b>' + esc(n(diff.driveAfter)) + '</b>',
                warn: false
            });
            //① 去掉（只地点；时间放括号）
            var removed = dedupePlaces(diff.removed);
            if (removed.length) {
                lines.push({ html: '去掉：' + esc(removed.map(withTime).join('、')), warn: false });
            }
            //② 新增（同）
            var added = dedupePlaces(diff.added);
            if (added.length) {
                lines.push({ html: '新增：' + esc(added.map(withTime).join('、')), warn: false });
            }
            //③ 时间调整（地点 原时间→新时间）
            var changed = dedupeChanges(diff.changed);
            if (changed.length) {
                lines.push({
                    html: '时间调整：' + esc(changed.map(function (c) {
                        return c.place + ' ' + (c.timeBefore || '—') + '→' + (c.timeAfter || '—');
                    }).join('、')),
                    warn: false
                });
            }
            //后端硬判定的落实情况（只展示，判定在后端）
            (d.hardChecks || []).forEach(function (c) {
                if (c && c.ok !== false && !c.skipped && c.note) {
                    lines.push({ html: '✔ ' + esc(c.note), warn: false });
                }
            });
            var ctLine = continuityLine(d.continuity);
            if (ctLine) lines.push({ html: ctLine, warn: false, dim: true });
            lines.push({ html: '用到的真实信息：高德实查 <b>' + n(d.factsUsed) + '</b> 条', warn: false });
            (d.warnings || []).forEach(function (w) {
                lines.push({ html: '⚠ ' + esc(String(w)), warn: true });
            });
        }

        var head = failed
            ? ('⚠ 没改成' + (dayNo ? '（第 ' + dayNo + ' 天）' : '') + '<em>其他天未改动</em>')
            : ('✓ 第 ' + ((typeof d.dayIndex === 'number' ? d.dayIndex + 1 : '') || '') + ' 天已重排'
                + (countDays() > 0 ? '<em>其他 ' + (countDays() - 1) + ' 天未改动</em>' : ''));

        box.className = 'tripchat-summary on' + (failed ? ' warn' : '');
        box.innerHTML = '<div class="h' + (failed ? ' warn' : '') + '">' + head + '</div>'
            + '<ul>' + lines.map(function (l) {
                var cls = l.warn ? ' class="warn"' : (l.dim ? ' class="dim"' : '');
                return '<li' + cls + '>' + l.html + '</li>';
            }).join('') + '</ul>'
            + (failed ? '' : '<div class="tripchat-undo"><button type="button" id="tripchatUndo">撤销这次改动</button></div>');
        if (!failed) {
            var btn = document.getElementById('tripchatUndo');
            if (btn) btn.addEventListener('click', undo);
        }
    }

    function hideSummary() {
        var box = document.getElementById('tripchatSummary');
        if (!box) return;
        box.className = 'tripchat-summary';
        box.innerHTML = '';
    }

    /** 后端判定未通过的条目文本（ok===false 才算未通过；skipped 表示"没判定"） */
    function failTexts(arr, field) {
        var out = [];
        (arr || []).forEach(function (c) {
            if (!c || c.ok !== false) return;
            var v = c[field] || c.note || c.detail || c.rule || '';
            if (v) out.push(String(v));
        });
        return out;
    }

    /** 连续性一句话（判过且都通过才说"衔接一致"；都没写明就说"未作判定"） */
    function continuityLine(ct) {
        var judged = (ct || []).filter(function (c) { return c && !c.skipped; });
        var skipped = (ct || []).filter(function (c) { return c && c.skipped; });
        if (judged.length && !judged.some(function (c) { return c.ok === false; })) {
            return '跨天连续性：与前后两天没有重复景点/同类型重复，出发点与落脚点接得上';
        }
        if (!judged.length && skipped.length) {
            return '跨天连续性：这一天/相邻天没写明出发地与落脚点，未作判定';
        }
        return '';
    }

    /** 只留地点（按规范化地名去重）；元素统一成 {place, time} */
    function dedupePlaces(arr) {
        var seen = {}, out = [];
        (arr || []).forEach(function (x) {
            var p = '', t = '';
            if (typeof x === 'string') {
                p = x;
            } else if (x) {
                p = x.place || '';
                t = x.time || '';
            }
            p = String(p).trim();
            if (!p) return;
            var k = normKey(p);
            if (!k || seen[k]) return;
            seen[k] = 1;
            out.push({ place: p, time: String(t).trim() });
        });
        return out;
    }

    /** 时间调整（按地点去重；同一地点只报一次） */
    function dedupeChanges(arr) {
        var seen = {}, out = [];
        (arr || []).forEach(function (x) {
            if (!x) return;
            var p = String(x.place || '').trim();
            if (!p) return;
            var k = normKey(p);
            if (!k || seen[k]) return;
            seen[k] = 1;
            out.push({ place: p, timeBefore: String(x.timeBefore || ''), timeAfter: String(x.timeAfter || '') });
        });
        return out;
    }

    function withTime(o) {
        return o.place + (o.time ? '（' + o.time + '）' : '');
    }

    function normKey(s) {
        return String(s === undefined || s === null ? '' : s)
            .replace(/[\s（）()【】\[\]、,，。.·\-—→>／/\\"']/g, '');
    }

    /** 撤销：用返回的 prevSection 就地换回那一天（重排不落库，所以只还原画面即可）*/
    function undo() {
        if (!state.last || !state.plan) return;
        var s = state.last;
        if (s.prevSection) state.plan.sections[s.sectionIdx] = s.prevSection;
        state.last = null;
        rerender();
        hideSummary();
        //★用户口径（铁律）：重排【不落库】→ 撤销只需还原画面（库里从来没被改过，无需回滚）
        notice('已撤销，恢复到你改动前的样子（未保存的改动已丢弃）', 'ok');
    }

    /* ---------------- 小工具 ---------------- */
    function sectionIndexForDay(dayIndex) {
        if (!state.plan || !state.plan.sections) return -1;
        var n = 0;
        for (var i = 0; i < state.plan.sections.length; i++) {
            var s = state.plan.sections[i];
            if (s && s.type === 'timeline') {
                if (n === dayIndex) return i;
                n++;
            }
        }
        return -1;
    }

    /** DOM 里第 X 天卡片的序号（app.js 的天卡片顺序 = timeline 区块顺序 + 标题含"第X天"的区块） */
    function domDayIndex(dayIndex) {
        if (!state.plan || !state.plan.sections) return -1;
        var domIdx = -1, tlIdx = -1;
        for (var i = 0; i < state.plan.sections.length; i++) {
            var s = state.plan.sections[i];
            if (!s) continue;
            if (s.type === 'timeline' || /第.+天/.test(s.title || '')) domIdx++;
            if (s.type === 'timeline') {
                tlIdx++;
                if (tlIdx === dayIndex) return domIdx;
            }
        }
        return -1;
    }

    function markPending(dayIndex, on) {
        var cards = document.querySelectorAll('#resultBody .days .day-card');
        var i = domDayIndex(dayIndex);
        if (i < 0 || !cards[i]) return;
        if (on) cards[i].classList.add('tripchat-pending');
        else cards[i].classList.remove('tripchat-pending');
    }

    function setBusyUi(busy, dayGuess) {
        var input = document.getElementById('tripchatInput');
        var btn = document.getElementById('tripchatSend');
        if (input) input.disabled = busy;
        if (btn) { btn.disabled = busy; btn.textContent = busy ? '重排中…' : '发送'; }
        if (busy) {
            notice(dayGuess > 0 ? ('正在重排第 ' + dayGuess + ' 天…') : '正在重排…', '');
        }
    }

    function notice(text, kind) {
        var el = document.getElementById('tripchatMsg');
        if (!el) return;
        el.className = 'tripchat-msg' + (kind ? ' ' + kind : '');
        el.textContent = text || '';
    }

    /** 目标天识别（与后端同一口径：第3天 / 第 3 天 / Day 3 / D3 / 第三天 / 开头的光写数字「三」） */
    function guessDay(t) {
        var cn = { '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10 };
        function val(s) {
            if (/^[0-9]+$/.test(s)) return parseInt(s, 10);
            var ti = s.indexOf('十');
            if (ti >= 0) {
                var left = ti === 0 ? 1 : (cn[s.charAt(ti - 1)] || 0);
                var right = (s.length > ti + 1) ? (cn[s.charAt(ti + 1)] || 0) : 0;
                return left * 10 + right;
            }
            return cn[s.charAt(0)] || 0;
        }
        var m = t.match(/第\s*([0-9]+|[一二三四五六七八九十两]+)\s*[天日]/);
        if (m) return val(m[1]);
        m = t.match(/\bday\s*([0-9]{1,2})/i);
        if (m) return parseInt(m[1], 10);
        m = t.match(/(?:^|[^a-z])d\s*([0-9]{1,2})(?![0-9])/i);
        if (m) return parseInt(m[1], 10);
        m = t.match(/^\s*([一二三四五六七八九十两])(?![个天日小时分次人公])/);
        if (m) return val(m[1]);
        return 0;
    }

    /** 读取 app.js 里的当前行程 id（它是 app.js 顶层的 let；取不到就返回 null） */
    function readViewingPlanId() {
        try {
            /* eslint-disable no-undef */
            if (typeof viewingPlanId !== 'undefined' && viewingPlanId) return viewingPlanId;
        } catch (e) { /* TDZ：忽略 */ }
        return null;
    }

    function countDays() {
        if (!state.plan || !state.plan.sections) return 0;
        var n = 0;
        state.plan.sections.forEach(function (s) { if (s && s.type === 'timeline') n++; });
        return n;
    }

    function n(v) { return (v === undefined || v === null || v === '') ? 0 : v; }
    function esc(s) {
        return String(s === undefined || s === null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    window.TripChat = { attach: attach, detach: detach };
})();
