/* ============================================================
   forum.js — 论坛首页（forum.html）：版块 Tab + 帖子流 + 搜索
   ============================================================ */

let currentBoard = null;  // null = 全部

// 登录检查 + 未读通知数
function init() {
    fetch('/api/check-login').then(r => r.json()).then(d => { if (!d.success) location.href = 'login.html'; });
    fetch('/api/notifications/unread-count').then(r => r.json()).then(d => {
        const badge = document.getElementById('notifBadge');
        if (d.success && d.count > 0) { badge.style.display = 'inline-block'; badge.textContent = d.count; }
    }).catch(() => {});
    loadBoards();
    reload();
}

// 加载版块 Tab
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
            return '<div class="post-card" onclick="location.href=\'post.html?id=' + p.id + '\'">' +
                '<h3>' + esc(p.title) + '</h3>' +
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
    }).catch(() => { document.getElementById('postList').innerHTML = '<div class="empty">加载失败</div>'; });
}

function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

function fmtTime(t) {
    if (!t) return '';
    const d = new Date(String(t).replace(' ', 'T'));
    return isNaN(d) ? String(t) : (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0');
}

init();
