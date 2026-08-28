/* ============================================================
   notifications.js — 通知中心（notifications.html）
   列表 + 标记已读 + SSE 实时推送
   ============================================================ */

const TYPE_ICON = { 1: '💬', 2: '👍', 3: '⭐', 4: '🤖', 5: '📢' };
const TYPE_COLOR = { 1: '#e8f4fd', 2: '#fdf0e8', 3: '#fff8e1', 4: '#f0f9f6', 5: '#fdecea' };

function fmtTime(t) {
    if (!t) return '';
    const d = new Date(String(t).replace(' ', 'T'));
    return isNaN(d) ? String(t) : (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0');
}

function load() {
    fetch('/api/notifications').then(r => r.json()).then(d => {
        const list = document.getElementById('ntList');
        const items = d.notifications || [];
        if (!items.length) { list.innerHTML = '<div class="empty">暂无通知</div>'; return; }
        list.innerHTML = items.map(n => {
            const type = n.type || 1;
            const click = n.link_url ? 'onclick="location.href=\'' + n.link_url + '\'"' : '';
            return '<div class="nt-item' + (n.is_read ? '' : ' unread') + '" ' + click + '>' +
                '<div class="nt-icon" style="background:' + (TYPE_COLOR[type] || '#f0f0f0') + '">' + (TYPE_ICON[type] || '🔔') + '</div>' +
                '<div class="nt-body">' +
                '<div class="nt-content">' + esc(n.content) + '</div>' +
                '<div class="nt-time">' + fmtTime(n.created_at) + (n.is_read ? '' : ' · 未读') + '</div>' +
                '</div></div>';
        }).join('');
    }).catch(() => { document.getElementById('ntList').innerHTML = '<div class="empty">加载失败</div>'; });
}

function readAll() {
    fetch('/api/notifications/read-all', { method: 'PUT' }).then(() => load());
}

function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

// SSE 实时推送：收到新通知时刷新列表
function subscribe() {
    if (typeof EventSource === 'undefined') return;
    const es = new EventSource('/api/notifications/stream');
    es.addEventListener('notification', function () { load(); });
}

load();
subscribe();
