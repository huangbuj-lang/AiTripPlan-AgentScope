/** admin.js 管理控制台全部前端逻辑。 */

let currentTab = 'status';
let statusTimer = null, tasksTimer = null;

/* ---------- 基础工具 ---------- */
function esc(s) {
    if (s === null || s === undefined) return '';
    return String(s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function toast(msg, isErr) {
    let t = document.getElementById('toast');
    if (!t) { t = document.createElement('div'); t.id = 'toast'; t.className = 'toast'; document.body.appendChild(t); }
    t.textContent = msg;
    t.className = 'toast show' + (isErr ? ' err' : '');
    setTimeout(() => { t.className = 'toast'; }, 2600);
}
function fmtDur(ms) {
    if (ms == null || ms < 0) return '—';
    if (ms < 1000) return ms + 'ms';
    const s = Math.round(ms / 1000);
    if (s < 60) return s + 's';
    return Math.floor(s / 60) + 'm' + (s % 60) + 's';
}
function fmtTime(ms) {
    if (!ms) return '—';
    const d = new Date(ms);
    const p = n => String(n).padStart(2, '0');
    return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
}

/* 骨架屏（等待逻辑第一层：数据未到时占位） */
function skeleton(n = 3) {
    let rows = '';
    for (let i = 0; i < n; i++) rows += '<div class="sk-row w' + [80, 60, 40][i % 3] + '"></div>';
    return '<div class="skeleton">' + rows + '</div>';
}
/* 错误状态盒（等待逻辑终点：失败可重试，不静默吞掉） */
function errBox(containerId, msg, retryFnName) {
    const el = document.getElementById(containerId);
    if (!el) return;
    el.innerHTML = '<div class="state-box err"><div class="big">⚠️</div>' + esc(msg || '网络异常，加载失败') +
        '<br><button class="btn btn-ghost btn-xs retry-btn" onclick="' + retryFnName + '()">重试</button></div>';
}
/* fetch JSON 包装：非 2xx / 断网统一抛错 */
function fetchJson(url, opts) {
    return fetch(url, opts).then(r => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
    });
}

/* 按钮忙碌态（点击逻辑核心）：进入时记住原文案，完成后恢复 */
function btnBusy(btn, busyText) {
    if (!btn) return () => {};
    const original = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span class="spin"></span> ' + esc(busyText || '处理中...');
    return () => { btn.disabled = false; btn.innerHTML = original; };
}

/* ============ 登录校验 ============ */
fetch('/api/check-login').then(r => r.json()).then(d => {
    if (!d.success) { location.href = 'login.html'; return; }
    document.getElementById('currentUser').textContent = d.username + (d.role === 'admin' ? ' · 管理员' : '');
    if (d.role !== 'admin') {
        document.querySelector('.content').innerHTML =
            '<section class="panel"><div class="state-box"><div class="big">🔒</div>无管理员权限，仅 admin 角色可访问本页。</div></section>';
        return;
    }
    switchTab('status');
}).catch(() => location.href = 'login.html');

function logout() {
    fetch('/api/logout', { method: 'POST' }).then(() => location.href = 'login.html');
}

/* ============ 页签切换（点击逻辑：切页即拉数据，观测页启动轮询） ============ */
function switchTab(tab) {
    currentTab = tab;
    document.querySelectorAll('.nav-item').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
    document.querySelectorAll('.panel').forEach(p => p.classList.add('hidden'));
    document.getElementById('panel-' + tab).classList.remove('hidden');
    stopStatusTimer(); stopTasksTimer();
    if (tab === 'status')      { loadStatus(); startStatusTimer(); }
    else if (tab === 'tasks')  { loadTasks(); startTasksTimer(); }
    else if (tab === 'agents') { loadAgentsHealth(); }
    else if (tab === 'breaker'){ loadBreaker(); loadCosts(); }
    else if (tab === 'data')   { loadData(); }
    else if (tab === 'moderation') loadModeration();
    else if (tab === 'business')   loadBusiness();
    else if (tab === 'announce')   loadAnnouncements();
    else if (tab === 'center')     loadDataCenter();
}
function startStatusTimer() { if (!statusTimer) statusTimer = setInterval(loadStatus, 5000); }
function stopStatusTimer() { if (statusTimer) { clearInterval(statusTimer); statusTimer = null; } }
function startTasksTimer() { if (!tasksTimer) tasksTimer = setInterval(loadTasks, 3000); }
function stopTasksTimer() { if (tasksTimer) { clearInterval(tasksTimer); tasksTimer = null; } }

/* ============ 系统脉搏条（签名元素：四节点实时状态） ============ */
function setPulse(id, cls, valueHtml) {
    const light = document.getElementById('pl-' + id);
    const val = document.getElementById('pv-' + id);
    if (!light) return;
    light.className = 'pulse-light ' + cls;
    if (val && valueHtml !== undefined) val.innerHTML = valueHtml;
}
function renderPulseFromStatus(s) {
    // 主管
    setPulse('manager', 'ok breathe', '运行中<small>:9091</small>');
    // Nacos
    setPulse('nacos', s.nacos.connected ? 'ok' : 'bad', s.nacos.connected ? '已连接' : '断开');
}
function renderPulseFromAgents(agents) {
    agents.forEach(a => {
        const id = a.name === 'RouteMakingAgent' ? 'route' : 'trip';
        const cls = !a.alive ? 'bad' : (a.light === 'yellow' ? 'warn' : (a.light === 'gray' ? 'idle' : 'ok'));
        let txt;
        if (!a.alive) txt = '探活不通';
        else if (a.a2aCalls > 0) txt = a.a2aSuccessRate + '%<small>' + a.a2aCalls + '次调用</small>';
        else txt = '存活<small>未被调用</small>';
        setPulse(id, cls, txt);
    });
}

/* 指标卡溢出跑马灯：数据左靠齐单行不换行，内容装不下时注入右→左无缝循环动画（两段等宽副本 -50% 位移） */
function applyCardMarquee(root) {
    if (!root) return;
    root.querySelectorAll('.metric .num, .metric .label').forEach(el => {
        if (el.dataset.mqDone || el.scrollWidth <= el.clientWidth + 1) return;
        el.dataset.mqDone = '1';
        const t = el.textContent;
        el.textContent = '';
        const tr = document.createElement('div');
        tr.className = 'mq-track';
        [0, 1].forEach(() => {
            const s = document.createElement('span');
            s.textContent = t;
            tr.appendChild(s);
        });
        el.appendChild(tr);
    });
}

/* ============ 系统状态 ============ */
function loadStatus(btn) {
    const done = btnBusy(btn, '刷新中...');
    fetchJson('/api/admin/status').then(d => {
        done();
        const b = document.getElementById('statusBody');
        if (!d.success) { b.innerHTML = '<div class="state-box">加载失败</div>'; return; }
        if (btn) toast('系统状态已刷新');
        renderPulseFromStatus(d);
        const nacosBadge = d.nacos.connected ? '<span class="badge ok">已连接</span>' : '<span class="badge bad">未连接</span>';
        let agentsHtml = d.agents.map(a => {
            const badge = a.registered ? '<span class="badge ok">已注册</span>' : '<span class="badge bad">离线</span>';
            return '<div class="card"><h3><span class="dot ' + (a.registered ? 'ok' : 'bad') + '"></span>' + esc(a.name) + '</h3>' +
                '<div class="kv"><span>端口</span><b class="num-cell">' + a.port + '</b></div>' +
                '<div class="kv"><span>Nacos 注册</span><b>' + badge + '</b></div></div>';
        }).join('');
        b.innerHTML =
            '<div class="card-grid cols-auto">' +
            '<div class="card"><h3>🧭 ManagerAgent</h3>' +
            '<div class="kv"><span>状态</span><b><span class="badge ok">运行中</span></b></div>' +
            '<div class="kv"><span>端口</span><b class="num-cell">' + d.manager.port + '</b></div></div>' +
            '<div class="card"><h3>🔗 Nacos 注册中心</h3>' +
            '<div class="kv"><span>地址</span><b>' + esc(d.nacos.addr) + '</b></div>' +
            '<div class="kv"><span>连接</span><b>' + nacosBadge + '</b></div>' +
            (d.nacos.error ? '<div class="kv"><span>错误</span><b style="color:#F87171">' + esc(d.nacos.error) + '</b></div>' : '') +
            '</div>' + agentsHtml + '</div>';
        // 页签小圆点：Nacos 断开或任一 Agent 离线时点亮
        const dotEl = document.getElementById('dot-status');
        if (dotEl) dotEl.classList.toggle('on', !d.nacos.connected || d.agents.some(a => !a.registered));
    }).catch(() => { done(); errBox('statusBody', '系统状态加载失败', 'loadStatus'); });
}

/* ============ 任务监控 ============ */
const TASK_BADGE = {
    DONE: '<span class="badge ok">成功</span>',
    FAILED: '<span class="badge bad">失败</span>',
    CANCELLED: '<span class="badge warn">已中断</span>'
};
function loadTasks(btn) {
    const done = btnBusy(btn, '刷新中...');
    fetchJson('/api/admin/tasks').then(d => {
        done();
        // 汇总指标卡
        const s = document.getElementById('taskSummary');
        s.innerHTML =
            '<div class="card metric"><div class="num teal">' + d.inUse + '/' + d.maxConcurrent + '</div><div class="label">并发槽占用</div></div>' +
            '<div class="card metric"><div class="num">' + d.sessions.filter(x => x.running).length + '</div><div class="label">运行中任务</div></div>' +
            '<div class="card metric"><div class="num teal">' + (d.countDone || 0) + '</div><div class="label">已完成（本次进程运行）</div></div>' +
            '<div class="card metric"><div class="num' + ((d.countFailed || 0) > 0 ? ' red' : '') + '">' + (d.countFailed || 0) + '</div><div class="label">失败次数</div></div>' +
            '<div class="card metric"><div class="num' + ((d.failRate || 0) > 20 ? ' red' : ' teal') + '">' + (d.failRate || 0) + '%</div><div class="label">失败率</div></div>';
        applyCardMarquee(s);

        // 运行中任务表（当前步骤列是核心：回答"它卡在哪了"）
        const b = document.getElementById('taskBody');
        const running = (d.sessions || []).filter(x => x.running);
        const runCnt = document.getElementById('runningCnt');
        if (runCnt) runCnt.textContent = running.length ? running.length + ' 个' : '';
        b.innerHTML = running.length
            ? '<div class="table-wrap"><table><thead><tr><th>会话ID</th><th>当前步骤</th><th>LLM轮数</th><th>提交时间</th><th>操作</th></tr></thead><tbody>' +
              running.map(x => '<tr><td class="mono">' + esc(x.sessionId) + '</td>' +
                '<td><b>' + esc(x.currentStep || '—') + '</b></td>' +
                '<td class="num-cell">' + (x.modelCallsUsed != null && x.modelCallsUsed >= 0 ? x.modelCallsUsed : '—') + '</td>' +
                '<td class="num-cell">' + fmtTime(x.submitMs) + '</td>' +
                '<td><button class="btn btn-danger-o btn-xs" onclick="forceCancel(\'' + esc(x.sessionId) + '\')">强制中断</button></td></tr>').join('') +
              '</tbody></table></div>'
            : '<div class="state-box"><div class="big">🌙</div>暂无运行中任务<br><span style="font-size:12px">用户从前台提交规划后会出现在这里，每 3 秒自动更新步骤</span></div>';
        // 页签圆点：有运行中任务或高失败率时提示
        const dotEl = document.getElementById('dot-tasks');
        if (dotEl) dotEl.classList.toggle('on', running.length > 0 || (d.failRate || 0) > 20);

        // 历史任务表
        const h = document.getElementById('taskHistoryBody');
        const hist = d.history || [];
        h.innerHTML = hist.length
            ? '<table><thead><tr><th>提交时间</th><th>会话ID</th><th>用户</th><th>状态</th><th>耗时</th><th title="本任务消耗的模型调用次数（每次ReAct思考轮=1次）">LLM轮数</th><th>最后步骤</th></tr></thead><tbody>' +
              hist.map(x => '<tr style="cursor:pointer" onclick="showTrace(\'' + esc(x.sessionId) + '\')">' +
                '<td class="num-cell">' + fmtTime(x.submitMs) + '</td>' +
                '<td class="mono">' + esc(x.sessionId) + '</td>' +
                '<td class="num-cell">' + (x.userId > 0 ? x.userId : '—') + '</td>' +
                '<td>' + (TASK_BADGE[x.status] || esc(x.status)) + '</td>' +
                '<td class="num-cell">' + fmtDur(x.durationMs) + '</td>' +
                '<td class="num-cell">' + (x.modelCallsUsed != null && x.modelCallsUsed >= 0 ? x.modelCallsUsed : '—') + '</td>' +
                '<td>' + esc(x.currentStep || '—') + '</td></tr>').join('') +
              '</tbody></table>'
            : '<div class="state-box"><div class="big">📭</div>暂无历史任务<br><span style="font-size:12px">任务结束后自动归档到这里</span></div>';
        if (btn) toast('任务监控已刷新');
    }).catch(() => { done(); errBox('taskHistoryBody', '任务监控加载失败', 'loadTasks'); });
}

function forceCancel(sessionId) {
    if (!confirm('确认强制中断会话 ' + sessionId + ' ？')) return;
    fetch('/api/admin/tasks/cancel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'sessionId=' + encodeURIComponent(sessionId)
    }).then(r => r.json()).then(d => { toast(d.message, !d.success); loadTasks(); })
      .catch(() => toast('请求失败', true));
}

