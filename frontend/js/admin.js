/* ============================================================
   admin.js — 管理控制台（admin.html）全部前端逻辑
   登录校验（仅 admin）、四页签、调用 /api/admin/* 接口渲染
   ============================================================ */

let currentTab = 'status';
let statusTimer = null, tasksTimer = null;

// HTML 转义（防 XSS：用户名/预览等动态内容先转义再渲染）
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
    setTimeout(() => { t.className = 'toast'; }, 2500);
}

// ============ 登录校验 ============
fetch('/api/check-login').then(r => r.json()).then(d => {
    if (!d.success) { location.href = 'login.html'; return; }
    document.getElementById('currentUser').textContent = d.username + (d.role === 'admin' ? '（管理员）' : '');
    if (d.role !== 'admin') {
        document.querySelector('.content').innerHTML =
            '<div class="panel"><div class="empty">❌ 无管理员权限，仅 admin 角色可访问本页。</div></div>';
        return;
    }
    switchTab('status');
}).catch(() => location.href = 'login.html');

function logout() {
    fetch('/api/logout', { method: 'POST' }).then(() => location.href = 'login.html');
}

// ============ 页签切换 ============
function switchTab(tab) {
    currentTab = tab;
    document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
    document.querySelectorAll('.panel').forEach(p => p.classList.add('hidden'));
    document.getElementById('panel-' + tab).classList.remove('hidden');
    // 切到该页签时拉取数据；任务/状态页启动自动刷新
    if (tab === 'status') { loadStatus(); startStatusTimer(); stopTasksTimer(); }
    else if (tab === 'tasks') { loadTasks(); startTasksTimer(); stopStatusTimer(); }
    else if (tab === 'breaker') { loadBreaker(); stopStatusTimer(); stopTasksTimer(); }
    else if (tab === 'data') { loadData(); stopStatusTimer(); stopTasksTimer(); }
    else if (tab === 'moderation') { loadModeration(); stopStatusTimer(); stopTasksTimer(); }
    else if (tab === 'business') { loadBusiness(); stopStatusTimer(); stopTasksTimer(); }
    else if (tab === 'announce') { loadAnnouncements(); stopStatusTimer(); stopTasksTimer(); }
    else if (tab === 'center') { loadDataCenter(); stopStatusTimer(); stopTasksTimer(); }
}

function startStatusTimer() { if (!statusTimer) statusTimer = setInterval(loadStatus, 5000); }
function stopStatusTimer() { if (statusTimer) { clearInterval(statusTimer); statusTimer = null; } }
function startTasksTimer() { if (!tasksTimer) tasksTimer = setInterval(loadTasks, 3000); }
function stopTasksTimer() { if (tasksTimer) { clearInterval(tasksTimer); tasksTimer = null; } }

// ============ 系统状态 ============
function loadStatus() {
    fetch('/api/admin/status').then(r => r.json()).then(d => {
        const b = document.getElementById('statusBody');
        if (!d.success) { b.innerHTML = '<div class="empty">加载失败</div>'; return; }
        const nacosDot = d.nacos.connected ? 'ok' : 'bad';
        const nacosBadge = d.nacos.connected
            ? '<span class="badge ok">已连接</span>' : '<span class="badge bad">未连接</span>';
        let agentsHtml = d.agents.map(a => {
            const dot = a.registered ? 'ok' : 'bad';
            const badge = a.registered ? '<span class="badge ok">已注册</span>' : '<span class="badge bad">离线</span>';
            return '<div class="status-card"><h3>🤖 ' + esc(a.name) + '</h3>' +
                '<div class="status-row"><span>端口</span><b>' + a.port + '</b></div>' +
                '<div class="status-row"><span>注册状态</span>' + badge + '</div></div>';
        }).join('');
        b.innerHTML =
            '<div class="status-grid">' +
            '<div class="status-card"><h3>🧭 ManagerAgent</h3>' +
            '<div class="status-row"><span>状态</span><span class="badge ok">运行中</span></div>' +
            '<div class="status-row"><span>端口</span><b>' + d.manager.port + '</b></div></div>' +
            '<div class="status-card"><h3>🔗 Nacos 注册中心</h3>' +
            '<div class="status-row"><span>地址</span><b>' + esc(d.nacos.addr) + '</b></div>' +
            '<div class="status-row"><span>连接</span>' + nacosBadge + '</div>' +
            (d.nacos.error ? '<div class="status-row"><span>错误</span><b style="color:#f87171">' + esc(d.nacos.error) + '</b></div>' : '') +
            '</div>' + agentsHtml + '</div>';
    }).catch(() => {});
}

