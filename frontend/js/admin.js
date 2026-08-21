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
                '<td><span class="preview" title="' + esc(p.preview) + '">' + esc(p.preview) + '</span></td>' +
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