/* ============ 子Agent健康 ============ */
function loadAgentsHealth(btn) {
    const done = btnBusy(btn, '刷新中...');
    // ★两请求并行：健康状态 + 工具统计一次拿齐后同帧渲染（原 tools 二次插入导致刷新时卡片先矮后高、页面抖动）
    //   另并行取 /costs 的缓存命中统计（主管进程 PlanCache）→ 并进主管卡展示
    Promise.all([
        fetchJson('/api/admin/agents/health'),
        fetchJson('/api/admin/tools').catch(() => null),
        fetchJson('/api/admin/costs').catch(() => null)
    ]).then(([d, td, cd]) => {
        done();
        const b = document.getElementById('agentsHealthBody');
        const el = document.getElementById('agentsRefreshTime');
        if (el) el.textContent = '检测于 ' + new Date().toLocaleTimeString();
        if (!d.success) { b.innerHTML = '<div class="state-box">加载失败</div>'; return; }
        renderPulseFromAgents(d.agents);
        const lightDot = l => ({ green: '🟢', yellow: '🟡', red: '🔴', gray: '⚪' }[l] || '⚪');
        const lightText = { green: '正常', yellow: '异常', red: '探活不通', gray: '未被调用' };
        const lightBadge = l => ({ green: 'ok', yellow: 'warn', red: 'bad', gray: 'plain' }[l] || 'plain');
        const isMgr = a => a.label === '主管Agent';
        b.innerHTML = d.agents.map(a =>
            '<div class="card" data-agent="' + esc(a.label) + '"><h3>' + lightDot(a.light) + ' ' + esc(a.label) +
            ' <span style="color:var(--faint);font-size:12px;font-weight:400">:' + a.port + '</span>' +
            '<span class="badge ' + lightBadge(a.light) + '" style="margin-left:auto">' + (lightText[a.light] || a.light) + '</span></h3>' +
            '<div class="kv"><span>进程存活</span><b>' + (a.alive ? '✅ 是' : '❌ 否') + '</b></div>' +
            '<div class="kv"><span>已运行</span><b class="num-cell">' + (a.uptimeMs >= 0 ? fmtDur(a.uptimeMs) : '—') + '</b></div>' +
            '<div class="kv"><span>A2A 调用次数</span><b class="num-cell">' + a.a2aCalls + '</b></div>' +
            '<div class="kv"><span>失败次数</span><b class="num-cell">' + a.a2aFails + '</b></div>' +
            '<div class="kv"><span>成功率</span><b class="num-cell">' + (a.a2aSuccessRate < 0 ? '—' : a.a2aSuccessRate + '%') + '</b></div>' +
            '<div class="kv"><span>平均耗时</span><b class="num-cell">' + (a.a2aAvgCostMs < 0 ? '—' : fmtDur(a.a2aAvgCostMs)) + '</b></div>' +
            (isMgr(a)
                ? '<div class="kv"><span>模型调用次数</span><b class="num-cell">' + (a.modelCalls >= 0 ? a.modelCalls : '—') + '</b></div>' +
                  '<div class="kv"><span>在途并发</span><b class="num-cell">' + a.inflightActive + '/' + a.maxInflight + '</b></div>' +
                  '<div class="kv"><span>熔断器</span><b style="color:' + (a.breakerOpen ? '#F87171' : 'var(--ok)') + '">' + (a.breakerOpen ? '已熔断' : '正常') + '</b></div>' +
                  '<div class="kv"><span>QPS 限速</span><b class="num-cell">' + a.qps + '/s</b></div>' +
                  '<div class="kv"><span>Token 消耗</span><b class="num-cell">入' + (a.tokensIn / 10000).toFixed(1) + '万 · 出' + (a.tokensOut / 10000).toFixed(1) + '万</b></div>' +
                  (cd && cd.cache ? '<div class="kv"><span>结果缓存命中率</span><b style="color:var(--teal)" title="命中 ' + (cd.cache.hits || 0) + ' 次 · 未中 ' + (cd.cache.misses || 0) + ' 次 · 存量 ' + (cd.cache.size || 0) + ' 条（进程内存，重启清零）">' + (cd.cache.hitRate != null ? cd.cache.hitRate : 0) + '%</b></div>' : '')
                : '<div class="kv"><span>模型调用次数</span><b class="num-cell">' + (a.modelCalls >= 0 ? a.modelCalls : '—') + '</b></div>') +
            (!isMgr(a) ? lkBlock(a) : '') + '</div>'
        ).join('');
        // ★工具层细粒度成功率：td 已与主数据并行取回，同步注入（同帧渲染）
        if (td) {
            (td.agents || []).forEach(ta => {
                if (!ta.alive) return;
                const card = b.querySelector('[data-agent="' + CSS.escape(ta.label) + '"]');
                if (!card) return;
                const rateColor = r => r < 0 ? 'var(--muted)' : (r < 90 ? 'var(--amber)' : 'var(--ok)');
                let html = '<table class="mini-tbl">'; /* 明细直接平铺展示（原折叠块已按需求移除） */
                const row = (n, c, r, mono) =>
                    '<tr><td' + (mono ? ' class="mono"' : '') + '>' + n + '</td><td class="num-cell">' + c + '</td>' +
                    '<td class="num-cell" style="color:' + rateColor(r) + '">' + (r < 0 ? '—' : r + '%') + '</td></tr>';
                html += row('综合工具', ta.toolCalls, ta.toolRate, false);
                html += row('知识库查询', ta.lkCalls, ta.lkRate, false);
                html += row('MCP 合计', ta.mcpCalls, ta.mcpRate, false);
                (ta.mcpByName || []).forEach(t => { html += row(esc(t.tool), t.calls, t.rate, true); });
                html += '</table>';
                card.insertAdjacentHTML('beforeend', html);
            });
        }
        // 页签圆点：有不健康 Agent 时提示
        const dotEl = document.getElementById('dot-agents');
        if (dotEl) dotEl.classList.toggle('on', d.agents.some(a => !a.alive || a.light === 'yellow'));
        if (btn) toast('Agent健康已刷新');
    }).catch(() => { done(); errBox('agentsHealthBody', '健康检测失败', 'loadAgentsHealth'); });
}


/* 子Agent工具统计块：综合工具(query_trip/query_route_unified) + 本地知识库 */
function lkBlock(a) {
    let out = '';
    if (a.toolCalls != null) {
        const r = a.toolCalls > 0 ? Math.round((a.toolCalls - a.toolFails) * 1000.0 / a.toolCalls) / 10.0 : -1;
        out += '<div class="kv"><span>综合工具调用</span><b class="num-cell" style="color:' +
            (r < 0 ? 'var(--muted)' : (r < 90 ? 'var(--amber)' : 'var(--ok)')) + '">' + a.toolCalls + '次 ' + (r < 0 ? '—' : r + '%') +
            (a.toolAvgMs >= 0 ? ' · 均' + fmtDur(a.toolAvgMs) : '') + '</b></div>';
    }
    if (a.lkCalls != null) {
        const rate = a.lkCalls > 0 ? Math.round((a.lkCalls - a.lkFails) * 1000.0 / a.lkCalls) / 10.0 : -1;
        out += '<div class="kv"><span>知识库查询</span><b class="num-cell" style="color:' +
            (rate < 0 ? 'var(--muted)' : (rate < 90 ? 'var(--amber)' : 'var(--ok)')) + '">' + a.lkCalls + '次 ' + (rate < 0 ? '—' : rate + '%') +
            (a.lkAvgMs >= 0 ? ' · 均' + fmtDur(a.lkAvgMs) : '') + '</b></div>';
    }
    return out;
}