// ============ 任务监控 ============
function loadTasks() {
    fetch('/api/admin/tasks').then(r => r.json()).then(d => {
        const s = document.getElementById('taskSummary');
        s.innerHTML =
            '<div class="summary-card"><div class="num">' + d.inUse + '/' + d.maxConcurrent + '</div><div class="label">并发槽占用</div></div>' +
            '<div class="summary-card"><div class="num">' + d.sessions.length + '</div><div class="label">会话总数</div></div>' +
            '<div class="summary-card"><div class="num">' + d.sessions.filter(x => x.running).length + '</div><div class="label">运行中</div></div>';
        const b = document.getElementById('taskBody');
        if (!d.sessions.length) { b.innerHTML = '<div class="empty">暂无会话</div>'; return; }
        let rows = d.sessions.map(x => {
            const state = x.running ? '<span class="badge ok">运行中</span>' : (x.hasResult ? '<span class="badge">已完成</span>' : '<span class="badge">空闲</span>');
            return '<tr><td class="mono">' + esc(x.sessionId) + '</td>' +
                '<td>' + state + '</td>' +
                '<td>' + x.eventCount + '</td>' +
                '<td>' + (x.cancelled ? '<span class="badge bad">已请求中断</span>' : '—') + '</td>' +
                '<td>' + (x.ownerId || '—') + '</td>' +
                '<td><button class="btn-sm" onclick="forceCancel(\'' + esc(x.sessionId) + '\')">强制中断</button></td></tr>';
        }).join('');
        b.innerHTML = '<table><thead><tr><th>会话ID</th><th>状态</th><th>事件数</th><th>中断</th><th>归属用户</th><th>操作</th></tr></thead><tbody>' + rows + '</tbody></table>';
    }).catch(() => {});
}

function forceCancel(sessionId) {
    if (!confirm('确认强制中断会话 ' + sessionId + ' ？')) return;
    fetch('/api/admin/tasks/cancel', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'sessionId=' + encodeURIComponent(sessionId) })
        .then(r => r.json()).then(d => { toast(d.message, !d.success); loadTasks(); });
}

// ============ 模型监控 ============
function loadBreaker() {
    fetch('/api/admin/breaker').then(r => r.json()).then(d => {
        const b = document.getElementById('breakerBody');
        loadModelConfig();
        const stateCn = { CLOSED: '正常（CLOSED）', OPEN: '熔断中（OPEN）', HALF_OPEN: '半开试探（HALF_OPEN）' }[d.state] || d.state;
        b.innerHTML =
            '<div class="breaker-state ' + d.state + '">熔断器状态：<b>' + stateCn + '</b></div>' +
            '<div class="metric-grid">' +
            '<div class="metric"><div class="num">' + d.consecutiveFailures + '</div><div class="label">窗口内失败次数</div></div>' +
            '<div class="metric"><div class="num">' + d.maxQps + '</div><div class="label">限速 QPS</div></div>' +
            '<div class="metric"><div class="num">' + d.maxRetries + '</div><div class="label">最大重试次数</div></div>' +
            '<div class="metric"><div class="num">' + esc(d.primaryModel) + '</div><div class="label">主模型</div></div>' +
            '<div class="metric"><div class="num">' + esc(d.fallbackModel) + '</div><div class="label">备用模型</div></div>' +
            '</div>';
    }).catch(() => {});
}

// ============ 数据管理 ============
function loadData() { loadUsers(); loadPlans(); }

function loadUsers() {
    fetch('/api/admin/users').then(r => r.json()).then(d => {
        const b = document.getElementById('usersBody');
        if (!d.users.length) { b.innerHTML = '<div class="empty">暂无用户</div>'; return; }
        let rows = d.users.map(u => {
            const roleBadge = u.role === 'admin' ? '<span class="badge ok">admin</span>' : '<span class="badge">user</span>';
            return '<tr><td>' + u.id + '</td><td>' + esc(u.username) + '</td><td>' + roleBadge + '</td>' +
                '<td><button class="btn-danger" onclick="deleteUser(' + u.id + ',\'' + esc(u.username) + '\')">删除</button></td></tr>';
        }).join('');
        b.innerHTML = '<table><thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>操作</th></tr></thead><tbody>' + rows + '</tbody></table>';
    }).catch(() => {});
}

function loadPlans() {
    fetch('/api/admin/plans').then(r => r.json()).then(d => {
        const b = document.getElementById('plansBody');
        if (!d.plans.length) { b.innerHTML = '<div class="empty">暂无行程</div>'; return; }
        let rows = d.plans.map(p => {
            return '<tr><td>' + p.id + '</td><td>' + (p.username ? esc(p.username) : '—') + '</td>' +
                '<td>' + (p.city ? esc(p.city) : '—') + '</td><td>' + (p.days != null ? p.days : '—') + '</td>' +
                '<td class="preview-cell"><span class="preview" title="' + esc(p.preview) + '">' + esc(p.preview) + '</span></td>' +
                '<td><button class="btn-danger" onclick="deletePlan(' + p.id + ')">删除</button></td></tr>';
        }).join('');
        b.innerHTML = '<table><thead><tr><th>ID</th><th>用户</th><th>城市</th><th>天数</th><th>内容预览</th><th>操作</th></tr></thead><tbody>' + rows + '</tbody></table>';
    }).catch(() => {});
}

