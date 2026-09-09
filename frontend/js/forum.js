/* ============================================================ forum.js — 论坛首页（forum.html）：版块 Tab + 帖子流 + 搜索 ============================================================ */

let currentBoard = null;  // null = 全部
let me = { id: null, role: 'user' }; // 当前登录者（控制删帖键可见性）

//登录检查 + 未读通知数
function init() {
    fetch('/api/check-login').then(r => r.json()).then(d => {
        if (!d.success) { location.href = 'login.html'; return; }
        me = { id: d.userId, role: d.role || 'user' }; // 记住身份：发帖人只看自己的删除键，管理员看所有人的
        renderDelBtns(); // 登录态回来晚于列表时，补渲染删除键
    });
    fetch('/api/notifications/unread-count').then(r => r.json()).then(d => {
        const badge = document.getElementById('notifBadge');
        if (d.success && d.count > 0) { badge.style.display = 'inline-block'; badge.textContent = d.count; }
    }).catch(() => {});
    loadBoards();
    reload();
}

/** 根据当前身份切换每张帖子卡的删除键显隐（登录信息晚到时的补救渲染） */
function renderDelBtns() {
    document.querySelectorAll('.post-del[data-pid]').forEach(btn => {
        btn.style.display = (btn.dataset.uid == me.id || me.role === 'admin') ? '' : 'none';
    });
}

//加载版块 Tab
function loadBoards() {
    fetch('/api/forum/boards').then(r => r.json()).then(d => {
        if (!d.success) return;
        const box = document.getElementById('boardTabs');
        let html = '<div class="board-tab' + (currentBoard === null ? ' active' : '') + '" onclick="selectBoard(null)">全部</div>';
        (d.boards || []).forEach(b => {
            html += '<div class="board-tab' + (currentBoard === b.id ? ' active' : '') + '" onclick="selectBoard(' + b.id + ')">' + b.name + '</div>';
        });
        box.innerHTML = html;
    }).catch(() => {});
}

function selectBoard(id) {
    currentBoard = id;
    loadBoards();
    reload();
}

//发帖：带上当前浏览的版块，发帖页自动预选（在问答版发帖→AI答主入口不再踩空）
function goNewPost() {
    location.href = currentBoard ? 'new-post.html?boardId=' + currentBoard : 'new-post.html';
}

function reload() {
    const q = document.getElementById('searchInput').value.trim();
    const sort = document.getElementById('sortSelect').value;
    let url = '/api/forum/posts?sort=' + sort;
    if (currentBoard !== null) url += '&board=' + currentBoard;
    if (q) url += '&q=' + encodeURIComponent(q);
    fetch(url).then(r => r.json()).then(d => {
        const list = document.getElementById('postList');
        if (!d.success || !(d.posts || []).length) {
            list.innerHTML = '<div class="empty">暂无帖子，来发第一帖吧！</div>';
            return;
        }
        list.innerHTML = d.posts.map(p => {
            const dest = p.destination ? '<span class="post-badge"><i class="fa-solid fa-location-dot"></i> ' + p.destination + '</span>' : '';
            const trip = p.has_trip ? '<span class="post-badge"><i class="fa-solid fa-route"></i> 含行程</span>' : '';
            //删除键：默认隐藏，等登录信息回来后按身份显示（自己的帖子 or 管理员）
            const del = '<span class="post-del" data-pid="' + p.id + '" data-uid="' + (p.user_id || '') + '" style="display:none" onclick="event.stopPropagation();delPost(' + p.id + ')"><i class="fa-regular fa-trash-can"></i></span>';
            return '<div class="post-card" onclick="location.href=\'post.html?id=' + p.id + '\'">' +
                '<h3>' + esc(p.title) + del + '</h3>' +
                '<div style="margin-bottom:6px">' + dest + trip +
                '<span class="post-badge" style="background:#f0f0f0;color:#636e72">' + esc(p.board_name || '') + '</span></div>' +
                '<div class="meta">' +
                '<span><i class="fa-regular fa-user"></i> ' + esc(p.author || '') + '</span>' +
                '<span><i class="fa-regular fa-heart"></i> ' + (p.like_count || 0) + '</span>' +
                '<span><i class="fa-regular fa-comment"></i> ' + (p.comment_count || 0) + '</span>' +
                '<span><i class="fa-regular fa-eye"></i> ' + (p.view_count || 0) + '</span>' +
                '<span><i class="fa-regular fa-clock"></i> ' + fmtTime(p.created_at) + '</span>' +
                '</div></div>';
        }).join('');
        renderDelBtns(); // 列表每次重渲染后，按当前身份补一次显隐
    }).catch(() => { document.getElementById('postList').innerHTML = '<div class="empty">加载失败</div>'; });
}

/** 删帖：本人→真删接口； */
function delPost(id) {
    const btn = document.querySelector('.post-del[data-pid="' + id + '"]');
    const isMine = me.id != null && btn && String(btn.dataset.uid) === String(me.id);
    if (!confirm('确认删除该帖子？' + (isMine ? '删除后不可恢复。' : '（管理员下架，可恢复）'))) return;
    const url = isMine ? '/api/forum/posts/' + id
                       : '/api/admin/moderation/action?targetType=1&targetId=' + id + '&action=hide';
    fetch(url, { method: isMine ? 'DELETE' : 'POST' })
        .then(r => r.json()).then(d => {
            toast(d.message || (d.success ? '已删除' : '删除失败'), !d.success);
            if (d.success) reload();
        }).catch(() => toast('请求失败', true));
}

/* 轻提示（2.5s 自动消失），避免只有 alert 一种反馈 */
let toastTimer = null;
function toast(msg, isErr) {
    let el = document.getElementById('forumToast');
    if (!el) {
        el = document.createElement('div');
        el.id = 'forumToast';
        el.style.cssText = 'position:fixed;top:70px;left:50%;transform:translateX(-50%);padding:10px 22px;border-radius:8px;color:#fff;font-size:14px;z-index:9999;box-shadow:0 4px 16px rgba(0,0,0,.18);transition:opacity .3s';
        document.body.appendChild(el);
    }
    el.style.background = isErr ? '#e17055' : '#00b894';
    el.textContent = msg;
    el.style.opacity = '1';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.style.opacity = '0'; }, 2500);
}

function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

function fmtTime(t) {
    if (!t) return '';
    const d = new Date(String(t).replace(' ', 'T'));
    return isNaN(d) ? String(t) : (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0');
}

init();