/* ============ 模型监控 ============ */
function loadBreaker(btn) {
    const done = btnBusy(btn, '刷新中...');
    const b = document.getElementById('breakerBody');
    fetchJson('/api/admin/breaker').then(d => {
        loadModelConfig();
        loadAgentConfig();
        loadAmapCredentials();
        loadAgentPrompts();
        document.getElementById('limQps').value = d.maxQps || 3;
        document.getElementById('limMaxInflight').value = d.maxInflight || 6;
        document.getElementById('limRetries').value = d.maxRetries || 3;
        document.getElementById('limRetryBaseMs').value = d.retryBaseMs || 1000;
        document.getElementById('limBreakerThreshold').value = d.breakerFailureThreshold || 5;
        document.getElementById('limBreakerWindow').value = d.breakerWindowMs || 10000;
        document.getElementById('limBreakerCoolDown').value = d.breakerCoolDownMs || 60000;
        const stateCn = { CLOSED: '正常（CLOSED）', OPEN: '熔断中（OPEN）', HALF_OPEN: '半开试探（HALF_OPEN）' }[d.state] || d.state;
        b.innerHTML =
            '<div class="breaker-banner ' + d.state + '">熔断器状态：<b>' + stateCn + '</b></div>' +
            '<div class="card-grid cols-3">' +
            '<div class="card metric"><div class="num' + (d.consecutiveFailures > 0 ? ' red' : ' teal') + '">' + d.consecutiveFailures + '</div><div class="label">窗口内失败次数</div></div>' +
            '<div class="card metric"><div class="num">' + d.maxQps + '</div><div class="label">限速 QPS</div></div>' +
            '<div class="card metric"><div class="num' + (d.inflightActive >= d.maxInflight ? ' red' : '') + '">' + d.inflightActive + '/' + d.maxInflight + '</div><div class="label">模型在途/上限</div></div>' +
            '<div class="card metric"><div class="num">' + d.maxRetries + '</div><div class="label">最大重试次数</div></div>' +
            '<div class="card metric"><div class="num teal model-name">' + esc(d.primaryModel) + '</div><div class="label">主模型</div></div>' +
            '<div class="card metric"><div class="num model-name">' + esc(d.fallbackModel) + '</div><div class="label">备用模型（熔断降级）</div></div>' +            '</div>';
        const dotEl = document.getElementById('dot-breaker');
        if (dotEl) dotEl.classList.toggle('on', d.state !== 'CLOSED');
        applyCardMarquee(b);
        done();
        if (btn) toast('模型配置已刷新');
    }).catch(() => { done(); errBox('breakerBody', '熔断器状态加载失败', 'loadBreaker'); });
}

/* ============ LLM 成本观测 ============ */
function fmtTok(n) {
    if (n == null) return '—';
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
    return String(n);
}
function loadCosts() {
    fetchJson('/api/admin/costs').then(d => {
        // ★结果缓存命中率已并入「Agent健康-主管Agent卡」（进程级指标），模型监控不再单独设卡
        const sum = document.getElementById('costsSummary');
        if (sum) sum.innerHTML = '';
        // ★维度表固定显示全部已知维度（任务/管线/论坛AI + 后端实际返回的子Agent行），无数据补 0，不再空缺
        // ★数据管线是py侧独立模型处理，不经主管，不在本表展示
        const FIXED_DIMS = [['任务(用户行程)', 'task'], ['论坛AI答主', 'forum']];
        const dims = d.dimensions || [];
        const byName = {};
        dims.forEach(x => { byName[x.dimension] = x; });
        const rows = FIXED_DIMS.map(fd => byName[fd[1]] || { dimension: fd[0], calls: 0, inputTokens: 0, outputTokens: 0 });
        dims.forEach(x => { if (x.dimension !== 'pipeline' && !FIXED_DIMS.some(fd => fd[1] === x.dimension)) rows.push(x); });
        document.getElementById('costsDims').innerHTML =
            '<table><thead><tr><th>维度</th><th>调用次数</th><th>输入 tokens</th><th>输出 tokens</th></tr></thead><tbody>' +
            rows.map(x => '<tr><td>' + esc(x.dimension) + '</td><td class="num-cell">' + x.calls + '</td>' +
                '<td class="num-cell">' + fmtTok(x.inputTokens) + '</td><td class="num-cell">' + fmtTok(x.outputTokens) + '</td></tr>').join('') +
            '</tbody></table>';
        // ★近7日成本趋势（DB 落库历史）
        const trend = d.trend || [];
        const trendEl = document.getElementById('costsTrend');
        if (trendEl) {
            trendEl.innerHTML = trend.length
                ? '<table><thead><tr><th>日期</th><th>调用次数</th><th>输入 tokens</th><th>输出 tokens</th></tr></thead><tbody>' +
                  trend.map(x => '<tr><td class="num-cell">' + esc(x.day) + '</td><td class="num-cell">' + x.calls + '</td>' +
                      '<td class="num-cell">' + fmtTok(x.inputTokens) + '</td><td class="num-cell">' + fmtTok(x.outputTokens) + '</td></tr>').join('') +
                  '</tbody></table>'
                : '<table><thead><tr><th>日期</th><th>调用次数</th><th>输入 tokens</th><th>输出 tokens</th></tr></thead><tbody><tr><td class="num-cell">—</td><td class="num-cell">0</td><td class="num-cell">—</td><td class="num-cell">—</td></tr></tbody></table>';
        }
    }).catch(() => {});
}

/* ============ 任务调用链明细（统一五列表格 + 层级展开） ============ */
const TRACE_LAYER_ICON = { A2A: '🔗', MCP: '🗺️', LLM: '🧠', TOOL: '🛠️' };

/* 三态结果徽标 */
function traceBadge(stat) {
    if (stat === 'ok') return '<span class="badge ok">成功</span>';
    if (stat === 'warn') return '<span class="badge warn">警告</span>';
    return '<span class="badge bad">失败</span>';
}

/* 叶子状态：出现失败尝试即红 */
function leafState(s) {
    const atts = s.attempts || [];
    if (atts.length === 0) return s.success ? 'ok' : 'bad';
    return atts.some(a => !a.ok) ? 'bad' : 'ok';
}

/* 聚合状态：全成功绿 / 有失败有成功黄 / 全失败红 */
function aggState(items) {
    const st = items.map(i => i.stat);
    if (st.every(x => x === 'ok')) return 'ok';
    if (st.every(x => x === 'bad')) return 'bad';
    return 'warn';
}

/* ★尝试折叠成说明文字：第1次调用失败(原因)/第2次调用成功 */
function attemptsText(atts) {
    if (!atts || !atts.length) return '';
    return atts.map(a => {
        const n = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十'][(a.n || 1) - 1] || a.n;
        const reason = (a.reason || '').trim();
        return a.ok
            ? ('第' + n + '次调用成功')
            : ('第' + n + '次调用失败' + (reason && reason !== '—' ? '(' + reason + ')' : ''));
    }).join('/');
}

/* 叶子行 */
function leafRow(s) {
    const state = leafState(s);
    const detail = s.detail || '';
    const attText = attemptsText(s.attempts);
    const fullDetail = attText ? (detail ? detail + '（' + attText + '）' : attText) : detail;
    return '<tr class="trace-leaf" data-d="2"' + (state === 'bad' ? ' data-fail="1"' : '') + '>' +
        '<td class="tc-time">' + fmtTime(s.ts) + '</td>' +
        '<td class="tc-name">' + (TRACE_LAYER_ICON[s.layer] || '') + ' ' + esc(s.name) + '</td>' +
        '<td class="tc-result">' + traceBadge(state) + '</td>' +
        '<td class="tc-cost">' + (s.costMs >= 0 ? fmtDur(s.costMs) : '—') + '</td>' +
        '<td class="tc-detail">' + esc(fullDetail || '—') + '</td></tr>';
}

/* 二级行（LLM调用/综合工具/知识库工具）：五列 + 可展开 */
function groupRow(g, idx) {
    const sub = g.spans.map(sp => ({ stat: leafState(sp) }));
    const stat = aggState(sub);
    const total = g.spans.reduce((a, x) => a + Math.max(0, x.costMs), 0);
    const desc = g.desc || g.spans.map(sp => sp.name).join('/');
    return '<tr class="trace-group" data-d="1" data-idx="' + idx + '"' + (stat === 'bad' ? ' data-fail="1"' : (stat === 'warn' ? ' data-warn="1"' : '')) + '>' +
        '<td class="tc-time">—</td>' +
        '<td class="tc-name"><span class="tw">▸</span> ' + esc(g.label) + '</td>' +
        '<td class="tc-result">' + traceBadge(stat) + '</td>' +
        '<td class="tc-cost">' + fmtDur(total) + '</td>' +
        '<td class="tc-detail">' + esc(desc) + '</td></tr>' +
        g.spans.map(leafRow).join('');
}

/* 一级行（主管Agent/A2A/行程Agent/路线Agent）：五列 + 可展开 */
function agentRow(node, idx) {
    const all = node.groups.flatMap(g => g.spans);
    const stat = aggState(node.groups.map(g => ({ stat: aggState(g.spans.map(sp => ({ stat: leafState(sp) }))) })));
    const total = all.reduce((a, x) => a + Math.max(0, x.costMs), 0);
    const fails = all.filter(x => !x.success).length;
    const desc = node.desc || node.groups.map(g => g.label).join('/');
    return '<tr class="trace-agent" data-d="0" data-idx="' + idx + '"' + (stat === 'bad' ? ' data-fail="1"' : (stat === 'warn' ? ' data-warn="1"' : '')) + '>' +
        '<td class="tc-time">—</td>' +
        '<td class="tc-name"><span class="tw">▸</span> ' + esc(node.label) +
        (fails > 0 ? ' <span class="tc-failcnt">' + fails + '失败</span>' : '') + '</td>' +
        '<td class="tc-result">' + traceBadge(stat) + '</td>' +
        '<td class="tc-cost">' + fmtDur(total) + '</td>' +
        '<td class="tc-detail">' + esc(desc) + '</td></tr>' +
        node.groups.map(groupRow).join('');
}