function deleteUser(id, name) {
    if (!confirm('确认删除用户「' + name + '」（含其名下全部行程）？此操作不可恢复。')) return;
    fetch('/api/admin/users?id=' + id, { method: 'DELETE' }).then(r => r.json()).then(d => { toast(d.message, !d.success); loadUsers(); loadPlans(); });
}

function deletePlan(id) {
    if (!confirm('确认删除行程 #' + id + ' ？此操作不可恢复。')) return;
    fetch('/api/admin/plans?id=' + id, { method: 'DELETE' }).then(r => r.json()).then(d => { toast(d.message, !d.success); loadPlans(); });
}

// ============ 内容审核 ============
function loadModeration() {
    fetch('/api/admin/moderation/queue').then(r => r.json()).then(d => {
        const b = document.getElementById('moderationBody');
        if (!d.success) { b.innerHTML = '<div class="empty">加载失败</div>'; return; }
        const typeCn = t => t === 1 ? '帖子' : '评论';
        // 待处理举报
        let reportsHtml = (d.reports || []).map(x =>
            '<tr><td>#' + x.id + '</td><td>' + esc(x.reporter || '—') + '</td><td>' + typeCn(x.target_type) + ' #' + x.target_id + '</td>' +
            '<td>' + esc(x.reason) + '</td><td>' +
            '<button class="btn-sm" onclick="resolveReport(' + x.id + ')">标记处理</button> ' +
            '<button class="btn-danger" onclick="modAction(' + x.target_type + ',' + x.target_id + ',\'hide\')">隐藏</button></td></tr>').join('');
        // AI 审核 review/block 记录
        let recordsHtml = (d.records || []).map(x => {
            const v = { 1: '存疑(review)', 2: '违规(block)' }[x.verdict] || 'pass';
            return '<tr><td>#' + x.id + '</td><td>' + typeCn(x.target_type) + ' #' + x.target_id + '</td><td>' + v + '</td>' +
                '<td>' + esc(x.reason || '') + '</td><td>' + (x.created_at || '') + '</td></tr>';
        }).join('');
        // 被隐藏内容
        let hiddenPostsHtml = (d.hiddenPosts || []).map(x =>
            '<tr><td>帖子 #' + x.id + '</td><td>' + esc(x.title || '') + '</td><td>' + esc(x.username || '—') + '</td>' +
            '<td><button class="btn-sm" onclick="modAction(1,' + x.id + ',\'restore\')">恢复</button></td></tr>').join('');
        let hiddenCommentsHtml = (d.hiddenComments || []).map(x =>
            '<tr><td>评论 #' + x.id + '</td><td>' + esc(x.content || '').slice(0, 40) + '</td><td>' + esc(x.username || '—') + '</td>' +
            '<td><button class="btn-sm" onclick="modAction(2,' + x.id + ',\'restore\')">恢复</button></td></tr>').join('');

        b.innerHTML =
            '<h3>待处理举报（' + (d.reports || []).length + '）</h3>' +
            (reportsHtml ? '<table><thead><tr><th>ID</th><th>举报人</th><th>对象</th><th>理由</th><th>操作</th></tr></thead><tbody>' + reportsHtml + '</tbody></table>' : '<div class="empty">暂无举报</div>') +
            '<h3 style="margin-top:24px">AI 审核记录</h3>' +
            (recordsHtml ? '<table><thead><tr><th>ID</th><th>对象</th><th>判定</th><th>理由</th><th>时间</th></tr></thead><tbody>' + recordsHtml + '</tbody></table>' : '<div class="empty">暂无审核记录</div>') +
            '<h3 style="margin-top:24px">被隐藏内容</h3>' +
            (hiddenPostsHtml ? '<table><thead><tr><th>对象</th><th>标题</th><th>作者</th><th>操作</th></tr></thead><tbody>' + hiddenPostsHtml + '</tbody></table>' : '<div class="empty">无被隐藏帖子</div>') +
            (hiddenCommentsHtml ? '<table style="margin-top:8px"><tbody>' + hiddenCommentsHtml + '</tbody></table>' : '');
    }).catch(() => {});
}

function resolveReport(id) {
    fetch('/api/admin/moderation/resolve', { method: 'PUT', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'id=' + id })
        .then(r => r.json()).then(d => { toast(d.message, !d.success); loadModeration(); });
}

function modAction(type, id, action) {
    fetch('/api/admin/moderation/action', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'targetType=' + type + '&targetId=' + id + '&action=' + action })
        .then(r => r.json()).then(d => { toast(d.message, !d.success); loadModeration(); });
}

