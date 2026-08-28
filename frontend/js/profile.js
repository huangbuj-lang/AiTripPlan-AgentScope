/* ============================================================
   profile.js — 个人中心（profile.html）逻辑
   Tab：资料 / 旅行偏好 / 统计 / 账号与安全
   ============================================================ */

const STYLES = ['穷游', '亲子', '奢华', '徒步', '美食', '自驾', '度假'];
let selectedStyles = [];   // 当前选中的风格标签
let currentBudget = null;  // 当前预算档
let currentPace = null;    // 当前节奏

// 登录检查
function checkLogin() {
    fetch('/api/check-login')
        .then(r => r.json())
        .then(data => {
            if (!data.success) { location.href = 'login.html'; return; }
            document.getElementById('pUsername').textContent = data.username;
        })
        .catch(() => { location.href = 'login.html'; });
}

// Tab 切换
function initTabs() {
    document.querySelectorAll('.profile-tabs button').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.profile-tabs button').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            ['info', 'prefs', 'stats', 'account'].forEach(t => {
                document.getElementById('tab-' + t).style.display = (t === btn.dataset.tab) ? 'block' : 'none';
            });
            if (btn.dataset.tab === 'stats') loadStats();
        });
    });
}

// 渲染风格标签
function renderStyleTags(selected) {
    const box = document.getElementById('styleTags');
    box.innerHTML = '';
    STYLES.forEach(s => {
        const t = document.createElement('span');
        t.className = 'tag' + (selected.includes(s) ? ' selected' : '');
        t.textContent = s;
        t.onclick = () => {
            const idx = selectedStyles.indexOf(s);
            if (idx >= 0) selectedStyles.splice(idx, 1); else selectedStyles.push(s);
            renderStyleTags(selectedStyles);
        };
        box.appendChild(t);
    });
}

// 渲染分段按钮（预算/节奏）
function renderSeg(attr, value, holder) {
    document.querySelectorAll(`.seg-btn[${attr}]`).forEach(b => {
        b.classList.toggle('active', parseInt(b.getAttribute(attr)) === value);
    });
}

// 加载资料
function loadInfo() {
    fetch('/api/profile')
        .then(r => r.json())
        .then(d => {
            if (!d.success) { showMsg('infoMsg', d.message, false); return; }
            document.getElementById('pMeta').textContent =
                '注册于 ' + (d.create_time ? String(d.create_time).slice(0, 10) : '') + ' · ' + (d.role === 'admin' ? '管理员' : '普通用户');
            document.getElementById('nickname').value = d.nickname || '';
            document.getElementById('avatarUrl').value = d.avatar_url || '';
            document.getElementById('email').value = d.email || '';
            document.getElementById('bio').value = d.bio || '';
            renderAvatar(d.avatar_url);
        })
        .catch(() => showMsg('infoMsg', '加载失败', false));
}

function renderAvatar(url) {
    const box = document.getElementById('avatarBox');
    if (url) { box.innerHTML = '<img src="' + url + '" alt="">'; }
}

// 保存资料
function saveInfo(btn) {
    const restore = btnBusy(btn, '保存中...');
    const body = new URLSearchParams({
        nickname: document.getElementById('nickname').value,
        avatarUrl: document.getElementById('avatarUrl').value,
        email: document.getElementById('email').value,
        bio: document.getElementById('bio').value
    });
    fetch('/api/profile', { method: 'PUT', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body })
        .then(r => r.json())
        .then(d => {
            showMsg('infoMsg', d.message, d.success);
            if (d.success) { loadInfo(); }
            restore();
        })
        .catch(() => { showMsg('infoMsg', '保存失败', false); restore(); });
}

// 加载偏好
function loadPrefs() {
    fetch('/api/profile/preferences')
        .then(r => r.json())
        .then(d => {
            if (!d.success) return;
            selectedStyles = safeParseArray(d.travel_style);
            currentBudget = d.budget_level || null;
            currentPace = d.pace || null;
            renderStyleTags(selectedStyles);
            renderSeg('data-budget', currentBudget, 'budget');
            renderSeg('data-pace', currentPace, 'pace');
            document.getElementById('avoidList').value = safeParseArray(d.avoid_list).join(', ');
            document.getElementById('useByDefault').checked = (d.use_by_default !== 0);
        });
}