function showTrace(sessionId) {
    const fold = document.getElementById('traceFold');
    fold.open = true;
    document.getElementById('traceSessionId').textContent = sessionId;
    const b = document.getElementById('traceBody');
    b.innerHTML = '<div class="sk-row w80"></div><div class="sk-row w60"></div>';
    fetchJson('/api/admin/tasks/trace?sessionId=' + encodeURIComponent(sessionId)).then(d => {
        const KEY_LAYERS = { LLM: 1, A2A: 1, TOOL: 1 };
        const spans = (d.spans || []).filter(s => KEY_LAYERS[s.layer]);
        if (!spans.length) {
            b.innerHTML = '<div style="color:var(--faint);font-size:13px;padding:10px">该任务没有记录到调用明细（较早的任务或子进程未上报）</div>';
            return;
        }
        const buckets = { '': [], '行程': [], '路线': [] };
        spans.forEach(sp => { (buckets[sp.agent || ''] || buckets['']).push(sp); });
        const nodes = [];
        // 主管Agent → LLM调用
        const mgrLlm = buckets[''].filter(x => x.layer === 'LLM');
        if (mgrLlm.length) {
            nodes.push({
                label: '主管Agent', desc: 'LLM调用',
                groups: [{
                    label: 'LLM调用',
                    desc: mgrLlm.map(x => (x.detail || '').split(' ')[0] || '模型调用').filter((v, i, a) => a.indexOf(v) === i).join('/'),
                    spans: mgrLlm
                }]
            });
        }
        // A2A
        const a2a = buckets[''].filter(x => x.layer === 'A2A');
        if (a2a.length) {
            nodes.push({ label: 'A2A', desc: a2a.map(x => x.name).join('/'), groups: [{ label: '子Agent调用', desc: a2a.map(x => x.name).join('/'), spans: a2a }] });
        }
        // 行程/路线：LLM调用/综合工具/知识库工具
        ['行程', '路线'].forEach(ag => {
            const list = buckets[ag] || [];
            if (!list.length) return;
            const gs = [];
            const llms = list.filter(x => x.layer === 'LLM');
            if (llms.length) gs.push({ label: 'LLM调用', desc: '模型调用', spans: llms });
            const tools = list.filter(x => x.layer === 'TOOL' && (x.name === 'query_trip' || x.name === 'query_route_unified'));
            if (tools.length) gs.push({ label: '综合工具', desc: '综合工具调用', spans: tools });
            const lk = list.filter(x => x.layer === 'TOOL' && x.name === 'query_local_knowledge');
            if (lk.length) gs.push({ label: '知识库工具', desc: '知识库调用', spans: lk });
            if (gs.length) nodes.push({ label: ag + 'Agent', desc: gs.map(g => g.label).join('/'), groups: gs });
        });
        // 渲染统一五列表格
        let html = '<div class="trace-tree"><table class="trace-tbl"><thead><tr><th>时间</th><th>名称</th><th>结果</th><th>耗时</th><th>说明</th></tr></thead><tbody>';
        nodes.forEach((n, i) => { html += agentRow(n, i); });
        html += '</tbody></table></div>';
        b.innerHTML = html;
        // ★标准树折叠：初始全部展开；
        b.querySelectorAll('tr.trace-agent, tr.trace-group').forEach(tr => {
            tr.classList.add('open'); // 初始展开态
            tr.addEventListener('click', () => {
                const d = parseInt(tr.dataset.d, 10);
                const willCollapse = tr.classList.contains('open');
                let cur = tr.nextElementSibling;
                while (cur && parseInt(cur.dataset.d, 10) > d) {
                    if (willCollapse) {
                        cur.style.display = 'none'; // 收起：隐藏所有后代
                    } else {
                        const cd = parseInt(cur.dataset.d, 10);
                        if (cd === d + 1) {
                            cur.style.display = ''; // 展开：只显示直接子级
                            cur.classList.remove('open'); // 子级重置为收起态
                        }
                    }
                    cur = cur.nextElementSibling;
                }
                tr.classList.toggle('open', !willCollapse);
            });
        });
    }).catch(() => { b.innerHTML = '<div style="color:#C97A7C;font-size:13px;padding:10px">调用链加载失败，请重试</div>'; });
}

/* ============ 数据管理 ============ */
function loadData(btn) {
    const done = btnBusy(btn, '刷新中...');
    Promise.allSettled([loadUsers(), loadPlans()]).then(() => { done(); if (btn) toast('数据管理已刷新'); });
}

function loadUsers() {
    return fetchJson('/api/admin/users').then(d => {
        const b = document.getElementById('usersBody');
        if (!d.users.length) { b.innerHTML = '<div class="state-box"><div class="big">👤</div>暂无用户</div>'; return; }
        const rows = d.users.map(u => {
            const roleBadge = u.role === 'admin' ? '<span class="badge warn">admin</span>' : '<span class="badge plain">user</span>';
            return '<tr><td class="num-cell">' + u.id + '</td><td>' + esc(u.username) + '</td><td>' + roleBadge + '</td>' +
                '<td><button class="btn btn-danger-o btn-xs" onclick="deleteUser(' + u.id + ',\'' + esc(u.username) + '\')">删除</button></td></tr>';
        }).join('');
        b.innerHTML = '<table><thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>操作</th></tr></thead><tbody>' + rows + '</tbody></table>';
    }).catch(() => errBox('usersBody', '用户列表加载失败', 'loadUsers'));
}

function loadPlans() {
    return fetchJson('/api/admin/plans').then(d => {
        const b = document.getElementById('plansBody');
        if (!d.plans.length) { b.innerHTML = '<div class="state-box"><div class="big">🗺️</div>暂无行程</div>'; return; }
        const rows = d.plans.map(p =>
            '<tr><td class="num-cell">' + p.id + '</td><td>' + (p.username ? esc(p.username) : '—') + '</td>' +
            '<td>' + (p.city ? esc(p.city) : '—') + '</td><td class="num-cell">' + (p.days != null ? p.days : '—') + '</td>' +
            '<td style="max-width:340px;white-space:normal;word-break:break-word;color:var(--muted);font-size:12.5px">' + esc(p.preview) + '</td>' +
            '<td><button class="btn btn-danger-o btn-xs" onclick="deletePlan(' + p.id + ')">删除</button></td></tr>').join('');
        b.innerHTML = '<table><thead><tr><th>ID</th><th>用户</th><th>城市</th><th>天数</th><th>内容预览</th><th>操作</th></tr></thead><tbody>' + rows + '</tbody></table>';
    }).catch(() => errBox('plansBody', '行程列表加载失败', 'loadPlans'));
}

function deleteUser(id, name) {
    if (!confirm('确认删除用户「' + name + '」（含其名下全部行程）？此操作不可恢复。')) return;
    fetch('/api/admin/users?id=' + id, { method: 'DELETE' })
        .then(r => r.json()).then(d => { toast(d.message, !d.success); loadUsers(); loadPlans(); })
        .catch(() => toast('请求失败', true));
}
function deletePlan(id) {
    if (!confirm('确认删除行程 #' + id + ' ？此操作不可恢复。')) return;
    fetch('/api/admin/plans?id=' + id, { method: 'DELETE' })
        .then(r => r.json()).then(d => { toast(d.message, !d.success); loadPlans(); })
        .catch(() => toast('请求失败', true));
}

/* ============ 内容审核 ============ */
function loadModeration(btn) {
    const done = btnBusy(btn, '刷新中...');
    const b = document.getElementById('moderationBody');
    fetchJson('/api/admin/moderation/queue').then(d => {
        done();
        if (!d.success) { b.innerHTML = '<div class="state-box">加载失败</div>'; return; }
        const typeCn = t => t === 1 ? '帖子' : '评论';
        const reportsHtml = (d.reports || []).map(x =>
            '<tr><td>#' + x.id + '</td><td>' + esc(x.reporter || '—') + '</td><td>' + typeCn(x.target_type) + ' #' + x.target_id + '</td>' +
            '<td>' + esc(x.reason) + '</td><td style="white-space:nowrap">' +
            '<button class="btn btn-warn-o btn-xs" onclick="resolveReport(' + x.id + ')">标记处理</button> ' +
            '<button class="btn btn-danger-o btn-xs" onclick="modAction(' + x.target_type + ',' + x.target_id + ',\'hide\')">隐藏</button></td></tr>').join('');
        const recordsHtml = (d.records || []).map(x => {
            const v = { 1: '<span class="badge warn">存疑(review)</span>', 2: '<span class="badge bad">违规(block)</span>' }[x.verdict] || '<span class="badge ok">pass</span>';
            return '<tr><td>#' + x.id + '</td><td>' + typeCn(x.target_type) + ' #' + x.target_id + '</td><td>' + v + '</td>' +
                '<td>' + esc(x.reason || '') + '</td><td class="num-cell">' + (x.created_at || '') + '</td></tr>';
        }).join('');
        const hiddenPostsHtml = (d.hiddenPosts || []).map(x =>
            '<tr><td>帖子 #' + x.id + '</td><td>' + esc(x.title || '') + '</td><td>' + esc(x.username || '—') + '</td>' +
            '<td><button class="btn btn-warn-o btn-xs" onclick="modAction(1,' + x.id + ',\'restore\')">恢复</button></td></tr>').join('');
        const hiddenCommentsHtml = (d.hiddenComments || []).map(x =>
            '<tr><td>评论 #' + x.id + '</td><td>' + esc(x.content || '').slice(0, 40) + '</td><td>' + esc(x.username || '—') + '</td>' +
            '<td><button class="btn btn-warn-o btn-xs" onclick="modAction(2,' + x.id + ',\'restore\')">恢复</button></td></tr>').join('');

        b.innerHTML =
            '<div class="section-h" style="margin-top:4px">待处理举报 <span class="cnt">' + (d.reports || []).length + ' 条</span></div>' +
            (reportsHtml
                ? '<div class="table-wrap"><table><thead><tr><th>ID</th><th>举报人</th><th>对象</th><th>理由</th><th>操作</th></tr></thead><tbody>' + reportsHtml + '</tbody></table></div>'
                : '<div class="state-box"><div class="big">✅</div>暂无待处理举报</div>') +
            '<div class="section-h">AI 审核记录</div>' +
            (recordsHtml
                ? '<div class="table-wrap"><table><thead><tr><th>ID</th><th>对象</th><th>判定</th><th>理由</th><th>时间</th></tr></thead><tbody>' + recordsHtml + '</tbody></table></div>'
                : '<div class="state-box">暂无审核记录</div>') +
            '<div class="section-h">被隐藏内容</div>' +
            (hiddenPostsHtml
                ? '<div class="table-wrap"><table><thead><tr><th>对象</th><th>标题</th><th>作者</th><th>操作</th></tr></thead><tbody>' + hiddenPostsHtml + '</tbody></table></div>'
                : '<div class="state-box">无被隐藏帖子</div>') +
            (hiddenCommentsHtml
                ? '<div class="table-wrap" style="margin-top:10px"><table><tbody>' + hiddenCommentsHtml + '</tbody></table></div>' : '');
        if (btn) toast('内容审核已刷新');
    }).catch(() => { done(); errBox('moderationBody', '审核队列加载失败', 'loadModeration'); });
}