// ============ 业务看板 ============
function loadBusiness() {
    fetch('/api/admin/stats/business').then(r => r.json()).then(d => {
        const b = document.getElementById('businessBody');
        if (!d.success) { b.innerHTML = '<div class="empty">加载失败</div>'; return; }
        const hot = (d.hotDestinations || []).map(x => '<tr><td>' + esc(x.destination) + '</td><td>' + x.cnt + '</td></tr>').join('');
        b.innerHTML =
            '<div class="metric-grid">' +
            '<div class="metric"><div class="num">' + d.totalUsers + '</div><div class="label">总用户</div></div>' +
            '<div class="metric"><div class="num">' + d.dau + '</div><div class="label">今日活跃(DAU)</div></div>' +
            '<div class="metric"><div class="num">' + d.totalPlans + '</div><div class="label">累计行程</div></div>' +
            '<div class="metric"><div class="num">' + d.todayPlans + '</div><div class="label">今日生成</div></div>' +
            '<div class="metric"><div class="num">' + d.totalPosts + '</div><div class="label">累计发帖</div></div>' +
            '<div class="metric"><div class="num">' + d.todayPosts + '</div><div class="label">今日发帖</div></div>' +
            '</div>' +
            '<h3 style="margin-top:24px">热门目的地 Top10</h3>' +
            (hot ? '<table><thead><tr><th>目的地</th><th>生成次数</th></tr></thead><tbody>' + hot + '</tbody></table>' : '<div class="empty">暂无数据</div>');
    }).catch(() => {});
}

// ============ 公告发布 ============
function loadAnnouncements() {
    fetch('/api/admin/announcements').then(r => r.json()).then(d => {
        const b = document.getElementById('announceList');
        const items = d.announcements || [];
        if (!items.length) { b.innerHTML = '<div class="empty">暂无公告</div>'; return; }
        b.innerHTML = items.map(a =>
            '<div class="ann-item"><b>' + esc(a.title) + '</b>' +
            '<span class="ann-meta">' + esc(a.username || '管理员') + ' · ' + (a.created_at || '') + '</span>' +
            '<div>' + esc(a.content) + '</div></div>').join('');
    }).catch(() => {});
}

function publishAnnouncement() {
    const title = document.getElementById('annTitle').value.trim();
    const content = document.getElementById('annContent').value.trim();
    if (!title || !content) { toast('标题和内容不能为空', true); return; }
    fetch('/api/admin/announcements', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'title=' + encodeURIComponent(title) + '&content=' + encodeURIComponent(content) })
        .then(r => r.json()).then(d => {
            toast(d.message, !d.success);
            if (d.success) { document.getElementById('annTitle').value = ''; document.getElementById('annContent').value = ''; loadAnnouncements(); }
        });
}

// ============ 数据中心（数据管线可视化） ============
/** 动态定价专用刷新：只跑 pricing.py（秒级），完成后刷新定价表 */
function runPricing() {
    const btn = document.getElementById('pricingRunBtn');
    if (btn) { btn.disabled = true; btn.textContent = '更新中...'; }
    fetch('/api/admin/pricing/run', { method: 'POST' }).then(r => r.json()).then(d => {
        toast(d.message || (d.success ? '定价已更新' : '更新失败'), !d.success);
        if (d.success) loadDataCenter();
    }).catch(() => toast('请求失败', true)).finally(() => {
        if (btn) { btn.disabled = false; btn.textContent = '🔄 更新数据'; }
    });
}

