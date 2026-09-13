/**
 * 全局行程生成状态胶囊（任何页面都显示）
 * ── 口径（用户要求）：
 *    只要用户没中断任务、服务没重启，无论跳到哪个页面，生成进度都要正常显示。
 * ── 做法：
 *    ① 读 localStorage 里的进行中标记（atp_gen_running = {sid, t}），与首页 app.js 同一份标记
 *    ② 订阅 /events/stream 拿真实阶段事件（首页刷新恢复用的也是这条路，已验证可用）
 *    ③ 每 5 秒探一次 /result：返回"任务不存在"= 任务已没（服务重启/已过期）→ 收起胶囊并清标记
 *    ④ 生成完成 → 变成"行程已生成，点这里查看"，点击回首页
 * ── 只在非首页加载（首页有完整的调度中心面板，避免重复）
 */
(function () {
    'use strict';
    if (document.getElementById('genStatusPill')) return;
    if (/\/index\.html?$|\/$/.test(location.pathname)) return;   // 首页有自己的面板

    var STAGE_PCT = { '解析': 8, '路线': 20, '行程': 35, '整合': 85, '完成': 100 };
    var STAGE_CEIL = { '解析': 20, '路线': 35, '行程': 80, '整合': 96, '完成': 100 };
    var STAGE_TEXT = {
        '解析': '正在理解需求', '路线': '正在规划路线',
        '行程': '正在安排行程', '整合': '正在整合检查', '完成': '行程已生成'
    };

    function readMark() {
        try {
            var m = JSON.parse(localStorage.getItem('atp_gen_running') || 'null');
            return (m && m.sid) ? m : null;
        } catch (e) { return null; }
    }
    function clearMark() { try { localStorage.removeItem('atp_gen_running'); } catch (e) { } }
    function fmt(sec) {
        sec = Math.max(0, Math.floor(sec));
        return String(Math.floor(sec / 60)).padStart(2, '0') + ':' + String(sec % 60).padStart(2, '0');
    }

    var mark = readMark();
    if (!mark) return;

    /* ---------- 胶囊 ---------- */
    var style = document.createElement('style');
    style.textContent =
        '#genStatusPill{position:fixed;right:18px;bottom:18px;z-index:9997;display:flex;flex-direction:column;gap:7px;' +
        'min-width:196px;background:#fff;border:1.5px solid var(--forest-950,#0F1A14);border-radius:14px;' +
        'padding:10px 13px 11px;box-shadow:0 10px 28px rgba(15,26,20,.18);cursor:pointer;' +
        'font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;color:#1D2D23;transition:transform .15s}' +
        '#genStatusPill:hover{transform:translateY(-2px)}' +
        '#genStatusPill .row1{display:flex;align-items:center;gap:7px;font-size:12.5px;white-space:nowrap}' +
        '#genStatusPill .dot{width:7px;height:7px;border-radius:50%;background:#C9A96A;flex-shrink:0;animation:gsp 1.3s infinite}' +
        '#genStatusPill.done .dot{background:#47755A;animation:none}' +
        '@keyframes gsp{0%,100%{opacity:1}50%{opacity:.25}}' +
        '#genStatusPill b{font-family:"SF Mono",Consolas,monospace;color:#8A6D2F;font-weight:800}' +
        '#genStatusPill .used{margin-left:auto;font-family:"SF Mono",Consolas,monospace;font-size:11px;color:#64736E}' +
        '#genStatusPill .bar{height:5px;background:#EAE3D5;border-radius:99px;overflow:hidden}' +
        '#genStatusPill .bar i{display:block;height:100%;width:0%;background:linear-gradient(90deg,#47755A,#7fd4c0);' +
        'border-radius:99px;transition:width .6s}' +
        '#genStatusPill .hint{font-size:11px;color:#64736E;white-space:nowrap}';
    document.head.appendChild(style);

    var pill = document.createElement('div');
    pill.id = 'genStatusPill';
    pill.title = '点击回到首页查看完整进度';
    pill.innerHTML =
        '<div class="row1"><i class="dot"></i><span class="txt">行程生成中</span>' +
        '<b class="pct">0%</b><span class="used">已用 00:00</span></div>' +
        '<div class="bar"><i></i></div>' +
        '<div class="hint">点击回到首页看完整进度</div>';
    document.body.appendChild(pill);

    var elPct = pill.querySelector('.pct'), elTxt = pill.querySelector('.txt'),
        elUsed = pill.querySelector('.used'), elFill = pill.querySelector('.bar i'),
        elHint = pill.querySelector('.hint');
    pill.onclick = function () { location.href = 'index.html'; };

    /* ---------- 状态：单调进度 + 真实阶段 ---------- */
    var fired = {}, cur = '', pct = 0, ceiling = 0, finished = false;
    function render() {
        pct = Math.min(pct, ceiling);
        elPct.textContent = Math.round(pct) + '%';
        elFill.style.width = Math.round(pct) + '%';
        elTxt.textContent = finished ? '行程已生成' : (STAGE_TEXT[cur] || '行程生成中');
        if (finished) {
            pill.classList.add('done');
            elHint.textContent = '点这里查看生成的行程';
        }
    }
    function onEvent(type) {
        if (!(type in STAGE_PCT)) return;
        fired[type] = 1; cur = type;
        pct = Math.max(pct, STAGE_PCT[type]);
        ceiling = STAGE_CEIL[type];
        if (type === '完成') { finished = true; pct = 100; ceiling = 100; clearMark(); stopAll(); }
        render();
    }

    /* ---------- 计时 ---------- */
    var t0 = mark.t || Date.now();
    var clock = setInterval(function () {
        elUsed.textContent = '已用 ' + fmt((Date.now() - t0) / 1000);
        if (!finished) {                        // 与首页同口径的缓推，不跳档
            pct = Math.min(pct + 1, ceiling);
            render();
        }
    }, 1500);
    elUsed.textContent = '已用 ' + fmt((Date.now() - t0) / 1000);

    /* ---------- 事件：轮询 /events（不用 SSE 长连接）
       之前用 EventSource 长连接，浏览器标签会【一直转圈】（用户当场反馈）→ 换成短轮询
       /events 返回该会话累计的全部事件，天然幂等、单调，适合轮询 */
    function pollEvents() {
        fetch('/events?sessionId=' + encodeURIComponent(mark.sid))
            .then(function (r) { return r.json(); })
            .then(function (list) {
                if (!list || !list.length) return;
                for (var i = 0; i < list.length; i++) { if (list[i] && list[i].type) onEvent(list[i].type); }
            })
            .catch(function () { });
    }
    pollEvents();
    var evTimer = setInterval(pollEvents, 3000);

    /* ---------- 探活：任务还在不在（服务重启=任务不存在） ---------- */
    var probe = setInterval(function () {
        fetch('/result?sessionId=' + encodeURIComponent(mark.sid))
            .then(function (r) { return r.text(); })
            .then(function (t) {
                if (t.indexOf('任务不存在') >= 0) { clearMark(); stopAll(); if (!finished && pill) pill.remove(); return; }
                //兜底：既不是"正在跑"、也不是"已出结果" → 任务已没（重启/失效），直接收起
                var running = t.indexOf('还在执行中') >= 0 || t.indexOf('正在生成') >= 0 || t.indexOf('等待生成') >= 0;
                if (!running && (t || '').length < 200) { clearMark(); stopAll(); if (!finished && pill) pill.remove(); return; }
                if (!finished && t && t.length > 200 && t.indexOf('正在生成') < 0 && t.indexOf('等待生成') < 0) {
                    finished = true; pct = 100; ceiling = 100; clearMark(); stopAll(); render();
                }
            })
            .catch(function () { });
    }, 5000);
    function stopAll() {
        try { clearInterval(evTimer); } catch (e) { }
        try { clearInterval(clock); } catch (e) { }
        try { clearInterval(probe); } catch (e) { }
    }
    render();
})();