function resolveReport(id) {
    fetch('/api/admin/moderation/resolve', {
        method: 'PUT', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'id=' + id
    }).then(r => r.json()).then(d => { toast(d.message, !d.success); loadModeration(); })
      .catch(() => toast('请求失败', true));
}
function modAction(type, id, action) {
    fetch('/api/admin/moderation/action', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'targetType=' + type + '&targetId=' + id + '&action=' + action
    }).then(r => r.json()).then(d => { toast(d.message, !d.success); loadModeration(); })
      .catch(() => toast('请求失败', true));
}

/* ============ 业务看板 ============ */
function loadBusiness(btn) {
    const done = btnBusy(btn, '刷新中...');
    fetchJson('/api/admin/stats/business').then(d => {
        done();
        const b = document.getElementById('businessBody');
        if (!d.success) { b.innerHTML = '<div class="state-box">加载失败</div>'; return; }
        const hot = (d.hotDestinations || []).map((x, i) =>
            '<tr><td>' + (i < 3 ? '<b style="color:var(--teal)">#' + (i + 1) + '</b>&nbsp;&nbsp;' : '') + esc(x.destination) + '</td><td class="num-cell">' + x.cnt + '</td></tr>').join('');
        b.innerHTML =
            '<div class="card-grid cols-3">' +
            '<div class="card metric"><div class="num teal">' + d.totalUsers + '</div><div class="label">总用户</div></div>' +
            '<div class="card metric"><div class="num">' + d.dau + '</div><div class="label">今日活跃（DAU）</div></div>' +
            '<div class="card metric"><div class="num">' + d.totalPlans + '</div><div class="label">累计行程</div></div>' +
            '<div class="card metric"><div class="num teal">' + d.todayPlans + '</div><div class="label">今日生成行程</div></div>' +
            '<div class="card metric"><div class="num">' + d.totalPosts + '</div><div class="label">累计发帖</div></div>' +
            '<div class="card metric"><div class="num">' + d.todayPosts + '</div><div class="label">今日发帖</div></div>' +
            '</div>' +
            '<div class="section-h">热门目的地 Top 10</div>' +
            (hot
                ? '<div class="table-wrap"><table><thead><tr><th>目的地</th><th>生成次数</th></tr></thead><tbody>' + hot + '</tbody></table></div>'
                : '<div class="state-box"><div class="big">🏔️</div>暂无数据</div>');
        applyCardMarquee(b);
        if (btn) toast('业务看板已刷新');
    }).catch(() => { done(); errBox('businessBody', '业务看板加载失败', 'loadBusiness'); });
}

/* ============ 公告发布 ============ */
function loadAnnouncements() {
    fetchJson('/api/admin/announcements').then(d => {
        const b = document.getElementById('announceList');
        const items = d.announcements || [];
        if (!items.length) { b.innerHTML = '<div class="state-box"><div class="big">📣</div>暂无公告<br><span style="font-size:12px">在上方填写标题和内容发布第一条</span></div>'; return; }
        b.innerHTML = items.map(a =>
            '<div class="ann-item"><b>' + esc(a.title) + '</b>' +
            '<span class="ann-meta">' + esc(a.username || '管理员') + ' · ' + (a.created_at || '') + '</span>' +
            '<div>' + esc(a.content) + '</div></div>').join('');
    }).catch(() => errBox('announceList', '公告加载失败', 'loadAnnouncements'));
}

function publishAnnouncement(btn) {
    const title = document.getElementById('annTitle').value.trim();
    const content = document.getElementById('annContent').value.trim();
    if (!title || !content) { toast('标题和内容不能为空', true); return; }
    const restore = btnBusy(btn, '发布中...'); /* 点击逻辑：忙碌态防重复发布 */
    fetch('/api/admin/announcements', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'title=' + encodeURIComponent(title) + '&content=' + encodeURIComponent(content)
    }).then(r => r.json()).then(d => {
        restore();
        toast(d.message, !d.success);
        if (d.success) { document.getElementById('annTitle').value = ''; document.getElementById('annContent').value = ''; loadAnnouncements(); }
    }).catch(() => { restore(); toast('请求失败', true); });
}

/* ============ 数据中心 ============ */
/** 只跑 pricing.py（秒级），完成后刷新定价表（点击逻辑：按钮忙碌→完成恢复） */
function runPricing() {
    const restore = btnBusy(document.getElementById('pricingRunBtn'), '更新中...');
    fetchJson('/api/admin/pricing/run', { method: 'POST' }).then(d => {
        toast(d.success ? '定价已更新' : (d.message || '更新失败'), !d.success);
        restore();
        if (d.success) loadDataCenter();
    }).catch(() => { restore(); toast('请求失败', true); });
}

/* 客流预测专用：只跑本地统计（forecast.py 读 trip_plans 生成 forecast_output），不碰采集/定价管线。 */
function runForecastLocal(btn) {
    const restore = btnBusy(document.getElementById('forecastRunBtn'), '统计中...');
    fetchJson('/api/admin/forecast/run', { method: 'POST' }).then(d => {
        toast(d.success ? '客流预测已完成' : (d.message || '重算失败'), !d.success);
        restore();
        if (d.success) loadDataCenter();
    }).catch(() => { restore(); toast('请求失败', true); });
}