function loadDataCenter() {
    // 统计卡片
    fetch('/api/admin/data-stats').then(r => r.json()).then(d => {
        const s = document.getElementById('centerStats');
        if (!d.success) { s.innerHTML = '<div class="empty">加载失败</div>'; return; }
        const cards = [['景点', d.attractions, 'attractions'], ['美食', d.foods, 'foods'], ['酒店', d.hotels, 'hotels'], ['覆盖城市', d.cities, 'cities'], ['画像用户', d.profiles, 'profiles']];
        s.innerHTML = cards.map(c => '<div class="metric" style="cursor:pointer" title="点击查看明细" onclick="showDataDetail(\'' + c[2] + '\')"><div class="num">' + c[1] + '</div><div class="label">' + c[0] + '</div></div>').join('');
    }).catch(() => {});
    // 定价
    fetch('/api/admin/pricing').then(r => r.json()).then(d => {
        const b = document.getElementById('pricingBody');
        const rows = d.rows || [];
        if (!rows.length) { b.innerHTML = '<div class="empty">暂无数据（运行 data-pipeline/pricing.py）</div>'; return; }
        b.innerHTML = '<table style="width:100%;table-layout:fixed;border-collapse:collapse;font-size:12.5px"><colgroup><col style="width:19%"><col style="width:17%"><col style="width:17%"><col style="width:17%"><col style="width:30%"></colgroup><thead><tr style="color:#94a3b8;text-align:left;border-bottom:1px solid #334155"><th style="padding:6px 10px;text-align:center">城市</th><th style="padding:6px 10px;text-align:center">酒店/晚 现价/基准</th><th style="padding:6px 10px;text-align:center">餐饮/人 现价/基准</th><th style="padding:6px 10px;text-align:center">门票/次 现价/基准</th><th style="padding:6px 10px;text-align:center">定价系数</th></tr></thead><tbody>' +
            rows.map(x => { let f = ''; try { const j = JSON.parse(x.factors || '{}'); f = ['热度','节假日','天气'].map((n,i) => { const v = [j.heat||1, j.holiday||1, j.weather||1][i]; return '<span title="' + n + '" style="display:inline-block;margin:1px 4px 1px 0;padding:1px 6px;border-radius:9px;font-size:11px;white-space:nowrap;background:' + (v>1.001?'rgba(251,191,36,.12)':'rgba(100,116,139,.12)') + ';color:' + (v>1.001?'#fbbf24':'#64748b') + '">' + n + ' ×' + v.toFixed(2) + '</span>'; }).join(''); } catch(e) {} return '<tr style="border-bottom:1px solid rgba(51,65,85,.5)"><td style="padding:7px 10px;font-weight:600;text-align:center">' + esc(x.city) + '</td><td style="padding:7px 10px;text-align:center"><b style="color:#2FBF9F">¥' + x.hotel_price + '</b> <span style="color:#64748b;font-size:11px">/ ¥' + x.hotel_base + '</span></td><td style="padding:7px 10px;text-align:center"><b style="color:#2FBF9F">¥' + (x.food_price != null ? x.food_price : '-') + '</b> <span style="color:#64748b;font-size:11px">/ ¥' + (x.food_base != null ? x.food_base : '-') + '</span></td><td style="padding:7px 10px;text-align:center"><b style="color:#2FBF9F">¥' + x.ticket_price + '</b> <span style="color:#64748b;font-size:11px">/ ¥' + x.ticket_base + '</span></td><td style="padding:7px 10px;font-size:11px;text-align:center">' + f + '</td></tr>'; }).join('') + '</tbody></table>';
    }).catch(() => {});
    // 客流预测：ECharts 图表（城市趋势折线 / 单日对比柱状 / 城市×日期热力），右上角胶囊切换
    window._fcRows = null; window._fcDim = 'city'; window._fcDayIdx = 0;
    const nav0 = document.getElementById('fcDayNav'); if (nav0) nav0.style.display = 'none';
    fetch('/api/admin/forecast').then(r => r.json()).then(d => {
        window._fcRows = d.rows || [];
        renderFcTable();
    }).catch(() => {});

    /** 渲染当前图表（一次只显示一个） */
    let _fcChart = null;
    function renderFcTable() {
        const el = document.getElementById('fcChart');
        if (!el) return;
        const rows = window._fcRows || [];
        if (!rows.length) { el.innerHTML = '<div style="padding:60px;text-align:center;color:#64748b">暂无数据（点上方「🔄 更新数据」运行管线）</div>'; return; }
        if (typeof echarts === 'undefined') { el.innerHTML = '<div style="padding:60px;text-align:center;color:#64748b">图表库加载失败</div>'; return; }
        if (_fcChart) { _fcChart.dispose(); _fcChart = null; }
        _fcChart = echarts.init(el);
        const dayMap = ['周日','周一','周二','周三','周四','周五','周六'];
        const fmtDay = v => { const dt = new Date(String(v).replace(' ', 'T')); return (dt.getMonth()+1) + '/' + dt.getDate() + ' ' + dayMap[dt.getDay()]; };
        const dim = window._fcDim;

        if (dim === 'city') {
            // ===== 折线图：每城市一条线，X=7天，看趋势 =====
            const days = [...new Set(rows.map(x => x.day))].sort();
            const cities = [...new Set(rows.map(x => x.city))];
            const palette = ['#2FBF9F','#5B8FF9','#f59e0b','#ef4444','#9b59b6','#00c1d4','#ff9f43','#7bed9f','#e84393','#a4b0be','#f39c12','#1abc9c','#e67e22','#74b9ff','#badc58'];
            _fcChart.setOption({
                backgroundColor: 'transparent',
                tooltip: { trigger: 'axis' },
                legend: { type: 'scroll', top: 6, textStyle: { color: '#94a3b8', fontSize: 11 }, pageIconColor: '#94a3b8', pageIconSize: 11, pageTextStyle: { color: '#94a3b8' } },
                grid: { left: 42, right: 20, top: 58, bottom: 28 },
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
            // ===== 柱状图：单日各城市对比，标题两侧按钮翻日期 =====
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
                    data: drows.map(x => ({ value: x.v, itemStyle: { color: x.v >= 1.2 ? '#ef4444' : (x.v >= 0.8 ? '#f59e0b' : '#2FBF9F'), borderRadius: [4, 4, 0, 0] } })),
                    label: { show: true, position: 'top', color: '#94a3b8', fontSize: 10, formatter: p => p.value.toFixed(2) }
                }]
            });
            const nav = document.getElementById('fcDayNav');
            const lbl = document.getElementById('fcDayLabel');
            if (nav) nav.style.display = 'flex';
            if (lbl) lbl.textContent = fmtDay(dy);
        } else {
            // ===== 热力图：城市×日期，颜色=客流指数 =====
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
                visualMap: { min: 0.8, max: 1.6, calculable: true, orient: 'vertical', right: 8, top: 'center',
                    textStyle: { color: '#94a3b8' },
                    inRange: { color: ['#2FBF9F', '#f59e0b', '#ef4444'] } },
                series: [{ type: 'heatmap', data: data,
                    label: { show: true, color: '#e2e8f0', fontSize: 10, formatter: p => p.data[2].toFixed(2) },
                    itemStyle: { borderColor: '#0f172a', borderWidth: 2, borderRadius: 3 } }]
            });
        }
    }

    /** 维度切换（右上角胶囊按钮） */
    function fcSwitchDim(dim) {
        window._fcDim = dim;
        const nav = document.getElementById('fcDayNav');
        if (nav) nav.style.display = (dim === 'date') ? 'flex' : 'none';
        document.querySelectorAll('.fc-tab').forEach(t => {
            const on = t.dataset.dim === dim;
            t.style.color = on ? '#2FBF9F' : '#94a3b8';
            t.style.borderColor = on ? '#2FBF9F' : '#334155';
        });
        renderFcTable();
    }
    window.fcSwitchDim = fcSwitchDim;
    /** 单日对比：翻日期 */
    window._fcDayShift = function(delta) {
        const rows = window._fcRows || [];
        const total = [...new Set(rows.map(x => x.day))].length;
        window._fcDayIdx = ((window._fcDayIdx || 0) + delta + total) % total;
        renderFcTable();
    };

}