function safeParseArray(v) {
    if (Array.isArray(v)) return v;
    if (typeof v === 'string' && v.trim()) {
        try { const a = JSON.parse(v); return Array.isArray(a) ? a : []; } catch (e) { return []; }
    }
    return [];
}

// 保存偏好
function savePrefs(btn) {
    const restore = btnBusy(btn, '保存中...');
    const avoid = document.getElementById('avoidList').value.split(/[,，]/).map(s => s.trim()).filter(Boolean);
    const body = new URLSearchParams({
        travelStyle: JSON.stringify(selectedStyles),
        avoidList: JSON.stringify(avoid),
        useByDefault: document.getElementById('useByDefault').checked ? 1 : 0
    });
    if (currentBudget) body.append('budgetLevel', currentBudget);
    if (currentPace) body.append('pace', currentPace);
    fetch('/api/profile/preferences', { method: 'PUT', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body })
        .then(r => r.json())
        .then(d => { showMsg('prefsMsg', d.message, d.success); restore(); })
        .catch(() => { showMsg('prefsMsg', '保存失败', false); restore(); });
}

// 加载统计
function loadStats() {
    fetch('/api/my-plans')
        .then(r => r.json())
        .then(d => {
            const plans = (d.plans || []);
            document.getElementById('stTrips').textContent = plans.length;
            const cities = new Set(plans.map(p => p.city).filter(Boolean));
            document.getElementById('stCities').textContent = cities.size;
            const days = plans.reduce((s, p) => s + (parseInt(p.days) || 0), 0);
            document.getElementById('stDays').textContent = days;
        })
        .catch(() => {});
}

// 修改密码
function changePwd(btn) {
    const restore = btnBusy(btn, '提交中...');
    const oldPwd = document.getElementById('oldPwd').value;
    const newPwd = document.getElementById('newPwd').value;
    if (!oldPwd || !newPwd) { showMsg('pwdMsg', '请填写新旧密码', false); return; }
    const body = new URLSearchParams({ oldPassword: oldPwd, newPassword: newPwd });
    fetch('/api/profile/password', { method: 'PUT', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body })
        .then(r => r.json())
        .then(d => {
            showMsg('pwdMsg', d.message, d.success);
            if (d.success) { document.getElementById('oldPwd').value = ''; document.getElementById('newPwd').value = ''; }
            restore();
        })
        .catch(() => { showMsg('pwdMsg', '修改失败', false); restore(); });
}

// 注销账号
function deleteAccount() {
    if (!confirm('确定注销账号吗？此操作不可撤销。')) return;
    fetch('/api/profile', { method: 'DELETE' })
        .then(r => r.json())
        .then(() => { alert('账号已注销'); location.href = 'login.html'; })
        .catch(() => alert('注销失败'));
}

/* 按钮忙碌态：点击即禁用并显示转圈，完成后恢复（防止连点重复提交） */
function btnBusy(btn, busyText) {
    if (!btn) return () => {};
    const original = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> ' + (busyText || '处理中...');
    return () => { btn.disabled = false; btn.innerHTML = original; };
}
// 提示消息
function showMsg(id, text, ok) {
    const el = document.getElementById(id);
    el.textContent = (ok ? '✅ ' : '❌ ') + text;
    el.className = 'form-msg ' + (ok ? 'ok' : 'err');
}

// 分段按钮点击
function initSegButtons() {
    document.querySelectorAll('.seg-btn[data-budget]').forEach(b => {
        b.addEventListener('click', () => { currentBudget = parseInt(b.dataset.budget); renderSeg('data-budget', currentBudget, 'budget'); });
    });
    document.querySelectorAll('.seg-btn[data-pace]').forEach(b => {
        b.addEventListener('click', () => { currentPace = parseInt(b.dataset.pace); renderSeg('data-pace', currentPace, 'pace'); });
    });
}

// 初始化
checkLogin();
initTabs();
initSegButtons();
loadInfo();
loadPrefs();