function loadDataCenter(btn) {
    const done = btnBusy(btn, '刷新中...');
    // 统计卡片（可点击 → 明细弹窗）
    fetchJson('/api/admin/data-stats').then(d => {
        const s = document.getElementById('centerStats');
        if (!d.success) { s.innerHTML = '<div class="state-box">加载失败</div>'; return; }
        const cards = [
            ['景点', d.attractions, 'attractions'], ['美食', d.foods, 'foods'],
            ['酒店', d.hotels, 'hotels'], ['覆盖城市', d.cities, 'cities'], ['画像用户', d.profiles, 'profiles']
        ];
        s.innerHTML = cards.map(c =>
            '<div class="card metric clickable" title="点击查看明细" onclick="showDataDetail(\'' + c[2] + '\')">' +
            '<div class="num teal">' + c[1] + '</div><div class="label">' + c[0] + '</div></div>').join('');
        applyCardMarquee(s);
    }).catch(() => errBox('centerStats', '统计加载失败', 'loadDataCenter'));

    // 定价表
    fetchJson('/api/admin/pricing').then(d => {
        const b = document.getElementById('pricingBody');
        const rows = d.rows || [];
        if (!rows.length) { b.innerHTML = '<div class="state-box">暂无数据（点上方「只更新定价表」或跑完整管线）</div>'; return; }
        b.innerHTML = '<table><colgroup><col style="width:16%"><col style="width:18%"><col style="width:18%"><col style="width:18%"><col style="width:30%"></colgroup>' +
            '<thead><tr><th style="text-align:center">城市</th><th style="text-align:center">酒店/晚 现价/基准</th><th style="text-align:center">餐饮/人 现价/基准</th><th style="text-align:center">门票/次 现价/基准</th><th style="text-align:center">定价系数</th></tr></thead><tbody>' +
            rows.map(x => {
                let f = '';
                try {
                    const j = JSON.parse(x.factors || '{}');
                    f = ['热度', '节假日', '天气'].map((n, i) => {
                        const v = [j.heat || 1, j.holiday || 1, j.weather || 1][i];
                        return '<span title="' + n + '" style="display:inline-block;margin:1px 4px 1px 0;padding:1px 7px;border-radius:10px;font-size:11px;white-space:nowrap;background:' +
                            (v > 1.001 ? 'rgba(232,180,92,.13);color:#E8B45C' : 'rgba(140,160,188,.11);color:#64748b') + '">' + n + ' ×' + v.toFixed(2) + '</span>';
                    }).join('');
                } catch (e) {}
                return '<tr><td style="text-align:center;font-weight:600">' + esc(x.city) + '</td>' +
                    '<td style="text-align:center"><b style="color:var(--teal)">¥' + x.hotel_price + '</b> <span style="color:var(--faint);font-size:11px">/ ¥' + x.hotel_base + '</span></td>' +
                    '<td style="text-align:center"><b style="color:var(--teal)">¥' + (x.food_price != null ? x.food_price : '-') + '</b> <span style="color:var(--faint);font-size:11px">/ ¥' + (x.food_base != null ? x.food_base : '-') + '</span></td>' +
                    '<td style="text-align:center"><b style="color:var(--teal)">¥' + x.ticket_price + '</b> <span style="color:var(--faint);font-size:11px">/ ¥' + x.ticket_base + '</span></td>' +
                    '<td style="text-align:center">' + f + '</td></tr>';
            }).join('') + '</tbody></table>';
    }).catch(() => errBox('pricingBody', '定价数据加载失败', 'loadDataCenter'));

    // 客流预测图表
    window._fcRows = null; window._fcDim = 'city'; window._fcDayIdx = 0;
    const nav0 = document.getElementById('fcDayNav'); if (nav0) nav0.style.display = 'none';
    fetchJson('/api/admin/forecast').then(d => {
        window._fcRows = d.rows || [];
        renderFcTable();
        done();
        if (btn) toast('数据中心已刷新');
    }).catch(() => { done(); });

    let _fcChart = null;
    function renderFcTable() {
        const el = document.getElementById('fcChart');
        if (!el) return;
        const rows = window._fcRows || [];
        if (!rows.length) { el.innerHTML = '<div class="state-box" style="padding-top:150px"><div class="big">📈</div>暂无预测数据<br><span style="font-size:12px">点上方「🔄 更新数据」运行管线生成</span></div>'; return; }
        if (typeof echarts === 'undefined') { el.innerHTML = '<div class="state-box">图表库加载失败</div>'; return; }
        if (_fcChart) { _fcChart.dispose(); _fcChart = null; }
        _fcChart = echarts.init(el);
        const dayMap = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
        const fmtDay = v => { const dt = new Date(String(v).replace(' ', 'T')); return (dt.getMonth() + 1) + '/' + dt.getDate() + ' ' + dayMap[dt.getDay()]; };
        const dim = window._fcDim;

        if (dim === 'city') {
            const days = [...new Set(rows.map(x => x.day))].sort();
            const cities = [...new Set(rows.map(x => x.city))];
            const palette = ['#2FBF9F', '#5B8FF9', '#E8B45C', '#E8797B', '#9b59b6', '#00c1d4', '#ff9f43', '#7bed9f', '#e84393', '#a4b0be'];
            _fcChart.setOption({
                backgroundColor: 'transparent',
                tooltip: { trigger: 'axis' },
                legend: { type: 'scroll', top: 6, textStyle: { color: '#94a3b8', fontSize: 11 }, pageIconColor: '#94a3b8', pageTextStyle: { color: '#94a3b8' } },
                grid: { left: 42, right: 20, top: 42, bottom: 28 },
                xAxis: { type: 'category', data: days.map(fmtDay), axisLine: { lineStyle: { color: '#334155' } }, axisLabel: { color: '#94a3b8' } },
                yAxis: { type: 'value', name: '客流指数', nameTextStyle: { color: '#94a3b8' }, splitLine: { lineStyle: { color: '#1e293b' } }, axisLabel: { color: '#94a3b8' } },
                series: cities.map((c, i) => ({
                    name: c, type: 'line', smooth: true, symbol: 'circle', symbolSize: 6,
                    data: days.map(dy => { const f = rows.find(x => x.city === c && String(x.day) === String(dy)); return f ? parseFloat(f.forecast_idx) : null; }),
                    lineStyle: { width: 2, color: palette[i % palette.length] },
                    itemStyle: { color: palette[i % palette.length] }
                }))
            });
        } else if (dim === 'date') {
            const days = [...new Set(rows.map(x => x.day))].sort();
            if (window._fcDayIdx >= days.length) window._fcDayIdx = 0;
            const dy = days[window._fcDayIdx];
            const drows = rows.filter(x => String(x.day) === String(dy))
                .map(x => ({ c: x.city, v: parseFloat(x.forecast_idx) }))
                .sort((a, b) => b.v - a.v);
            _fcChart.setOption({
                backgroundColor: 'transparent',
                tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
                grid: { left: 42, right: 20, top: 40, bottom: 62 },
                xAxis: { type: 'category', data: drows.map(x => x.c), axisLabel: { color: '#94a3b8', rotate: 40, fontSize: 10, interval: 0 }, axisLine: { lineStyle: { color: '#334155' } } },
                yAxis: { type: 'value', name: '客流指数', nameTextStyle: { color: '#94a3b8' }, splitLine: { lineStyle: { color: '#1e293b' } }, axisLabel: { color: '#94a3b8' } },
                dataZoom: [{ type: 'slider', height: 16, bottom: 8, borderColor: '#334155', backgroundColor: '#0b1222', fillerColor: 'rgba(47,191,159,.15)', handleStyle: { color: '#2FBF9F' }, textStyle: { color: '#94a3b8' } }],
                series: [{
                    type: 'bar', barMaxWidth: 26,
                    data: drows.map(x => ({ value: x.v, itemStyle: { color: x.v >= 1.2 ? '#ef4444' : (x.v >= 0.8 ? '#E8B45C' : '#2FBF9F'), borderRadius: [4, 4, 0, 0] } })),
                    label: { show: true, position: 'top', color: '#94a3b8', fontSize: 10, formatter: p => p.value.toFixed(2) }
                }]
            });
            const nav = document.getElementById('fcDayNav');
            const lbl = document.getElementById('fcDayLabel');
            if (nav) nav.style.display = 'flex';
            if (lbl) lbl.textContent = fmtDay(dy);
        } else {
            const days = [...new Set(rows.map(x => x.day))].sort();
            const cities = [...new Set(rows.map(x => x.city))];
            const data = [];
            rows.forEach(x => {
                const ci = cities.indexOf(x.city), di = days.indexOf(String(x.day));
                if (ci >= 0 && di >= 0) data.push([di, ci, parseFloat(x.forecast_idx)]);
            });
            _fcChart.setOption({
                backgroundColor: 'transparent',
                tooltip: { formatter: p => cities[p.data[1]] + '<br>' + fmtDay(days[p.data[0]]) + '：' + p.data[2].toFixed(2) },
                grid: { left: 130, right: 70, top: 16, bottom: 46 },
                xAxis: { type: 'category', data: days.map(fmtDay), splitArea: { show: true }, axisLabel: { color: '#94a3b8', fontSize: 10 }, axisLine: { show: false } },
                yAxis: { type: 'category', data: cities, splitArea: { show: true }, axisLabel: { color: '#cbd5e1', fontSize: 11 }, axisLine: { show: false } },
                visualMap: {
                    min: 0.8, max: 1.6, calculable: true, orient: 'vertical', right: 8, top: 'center',
                    textStyle: { color: '#94a3b8' },
                    inRange: { color: ['#2FBF9F', '#E8B45C', '#ef4444'] }
                },
                series: [{ type: 'heatmap', data,
                    label: { show: true, color: '#e2e8f0', fontSize: 10, formatter: p => p.data[2].toFixed(2) },
                    itemStyle: { borderColor: '#0f172a', borderWidth: 2, borderRadius: 3 } }]
            });
        }
    }

    function fcSwitchDim(dim) {
        window._fcDim = dim;
        const nav = document.getElementById('fcDayNav');
        if (nav) nav.style.display = (dim === 'date') ? 'flex' : 'none';
        document.querySelectorAll('.fc-tab').forEach(t => {
            const on = t.dataset.dim === dim;
            t.classList.toggle('btn-teal', on);
            t.classList.toggle('btn-ghost', !on);
        });
        renderFcTable();
    }
    window.fcSwitchDim = fcSwitchDim;
    window._fcDayShift = function(delta) {
        const rows = window._fcRows || [];
        const total = [...new Set(rows.map(x => x.day))].length;
        window._fcDayIdx = ((window._fcDayIdx || 0) + delta + total) % total;
        renderFcTable();
    };
}

/* ==================== 数据统计明细弹窗 ==================== */
function showDataDetail(type) {
    const mask = document.getElementById('dataDetailMask');
    const titleEl = document.getElementById('ddTitle');
    const bodyEl = document.getElementById('ddBody');
    const searchBox = document.getElementById('ddSearch');
    if (!mask || !bodyEl) return;
    mask.classList.add('open');
    titleEl.textContent = '加载中...';
    bodyEl.innerHTML = skeleton(3);
    searchBox.value = '';
    searchBox.style.display = 'none';
    fetchJson('/api/admin/data-detail?type=' + type).then(d => {
        if (!d.success) { titleEl.textContent = '明细'; bodyEl.innerHTML = '<div class="state-box err">加载失败：' + esc(d.error || '') + '</div>'; return; }
        titleEl.textContent = d.title + '（' + (d.rows || []).length + ' 条）';
        window._ddData = d;
        window._ddType = type;
        renderDetailTable('');
        if (['attractions', 'foods', 'hotels', 'cities', 'profiles'].includes(type)) searchBox.style.display = 'inline-block';
    }).catch(() => {
        titleEl.textContent = '明细';
        bodyEl.innerHTML = '<div class="state-box err"><div class="big">⚠️</div>网络异常<button class="btn btn-ghost btn-xs retry-btn" onclick="showDataDetail(window._ddType||\'attractions\')">重试</button></div>';
    });
}
function closeDataDetail() {
    document.getElementById('dataDetailMask').classList.remove('open');
}
/* 点击遮罩关闭弹窗 */
document.addEventListener('click', e => {
    if (e.target && e.target.id === 'dataDetailMask') closeDataDetail();
});
/* Esc 关闭弹窗 */
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closeDataDetail();
});

function renderDetailTable(kw) {
    const bodyEl = document.getElementById('ddBody');
    const d = window._ddData || {};
    if (window._ddType === 'profiles') { renderProfilesCloud(kw); return; }
    let rows = d.rows || [];
    kw = (kw || '').trim().toLowerCase();
    if (kw) rows = rows.filter(r => r.some(cell => String(cell).toLowerCase().includes(kw)));
    if (!rows.length) { bodyEl.innerHTML = '<div class="state-box">无匹配数据</div>'; return; }
    const cols = (d.columns || []).map(c => c === '画像标签(JSON)' ? '画像标签' : c);
    const editable = ['attractions', 'foods', 'hotels'].includes(window._ddType);
    let html = '<table><thead><tr>' +
        cols.map(c => '<th>' + esc(c) + '</th>').join('') +
        (editable ? '<th style="width:70px;text-align:center">操作</th>' : '') + '</tr></thead><tbody>';
    rows.forEach(r => {
        const rid = String(r[0]);
        const cells = r.slice(1);
        html += '<tr>' +
            cells.map((v, i) => {
                const st = i === 0 ? 'font-weight:600;white-space:nowrap' : 'max-width:280px;overflow-wrap:break-word;word-break:break-word';
                return '<td style="' + st + '">' + esc(v) + '</td>';
            }).join('') +
            (editable ? '<td style="text-align:center"><button class="btn btn-teal btn-xs" onclick="editDetailRow(\'' + window._ddType + '\',\'' + rid + '\',this)">编辑</button></td>' : '') +
            '</tr>';
    });
    bodyEl.innerHTML = html + '</tbody></table>';
}
/* 行内编辑：整行单元格变输入框（点击逻辑：编辑→保存/取消 两态切换） */
function editDetailRow(type, id, btn) {
    const tr = btn.closest('tr');
    const tds = [...tr.querySelectorAll('td')];
    tds.slice(0, -1).forEach(td => {
        const v = td.textContent;
        td.innerHTML = '<input value="' + esc(v) + '" style="width:92%;padding:4px 8px;background:var(--bg);border:1px solid var(--line-strong);border-radius:6px;color:var(--ink);font-size:12.5px">';
    });
    btn.textContent = '保存';
    btn.className = 'btn btn-primary btn-xs';
    btn.onclick = () => saveDetailRow(type, id, btn);
    const cancelBtn = document.createElement('button');
    cancelBtn.textContent = '取消';
    cancelBtn.className = 'btn btn-ghost btn-xs';
    cancelBtn.style.marginLeft = '4px';
    cancelBtn.onclick = () => showDataDetail(type);
    btn.parentNode.appendChild(cancelBtn);
}
/* 保存：逐字段提交 */
function saveDetailRow(type, id, btn) {
    const restore = btnBusy(btn, '保存中...');
    const tr = btn.closest('tr');
    const tds = [...tr.querySelectorAll('td')];
    const inputs = tds.slice(0, -2).map(td => td.querySelector('input'));
    const d = window._ddData || {};
    const cols = (d.columns || []).map(c => c === '画像标签(JSON)' ? '画像标签' : c);
    const fieldMap = { '名称': 'name', '城市': 'city', '评分': 'rating', '参考费用': 'cost', '人均': 'cost', '参考价': 'cost', '地址': 'address' };
    const tasks = [];
    inputs.forEach((inp, i) => {
        const col = cols[i];
        const f = fieldMap[col];
        if (!f || !inp) return;
        tasks.push(fetchJson('/api/admin/data-detail/edit?type=' + type + '&id=' + id + '&field=' + f + '&value=' + encodeURIComponent(inp.value.trim())));
    });
    Promise.all(tasks).then(results => {
        restore();
        const ok = results.every(x => x && x.success);
        toast(ok ? '已保存' : '部分字段保存失败', !ok);
        showDataDetail(type);
    }).catch(() => { restore(); toast('保存失败', true); showDataDetail(type); });
}