// ==================== 数据统计明细弹窗 ====================
/** 点击统计卡片：拉取明细并弹窗展示（带搜索过滤） */
function showDataDetail(type) {
    const mask = document.getElementById('dataDetailMask');
    const titleEl = document.getElementById('ddTitle');
    const bodyEl = document.getElementById('ddBody');
    const searchBox = document.getElementById('ddSearch');
    if (!mask || !bodyEl) return;
    titleEl.textContent = '加载中...';
    bodyEl.innerHTML = '<div class="empty">加载中...</div>';
    searchBox.value = '';
    searchBox.style.display = 'none';
    mask.style.display = 'flex';
    fetch('/api/admin/data-detail?type=' + type).then(r => r.json()).then(d => {
        if (!d.success) { titleEl.textContent = '明细'; bodyEl.innerHTML = '<div class="empty">加载失败：' + esc(d.error || '') + '</div>'; return; }
        titleEl.textContent = d.title + '（' + (d.rows || []).length + ' 条）';
        window._ddData = d;
        window._ddType = type;
        renderDetailTable('');
        if (['attractions','foods','hotels','cities','profiles'].includes(type)) searchBox.style.display = 'inline-block';
    }).catch(() => { titleEl.textContent = '明细'; bodyEl.innerHTML = '<div class="empty">网络异常</div>'; });
}
/** 关闭弹窗 */
function closeDataDetail() {
    document.getElementById('dataDetailMask').style.display = 'none';
}
/** 渲染明细（kw=搜索关键词；profiles 走标签云，其余走表格） */
function renderDetailTable(kw) {
    const bodyEl = document.getElementById('ddBody');
    const d = window._ddData || {};
    if (window._ddType === 'profiles') { renderProfilesCloud(kw); return; }
    let rows = d.rows || [];
    kw = (kw || '').trim().toLowerCase();
    if (kw) rows = rows.filter(r => r.some(cell => String(cell).toLowerCase().includes(kw)));
    if (!rows.length) { bodyEl.innerHTML = '<div class="empty">无匹配数据</div>'; return; }
    const cols = (d.columns || []).map(c => c === '画像标签(JSON)' ? '画像标签' : c);
    const editable = ['attractions','foods','hotels'].includes(window._ddType);
    let html = '<table style="width:100%;border-collapse:collapse;font-size:12.5px;color:#e2e8f0">'
        + '<thead><tr style="color:#94a3b8;text-align:left;border-bottom:1px solid #334155">'
        + cols.map(c => '<th style="padding:6px 10px">' + esc(c) + '</th>').join('')
        + (editable ? '<th style="padding:6px 10px;width:60px">操作</th>' : '') + '</tr></thead><tbody>';
    rows.forEach(r => {
        const rid = String(r[0]); // 首格=隐藏主键id
        const cells = r.slice(1);
        html += '<tr style="border-bottom:1px solid rgba(51,65,85,.5)">'
            + cells.map((v, i) => {
                let st = 'padding:6px 10px;max-width:260px;overflow-wrap:break-word;word-break:break-word';
                if (i === 0) st += ';font-weight:600;white-space:nowrap';
                return '<td style="' + st + '">' + esc(v) + '</td>';
            }).join('')
            + (editable ? '<td style="padding:6px 10px;text-align:center"><button class="btn-edit" title="编辑" style="padding:4px 8px;background:#1e293b;border:1px solid #334155;border-radius:6px;color:#2FBF9F;cursor:pointer;line-height:1" onclick="editDetailRow(\'' + window._ddType + '\',\'' + rid + '\',this)"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/></svg></button></td>' : '')
            + '</tr>';
    });
    bodyEl.innerHTML = html + '</tbody></table>';
}
/** 行内编辑：整行单元格变输入框 */
function editDetailRow(type, id, btn) {
    const tr = btn.closest('tr');
    const tds = [...tr.querySelectorAll('td')];
    tds.slice(0, -1).forEach(td => {
        const v = td.textContent;
        td.innerHTML = '<input value="' + esc(v) + '" style="width:92%;padding:4px 8px;background:#0b1222;border:1px solid #334155;border-radius:6px;color:#e2e8f0;font-size:12.5px">';
    });
    btn.textContent = '保存';
    btn.style.color = '#22c55e';
    btn.onclick = () => saveDetailRow(type, id, btn);
    const cancelBtn = document.createElement('button');
    cancelBtn.textContent = '取消';
    cancelBtn.style.cssText = 'padding:3px 10px;background:#1e293b;border:1px solid #334155;border-radius:6px;color:#94a3b8;cursor:pointer;font-size:12px;margin-left:4px';
    cancelBtn.onclick = () => showDataDetail(type);
    btn.parentNode.appendChild(cancelBtn);
}
/** 保存：逐字段提交 */
function saveDetailRow(type, id, btn) {
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
        tasks.push(fetch('/api/admin/data-detail/edit?type=' + type + '&id=' + id + '&field=' + f + '&value=' + encodeURIComponent(inp.value.trim())).then(r => r.json()));
    });
    Promise.all(tasks).then(results => {
        const ok = results.every(x => x && x.success);
        toast(ok ? '✅ 已保存' : '⚠️ 部分保存失败', !ok);
        showDataDetail(type);
    }).catch(() => { toast('保存失败', true); showDataDetail(type); });
}

/** 画像标签云渲染（弹窗内，配色与标签前缀一致：常去=青绿/风格=紫/偏好=蓝/爱收藏=粉/高采纳=黄） */
function renderProfilesCloud(kw) {
    const bodyEl = document.getElementById('ddBody');
    const rows = window._ddData.rows || [];
    kw = (kw || '').trim().toLowerCase();
    const list = kw ? rows.filter(r => r.some(cell => String(cell).toLowerCase().includes(kw))) : rows;
    if (!list.length) { bodyEl.innerHTML = '<div class="empty">无匹配数据</div>'; return; }
    // 标签类别→配色（常去/偏好/风格/其他）
    const CAT_STYLE = {
        '常去': ['rgba(45,212,191,.15)', '#2dd4bf'],
        '偏好': ['rgba(96,165,250,.15)', '#60a5fa'],
        '风格': ['rgba(168,85,247,.15)', '#a855f7']
    };
    list.forEach(r => {
        const uname = String(r[0]);
        const uid = esc(String(r[1]));
        let html = '<div style="margin-bottom:16px"><div style="font-size:13px;font-weight:600;color:#e2e8f0;margin-bottom:6px">👤 ' + esc(uname === '-' ? '用户 #' + uid : uname) + ' <span style="color:#64748b;font-weight:400">#' + uid + '</span></div>';
        try {
            const tags = JSON.parse(r[1]);
            const entries = Object.entries(tags).sort((a, b) => b[1] - a[1]);
            const maxV = entries.length ? entries[0][1] : 1;
            let cloud = '<div style="display:flex;flex-wrap:wrap;gap:6px">';
            entries.forEach(([k, v]) => {
                const cat = k.split('-')[0];
                const st = CAT_STYLE[cat] || ['rgba(148,163,184,.15)', '#94a3b8'];
                // 权重映射字号12~18px、透明度随权重加深
                const fs = 12 + Math.min(6, Math.round((v / maxV) * 6));
                const bg = st[0].replace('.15', '.' + (0.10 + Math.min(0.25, v / maxV * 0.25)).toFixed(2));
                cloud += '<span title="' + cat + '类 权重' + v + '" style="padding:3px 10px;border-radius:11px;font-size:' + fs + 'px;background:' + bg + ';color:' + st[1] + ';font-weight:' + (v / maxV > 0.66 ? 700 : 400) + '">'
                    + esc(k) + (v > 1 ? ' <b>×' + v + '</b>' : '') + '</span>';
            });
            cloud += '</div>';
            html += cloud;
        } catch (e) { html += '<span style="color:#94a3b8">' + esc(String(r[1])) + '</span>'; }
        html += '<div style="font-size:11px;color:#64748b;margin-top:4px;white-space:nowrap">更新时间 ' + esc(String(r[3])) + '</div></div>';
        bodyEl.insertAdjacentHTML('beforeend', html);
    });
}