/* 画像标签云渲染（配色与前台一致：常去=青绿/风格=紫/偏好=蓝） */
function renderProfilesCloud(kw) {
    const bodyEl = document.getElementById('ddBody');
    const rows = window._ddData.rows || [];
    kw = (kw || '').trim().toLowerCase();
    const list = kw ? rows.filter(r => r.some(cell => String(cell).toLowerCase().includes(kw))) : rows;
    if (!list.length) { bodyEl.innerHTML = '<div class="state-box">无匹配数据</div>'; return; }
    bodyEl.innerHTML = '';
    const CAT_STYLE = {
        '常去': ['rgba(47,191,159,.15)', '#2FBF9F'],
        '偏好': ['rgba(91,168,232,.15)', '#5BA8E8'],
        '风格': ['rgba(168,85,247,.15)', '#a855f7']
    };
    list.forEach(r => {
        const uname = String(r[1]);
        const uid = esc(String(r[2]));
        let html = '<div style="margin-bottom:16px"><div style="font-size:13px;font-weight:600;color:var(--ink);margin-bottom:6px">👤 ' + esc(uname === '-' ? '用户 #' + uid : uname) + ' <span style="color:var(--faint);font-weight:400">#' + uid + '</span></div>';
        try {
            const tags = JSON.parse(r[3]);
            const entries = Object.entries(tags).sort((a, b) => b[1] - a[1]);
            const maxV = entries.length ? entries[0][1] : 1;
            let cloud = '<div style="display:flex;flex-wrap:wrap;gap:6px">';
            entries.forEach(([k, v]) => {
                const cat = k.split('-')[0];
                const st = CAT_STYLE[cat] || ['rgba(140,160,188,.14)', '#8CA0BC'];
                const fs = 12 + Math.min(6, Math.round((v / maxV) * 6));
                const bg = st[0].replace('.15', '.' + (0.10 + Math.min(0.25, v / maxV * 0.25)).toFixed(2));
                cloud += '<span title="' + cat + '类 权重' + v + '" style="padding:3px 10px;border-radius:11px;font-size:' + fs + 'px;background:' + bg + ';color:' + st[1] + ';font-weight:' + (v / maxV > 0.66 ? 700 : 400) + '">' +
                    esc(k) + (v > 1 ? ' <b>×' + v + '</b>' : '') + '</span>';
            });
            cloud += '</div>';
            html += cloud;
        } catch (e) { html += '<span style="color:var(--muted)">' + esc(String(r[3])) + '</span>'; }
        html += '<div style="font-size:11px;color:var(--faint);margin-top:4px">更新时间 ' + esc(String(r[4])) + '</div></div>';
        bodyEl.insertAdjacentHTML('beforeend', html);
    });
}

/* ============ 数据管线一键更新（点击+等待逻辑最重的流程） ============ */
let pipelinePolling = false;

function runPipeline(btn) {
    if (!confirm('确认启动完整数据更新？（采集→预处理→…约30分钟）')) return;
    const restore = btnBusy(btn, '启动中...');
    const stopBtn = document.getElementById('pipeStopBtn');
    fetchJson('/api/admin/pipeline/run', { method: 'POST' }).then(d => {
        restore();
        toast(d.message || (d.success ? '已在后台启动' : '启动失败'), !d.success);
        if (d.success) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spin"></span> 更新中...';
            if (stopBtn) stopBtn.style.display = '';
            pipelinePolling = false;
            setTimeout(() => pollPipeline(), 5000);
        }
    }).catch(() => { restore(); toast('请求失败', true); });
}
function pollPipeline() {
    if (pipelinePolling) return;
    pipelinePolling = true;
    const autoTag = document.getElementById('pipeAutoTag');
    if (autoTag) autoTag.style.display = '';
    const bar = document.getElementById('pipelineBar');
    if (bar) bar.className = 'breaker-banner HALF_OPEN';
    const dot = document.getElementById('pipeStateDot');
    const text = document.getElementById('pipeStateText');
    if (dot) dot.className = 'dot warn';
    if (text) text.textContent = '管线更新中（采集→预处理→等级→LLM→定价→预测→画像），页面每 15s 自动查询进度';
    fetchJson('/api/admin/pipeline/status').then(d => {
        pipelinePolling = false;
        if (d.running) { setTimeout(pollPipeline, 15000); }
        else {
            const done = Object.values(d.jobs || {}).some(v => String(v).startsWith('done'));
            toast(done ? '数据更新完成' : '更新结束（有错误，请看服务日志）', !done);
            resetPipelineUi();
            loadDataCenter();
        }
    }).catch(() => { pipelinePolling = false; setTimeout(pollPipeline, 20000); }); /* 断网也持续重试轮询 */
}
function resetPipelineUi() {
    const btn = document.getElementById('pipeBtn');
    if (btn) { btn.disabled = false; btn.innerHTML = '🔄 更新数据'; }
    const sb = document.getElementById('pipeStopBtn');
    if (sb) sb.style.display = 'none';
    const autoTag = document.getElementById('pipeAutoTag');
    if (autoTag) autoTag.style.display = 'none';
    const bar = document.getElementById('pipelineBar');
    if (bar) bar.className = 'breaker-banner';
    const dot = document.getElementById('pipeStateDot');
    const text = document.getElementById('pipeStateText');
    if (dot) dot.className = 'dot ok';
    if (text) text.textContent = '管线空闲 · 数据为上次更新结果';
}
function stopPipeline(btn) {
    const restore = btnBusy(btn, '停止中...');
    fetchJson('/api/admin/pipeline/stop', { method: 'POST' }).then(d => {
        toast(d.message || '已发送停止请求', !d.success);
        restore();
        setTimeout(resetPipelineUi, 1500);
    }).catch(() => { restore(); toast('请求失败', true); });
}

/* ============ ★模型配置热更 ============ */
const KEY_MASK = '••••••••';
function applyKeyField(inputId, masked, placeholderWhenSet) {
    const input = document.getElementById(inputId);
    input.value = masked ? KEY_MASK : '';
    input.placeholder = masked ? placeholderWhenSet : '未填写（留空=不修改）';
}
function loadModelConfig(doneCb) {
    fetchJson('/api/admin/model-config').then(d => {
        if (!d.success) return;
        document.getElementById('mcJavaUrl').value = d.java.url || '';
        document.getElementById('mcJavaName').value = d.java.name || '';
        applyKeyField('mcJavaKey', !!d.java.keyMasked, '当前: ' + d.java.keyMasked);
        const fb = d.fallback || {};
        document.getElementById('mcFbUrl').value = fb.url || '';
        document.getElementById('mcFbName').value = fb.name || '';
        applyKeyField('mcFbKey', !!(fb.keyMasked), '当前: ' + fb.keyMasked);
        document.getElementById('mcPyUrl').value = d.python.url || '';
        document.getElementById('mcPyName').value = d.python.name || '';
        applyKeyField('mcPyKey', !!d.python.keyMasked, '当前: ' + d.python.keyMasked);
        document.getElementById('ocMaxTasks').value = d.orchestration?.maxConcurrentTasks || 1;
        document.getElementById('ocRemoteTimeout').value = d.orchestration?.remoteTimeoutSeconds || 300;
        document.getElementById('toDefault').value = d.timeouts?.defaultSeconds || 120;
        document.getElementById('toModeration').value = d.timeouts?.moderationSeconds || 60;
        document.getElementById('toAiAnswer').value = d.timeouts?.aiAnswerSeconds || 120;
        document.getElementById('toTripEdit').value = d.timeouts?.tripEditSeconds || 120;
        if (doneCb) doneCb();
    }).catch(() => {});
}