// ============ 数据管线一键更新 ============
function runPipeline(btn) {
    if (btn) { btn.disabled = true; btn.textContent = '启动中...'; }
    const stopBtn0 = document.getElementById('pipeStopBtn');
    if (stopBtn0) stopBtn0.style.display = '';
    fetch('/api/admin/pipeline/run', { method: 'POST' }).then(r => r.json()).then(d => {
        toast(d.message || (d.success ? '已启动' : '失败'), !d.success);
        if (d.success) {
            if (btn) btn.textContent = '更新中...';
            setTimeout(() => pollPipeline(btn), 5000);
        } else if (btn) { btn.disabled = false; btn.textContent = '\ud83d\udd04 更新数据'; }
    }).catch(() => { toast('请求失败', true); if (btn) { btn.disabled = false; btn.textContent = '\ud83d\udd04 更新数据'; } });
}
function pollPipeline(btn) {
    fetch('/api/admin/pipeline/status').then(r => r.json()).then(d => {
        if (d.running) { setTimeout(() => pollPipeline(btn), 15000); }
        else {
            const done = Object.values(d.jobs || {}).some(v => String(v).startsWith('done'));
            toast(done ? '\u2705 数据更新完成' : '\u26a0\ufe0f 更新结束（有错误，看服务日志）', !done);
            if (btn) { btn.disabled = false; btn.textContent = '\ud83d\udd04 更新数据'; }
const sb = document.getElementById('pipeStopBtn'); if (sb) sb.style.display = 'none';
            loadDataCenter();
        }
    }).catch(() => { if (btn) { btn.disabled = false; btn.textContent = '\ud83d\udd04 更新数据'; } });
}

function stopPipeline(btn) {
    if (btn) { btn.disabled = true; }
    fetch('/api/admin/pipeline/stop', { method: 'POST' }).then(r => r.json()).then(d => {
        toast(d.message || '已发送停止请求', !d.success);
    }).catch(() => toast('请求失败', true)).finally(() => { if (btn) btn.disabled = false; });
}

// ============ ★模型配置热更（Java侧/Python侧） ============
const KEY_MASK = '••••••••'; // key已配置的占位显示（password框显示为圆点）
function loadModelConfig() {
    fetch('/api/admin/model-config').then(r => r.json()).then(d => {
        if (!d.success) return;
        document.getElementById('mcJavaUrl').value = d.java.url || '';
        document.getElementById('mcJavaName').value = d.java.name || '';
        // ★key显示逻辑：已配置生效 → 填入圆点占位（黑点）；未配置 → 空白
        document.getElementById('mcJavaKey').value = d.java.keyMasked ? KEY_MASK : '';
        document.getElementById('mcJavaKey').placeholder = d.java.keyMasked ? ('当前: ' + d.java.keyMasked + '（留空不修改）') : '未填写（留空=不修改）';
        // ★备用模型组
        const fb = d.fallback || {};
        document.getElementById('mcFbUrl').value = fb.url || '';
        document.getElementById('mcFbName').value = fb.name || '';
        document.getElementById('mcFbKey').value = fb.keyMasked ? KEY_MASK : '';
        document.getElementById('mcFbKey').placeholder = fb.keyMasked ? ('当前: ' + fb.keyMasked + '（留空不修改）') : '未填写（留空=不修改）';
        document.getElementById('mcPyUrl').value = d.python.url || '';
        document.getElementById('mcPyName').value = d.python.name || '';
        document.getElementById('mcPyKey').value = d.python.keyMasked ? KEY_MASK : '';
        document.getElementById('mcPyKey').placeholder = d.python.keyMasked ? ('当前: ' + d.python.keyMasked + '（留空不修改）') : '未填写（留空=不修改）';
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
    // ★key占位值(••••••••)视为未修改，不提交；只有用户真正输入新key才提交
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
    fetch('/api/admin/model-config', { method: 'POST', body })
        .then(r => r.json())
        .then(d => {
            const msg = document.getElementById('mcMsg');
            msg.textContent = d.message || (d.success ? '已保存' : '保存失败');
            msg.style.color = d.success ? '#2FBF9F' : '#E8797B';
            loadModelConfig();
        })
        .catch(() => { document.getElementById('mcMsg').textContent = '网络错误'; });
}