function saveModelConfig() {
    const body = new URLSearchParams();
    const ju = document.getElementById('mcJavaUrl').value.trim();
    const jk = document.getElementById('mcJavaKey').value.trim();
    const jn = document.getElementById('mcJavaName').value.trim();
    const fu = document.getElementById('mcFbUrl').value.trim();
    const fk = document.getElementById('mcFbKey').value.trim();
    const fn = document.getElementById('mcFbName').value.trim();
    const pu = document.getElementById('mcPyUrl').value.trim();
    const pk = document.getElementById('mcPyKey').value.trim();
    const pn = document.getElementById('mcPyName').value.trim();
    const maxTasks = document.getElementById('ocMaxTasks').value.trim();
    const remoteTimeout = document.getElementById('ocRemoteTimeout').value.trim();
    const jkReal = (jk && jk !== KEY_MASK) ? jk : '';
    const fkReal = (fk && fk !== KEY_MASK) ? fk : '';
    const pkReal = (pk && pk !== KEY_MASK) ? pk : '';
    if (ju) body.append('javaUrl', ju);
    if (jkReal) body.append('javaKey', jkReal);
    if (jn) body.append('javaName', jn);
    if (fu) body.append('fbUrl', fu);
    if (fkReal) body.append('fbKey', fkReal);
    if (fn) body.append('fbName', fn);
    if (pu) body.append('pyUrl', pu);
    if (pkReal) body.append('pyKey', pkReal);
    if (pn) body.append('pyName', pn);
    if (maxTasks) body.append('maxConcurrentTasks', maxTasks);
    if (remoteTimeout) body.append('remoteTimeoutSeconds', remoteTimeout);
    const defaultTimeout = document.getElementById('toDefault').value.trim();
    const moderationTimeout = document.getElementById('toModeration').value.trim();
    const aiAnswerTimeout = document.getElementById('toAiAnswer').value.trim();
    const tripEditTimeout = document.getElementById('toTripEdit').value.trim();
    if (defaultTimeout) body.append('defaultTimeoutSeconds', defaultTimeout);
    if (moderationTimeout) body.append('moderationTimeoutSeconds', moderationTimeout);
    if (aiAnswerTimeout) body.append('aiAnswerTimeoutSeconds', aiAnswerTimeout);
    if (tripEditTimeout) body.append('tripEditTimeoutSeconds', tripEditTimeout);
    const qps = document.getElementById('limQps').value.trim();
    const maxInflight = document.getElementById('limMaxInflight').value.trim();
    const retries = document.getElementById('limRetries').value.trim();
    const retryBase = document.getElementById('limRetryBaseMs').value.trim();
    const bThreshold = document.getElementById('limBreakerThreshold').value.trim();
    const bWindow = document.getElementById('limBreakerWindow').value.trim();
    const bCoolDown = document.getElementById('limBreakerCoolDown').value.trim();
    if (qps) body.append('qps', qps);
    if (maxInflight) body.append('maxInflight', maxInflight);
    if (retries) body.append('maxRetries', retries);
    if (retryBase) body.append('retryBaseMs', retryBase);
    if (bThreshold) body.append('breakerFailureThreshold', bThreshold);
    if (bWindow) body.append('breakerWindowMs', bWindow);
    if (bCoolDown) body.append('breakerCoolDownMs', bCoolDown);

    /* 点击逻辑：保存按钮忙碌态 + 结果反馈到 mcMsg */
    const saveBtn = event && event.target ? event.target : null;
    const restore = btnBusy(saveBtn, '保存中...');
    const msg = document.getElementById('mcMsg');
    msg.textContent = '正在保存配置...'; msg.className = 'msg-line';
    fetch('/api/admin/model-config', { method: 'POST', body })
        .then(r => r.json())
        .then(d => {
            restore();
            msg.textContent = d.message || (d.success ? '已保存，热生效' : '保存失败');
            msg.className = 'msg-line ' + (d.success ? 'ok' : 'err');
            loadModelConfig();
        })
        .catch(() => { restore(); msg.textContent = '网络错误，配置未确认保存'; msg.className = 'msg-line err'; });
}

/* ============ ★Agent工具开关 ============ */
let agentConfig = null;

function readAgentConfigInputs() {
    return {
        amap: document.getElementById('acAmap').checked,
        localKnowledge: document.getElementById('acLocalKnowledge').checked,
        remoteRouteAgent: document.getElementById('acRemoteRoute').checked,
        remoteTripAgent: document.getElementById('acRemoteTrip').checked
    };
}
function renderAgentConfig(config) {
    document.getElementById('acAmap').checked = !!config.amap;
    document.getElementById('acLocalKnowledge').checked = !!config.localKnowledge;
    document.getElementById('acRemoteRoute').checked = !!config.remoteRouteAgent;
    document.getElementById('acRemoteTrip').checked = !!config.remoteTripAgent;
}
function setAgentConfigBusy(busy) {
    ['acAmap', 'acLocalKnowledge', 'acRemoteRoute', 'acRemoteTrip']
        .forEach(id => { document.getElementById(id).disabled = busy; });
}
function loadAgentConfig() {
    fetchJson('/api/admin/agent-config').then(d => {
        if (!d.success) return;
        const tools = d.tools || {};
        agentConfig = readAgentConfigInputs();
        Object.assign(agentConfig, tools);
        renderAgentConfig(agentConfig);
        const msg = document.getElementById('acMsg');
        msg.textContent = d.restartInProgress ? '子Agent重启中...' : ('配置来源：' + (d.source === 'nacos' ? 'Nacos' : '默认值'));
        msg.className = 'msg-line ' + (d.restartInProgress ? 'warn' : '');
    }).catch(() => {
        const msg = document.getElementById('acMsg');
        msg.textContent = '配置加载失败';
        msg.className = 'msg-line err';
    });
}
function saveAgentConfig() {
    const msg = document.getElementById('acMsg');
    if (!agentConfig) {
        msg.textContent = '配置还在加载，请稍后再试';
        msg.className = 'msg-line warn';
        return;
    }
    const next = readAgentConfigInputs();
    const registrationChanged = next.amap !== agentConfig.amap || next.localKnowledge !== agentConfig.localKnowledge;
    const changed = registrationChanged
        || next.remoteRouteAgent !== agentConfig.remoteRouteAgent
        || next.remoteTripAgent !== agentConfig.remoteTripAgent;
    if (!changed) return;

    const confirmed = registrationChanged
        ? confirm('确认修改工具开关？\n\n保存后将重启 8082/8085 两个子Agent，路线和行程功能会短暂不可用。')
        : confirm('确认修改远程Agent调用开关？修改会立即保存并热生效。');
    if (!confirmed) {
        renderAgentConfig(agentConfig); /* 取消 → 回滚勾选状态 */
        return;
    }

    const body = new URLSearchParams(next);
    setAgentConfigBusy(true);
    msg.textContent = '正在保存配置...'; msg.className = 'msg-line';
    fetch('/api/admin/agent-config', { method: 'POST', body })
        .then(r => r.json())
        .then(d => {
            if (d.success) agentConfig = next;
            else renderAgentConfig(agentConfig);
            msg.textContent = d.message || (d.success ? '已保存' : '保存失败');
            msg.className = 'msg-line ' + (d.success ? (d.restartTriggered ? 'warn' : 'ok') : 'err');
            if (d.restartTriggered) setTimeout(loadAgentConfig, 5000);
        })
        .catch(() => {
            renderAgentConfig(agentConfig);
            msg.textContent = '网络错误，配置未确认保存';
            msg.className = 'msg-line err';
        })
        .finally(() => setAgentConfigBusy(false));
}

/* ============ ★高德凭证 ============ */
let amapCredentials = null;

function loadAmapCredentials() {
    fetchJson('/api/admin/amap-credentials').then(d => {
        if (!d.success) return;
        amapCredentials = d;
        applyKeyField('amapWebKey', !!d.webKeyMasked, '当前: ' + d.webKeyMasked);
        applyKeyField('amapMcpKey', !!d.mcpKeyMasked, '当前: ' + d.mcpKeyMasked);
        const msg = document.getElementById('amapKeyMsg');
        if (d.restartInProgress) {
            msg.textContent = '子Agent重启中...';
            msg.className = 'msg-line warn';
            setTimeout(loadAmapCredentials, 5000);
        } else {
            msg.textContent = '配置来源：' + (d.source === 'nacos' ? 'Nacos' : '环境变量兜底');
            msg.className = 'msg-line';
        }
    }).catch(() => {
        const msg = document.getElementById('amapKeyMsg');
        msg.textContent = '高德凭证加载失败';
        msg.className = 'msg-line err';
    });
}
function saveAmapCredentials() {
    const msg = document.getElementById('amapKeyMsg');
    const webRaw = document.getElementById('amapWebKey').value.trim();
    const mcpRaw = document.getElementById('amapMcpKey').value.trim();
    const webKey = webRaw === KEY_MASK ? '' : webRaw;
    const mcpKey = mcpRaw === KEY_MASK ? '' : mcpRaw;
    if (!webKey && !mcpKey) {
        msg.textContent = '请输入要修改的高德 Key；留空表示不修改';
        msg.className = 'msg-line warn';
        return;
    }
    if (!confirm('确认修改高德 Key？\n\n普通 Key 热生效；MCP Key 变化会自动重启 8082/8085。')) {
        loadAmapCredentials();
        return;
    }
    const body = new URLSearchParams();
    if (webKey) body.append('webKey', webKey);
    if (mcpKey) body.append('mcpKey', mcpKey);
    msg.textContent = '正在保存高德 Key...'; msg.className = 'msg-line';
    fetch('/api/admin/amap-credentials', { method: 'POST', body })
        .then(r => r.json())
        .then(d => {
            msg.textContent = d.message || (d.success ? '已保存' : '保存失败');
            msg.className = 'msg-line ' + (d.success ? (d.restartTriggered ? 'warn' : 'ok') : 'err');
            loadAmapCredentials();
            if (d.restartTriggered) setTimeout(loadAmapCredentials, 5000);
        })
        .catch(() => { msg.textContent = '网络错误，配置未确认保存'; msg.className = 'msg-line err'; });
}

/* ============ Agent提示词/工具描述 ============ */
function promptLabel(key) {
    const labels = {
        requestParser: '需求解析提示词',
        preferenceClassifier: '偏好分类提示词',
        tripAggregator: '行程聚合/输出格式',
        tripAggregatorParseRetry: '聚合解析重试',
        tripAggregatorGapRetry: '聚合补齐重试',
        moderation: '内容审核提示词',
        tripEditor: '行程编辑提示词',
        routeAgentSystem: '路线Agent系统提示词',
        tripAgentSystem: '行程Agent系统提示词',
        genericReActSystem: '通用ReAct提示词模板',
        toolQueryTrip: '工具描述：query_trip',
        toolRouteUnified: '工具描述：query_route_unified',
        toolLocalKnowledge: '工具描述：query_local_knowledge'
    };
    return labels[key] || key;
}
function loadAgentPrompts() {
    const fields = document.getElementById('promptFields');
    if (!fields) return;
    fetchJson('/api/admin/agent-prompts').then(d => {
        if (!d.success) return;
        fields.innerHTML = '';
        Object.entries(d.prompts || {}).forEach(([key, item]) => {
            const wrap = document.createElement('div');
            wrap.className = 'field';
            const label = document.createElement('label');
            label.textContent = promptLabel(key) + (item.customized ? '（Nacos Prompt）' : '');
            const area = document.createElement('textarea');
            area.dataset.promptKey = key;
            area.value = item.value || '';
            area.readOnly = true;
            area.rows = key.startsWith('tool') ? 4 : 9;
            wrap.appendChild(label);
            wrap.appendChild(area);
            fields.appendChild(wrap);
        });
        const msg = document.getElementById('promptMsg');
        msg.textContent = d.source === 'nacos-prompt' ? '配置来源：Nacos Prompt 管理；请在 Nacos 控制台修改' : '配置来源：代码默认值';
        msg.className = 'msg-line';
    }).catch(() => {
        const msg = document.getElementById('promptMsg');
        msg.textContent = '提示词加载失败';
        msg.className = 'msg-line err';
    });
}
