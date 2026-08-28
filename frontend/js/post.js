/* ============================================================
   post.js — 帖子详情页（post.html）
   内容渲染 / 点赞收藏 / 同款行程复制 / 两级评论
   ============================================================ */

const postId = new URLSearchParams(location.search).get('id');
let replyTo = null; // 当前回复的评论（null=回复帖子）
let currentUserId = null;   // 当前登录用户 ID
let postAuthorId = null;    // 楼主 ID
let boardId = null;         // 版块 ID（判断是否问答版块可请求 AI 答主）
let likedByMe = false;      // 本会话内是否已点赞（点赞接口只返回切换结果，用于渲染实心/空心）
let favedByMe = false;      // 本会话内是否已收藏
let myRole = 'user';        // 当前登录者角色（admin 可删任何帖子）

// 获取当前登录用户（用于判断是否楼主 / AI 采纳 / 举报权限 / 删帖权限）
let loginReady = fetch('/api/check-login').then(r => r.json()).then(d => {
    if (d.success) { currentUserId = d.userId; myRole = d.role || 'user'; }
    return d;
});

function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

function fmtTime(t) {
    if (!t) return '';
    const d = new Date(String(t).replace(' ', 'T'));
    return isNaN(d) ? String(t) : (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0');
}

// 简易 markdown 渲染（服务端存 markdown 原文，前端 DOMPurify 消毒）
function renderMarkdown(text) {
    if (!text) return '';
    let html = esc(text);
    const lines = html.split('\n');
    let out = '', inUl = false, inOl = false;
    lines.forEach(t => {
        t = t.trim();
        if (/^#{1,4}\s*\S/.test(t)) {
            if (inUl) { out += '</ul>'; inUl = false; }
            if (inOl) { out += '</ol>'; inOl = false; }
            const lv = Math.min(t.match(/^#+/)[0].length, 4);
            out += '<h' + lv + '>' + inline(t.replace(/^#+\s*/, '')) + '</h' + lv + '>';
        } else if (/^[-*•]\s/.test(t)) {
            if (!inUl) { out += '<ul>'; inUl = true; }
            out += '<li>' + inline(t.replace(/^[-*•]\s/, '')) + '</li>';
        } else if (/^\d+[.、]\s/.test(t)) {
            if (!inOl) { out += '<ol>'; inOl = true; }
            out += '<li>' + inline(t.replace(/^\d+[.、]\s/, '')) + '</li>';
        } else if (t === '') {
            if (inUl) { out += '</ul>'; inUl = false; }
            if (inOl) { out += '</ol>'; inOl = false; }
        } else {
            if (inUl) { out += '</ul>'; inUl = false; }
            if (inOl) { out += '</ol>'; inOl = false; }
            out += '<p>' + inline(t) + '</p>';
        }
    });
    if (inUl) out += '</ul>';
    if (inOl) out += '</ol>';
    return out;
}

function inline(s) {
    return s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/`([^`]+)`/g, '<code>$1</code>');
}

// 内嵌行程简表
function renderTripPreview(plan) {
    const sections = plan.sections || [];
    const lines = [];
    sections.forEach(sec => {
        (sec.items || []).forEach(item => {
            const txt = item.time ? (item.time + ' ' + (item.place || item.activity || '')) : (item.place || item.activity || item.name || '');
            if (txt) lines.push(txt);
        });
    });
    return lines.slice(0, 6).map(l => '<li>' + esc(l) + '</li>').join('');
}

// 加载帖子详情（先等登录态，删除键的显隐才不会有时序抖动）
function loadPost() {
    loginReady.then(() => fetch('/api/forum/posts/' + postId)).then(r => r.json()).then(d => {
        if (!d.success) { document.getElementById('postCard').innerHTML = '<div class="empty">' + d.message + '</div>'; return; }
        const p = d.post;
        postAuthorId = p.user_id;
        boardId = p.board_id;
        let trip = '';
        if (p.trip_plan_json) {
            let plan = null;
            try { plan = typeof p.trip_plan_json === 'string' ? JSON.parse(p.trip_plan_json) : p.trip_plan_json; } catch (e) {}
            const title = plan && plan.title ? plan.title : '行程';
            trip = '<div class="trip-embed"><h4><i class="fa-solid fa-route"></i> 内嵌行程：' + esc(title) + '</h4>' +
                (plan ? '<ul style="margin:8px 0 12px 18px;font-size:13px;color:#636e72">' + renderTripPreview(plan) + '</ul>' : '') +
                '<button class="btn-primary" onclick="copyTrip()"><i class="fa-solid fa-copy"></i> 同款行程一键复制</button></div>';
        }
        document.getElementById('postCard').innerHTML =
            '<div style="display:flex;align-items:flex-start;justify-content:space-between;gap:12px">' +
            '<h1 style="flex:1">' + esc(p.title) + '</h1>' +
            (currentUserId != null && (String(currentUserId) === String(postAuthorId) || myRole === 'admin')
                ? '<button class="pd-del" onclick="delPost()" title="' + (String(currentUserId) === String(postAuthorId) ? '删除帖子（不可恢复）' : '管理员下架（可恢复）') + '"><i class="fa-regular fa-trash-can"></i> 删除</button>'
                : '') +
            '</div>' +
            '<div class="pd-meta">' +
            '<span><i class="fa-regular fa-user"></i> ' + esc(p.author || '') + '</span>' +
            '<span><i class="fa-regular fa-clock"></i> ' + fmtTime(p.created_at) + '</span>' +
            '<span><i class="fa-regular fa-eye"></i> ' + (p.view_count || 0) + '</span>' +
            (p.destination ? '<span><i class="fa-solid fa-location-dot"></i> ' + esc(p.destination) + '</span>' : '') +
            (p.travel_date ? '<span><i class="fa-regular fa-calendar"></i> ' + esc(p.travel_date) + '</span>' : '') +
            '</div>' +
            '<div class="pd-content">' + DOMPurify.sanitize(renderMarkdown(p.content)) + '</div>' + trip +
            '<div class="pd-actions">' +
            '<button class="act-btn' + (likedByMe ? ' active' : '') + '" id="likeBtn" onclick="toggleLike()"><i class="fa-' + (likedByMe ? 'solid' : 'regular') + ' fa-heart"></i> 赞 ' + (p.like_count || 0) + '</button>' +
            '<button class="act-btn' + (favedByMe ? ' active' : '') + '" id="favBtn" onclick="toggleFav()"><i class="fa-' + (favedByMe ? 'solid' : 'regular') + ' fa-star"></i> 收藏</button>' +
            (boardId === 2 ? '<button class="act-btn" onclick="requestAi()"><i class="fa-solid fa-robot"></i> 请求 AI 答主</button>' : '') +
            '<button class="act-btn" onclick="reportPost()"><i class="fa-solid fa-flag"></i> 举报</button>' +
            '</div>';
    }).catch(() => { document.getElementById('postCard').innerHTML = '<div class="empty">加载失败</div>'; });
}

function copyTrip() {
    fetch('/api/forum/posts/' + postId + '/copy-trip', { method: 'POST' }).then(r => r.json()).then(d => alert(d.message || '复制成功'));
}

/* 点赞：实心/空心即时反馈 + 忙碌态防连点；数量由 loadPost 重渲染刷新 */
function toggleLike() {
    const btn = document.getElementById('likeBtn');
    if (!btn || btn.disabled) return;
    btn.disabled = true;
    fetch('/api/forum/posts/' + postId + '/like', { method: 'POST' })
        .then(r => r.json())
        .then(d => {
            if (d.success) { likedByMe = !!d.liked; loadPost(); } /* 状态留存，重渲染后心形保持一致 */
            else alert(d.message || '操作失败');
        })
        .catch(() => alert('网络异常，请重试'))
        .finally(() => { btn.disabled = false; });
}

/* 收藏：实心/空心星星即时反馈，去掉打断式弹窗 */
function toggleFav() {
    const btn = document.getElementById('favBtn');
    if (!btn || btn.disabled) return;
    btn.disabled = true;
    fetch('/api/forum/posts/' + postId + '/favorite', { method: 'POST' })
        .then(r => r.json())
        .then(d => {
            if (d.success) {
                favedByMe = !!d.favorited;
                btn.classList.toggle('active', favedByMe);
                btn.innerHTML = '<i class="fa-' + (favedByMe ? 'solid' : 'regular') + ' fa-star"></i> 收藏';
            } else {
                alert(d.message || '操作失败');
            }
        })
        .catch(() => alert('网络异常，请重试'))
        .finally(() => { btn.disabled = false; });
}

// 加载评论
function loadComments() {
    fetch('/api/forum/posts/' + postId + '/comments').then(r => r.json()).then(d => {
        const list = document.getElementById('commentList');
        const comments = d.comments || [];
        document.getElementById('commentCount').textContent = comments.length;
        if (!comments.length) { list.innerHTML = '<div class="empty" style="padding:20px">暂无评论，抢沙发！</div>'; return; }
        list.innerHTML = comments.map(c => {
            const isReply = c.parent_id != null;
            const isAi = c.is_ai === 1;
            const author = isAi ? '🤖 AI答主' : esc(c.author);
            const aiBadge = isAi ? '<span class="ai-badge">AI 生成</span>' : '';
            const adopted = c.is_adopted === 1 ? '<span class="adopt-badge">已采纳</span>' : '';
            const adoptBtn = (isAi && !c.is_adopted && currentUserId != null && currentUserId == postAuthorId)
                ? '<button class="adopt-btn" onclick="adoptComment(' + c.id + ')">采纳</button>' : '';
            const replyBtn = isAi ? '' : '<span class="c-reply" onclick="setReply(' + c.id + ', ' + c.user_id + ', \'' + esc(c.author) + '\')">回复</span>';
            return '<div class="comment' + (isReply ? ' reply' : '') + (isAi ? ' ai' : '') + '">' +
                '<div class="c-head"><b>' + author + '</b>' + aiBadge + adopted +
                (c.reply_author ? ' 回复 <b>' + esc(c.reply_author) + '</b>' : '') +
                '<span style="color:#b2bec3"> · ' + fmtTime(c.created_at) + '</span>' +
                replyBtn + adoptBtn + '</div>' +
                '<div class="c-body">' + esc(c.content) + '</div></div>';
        }).join('');
    });
}

function setReply(parentId, userId, author) {
    replyTo = { parentId: parentId, userId: userId };
    document.getElementById('commentInput').placeholder = '回复 @' + author + '：';
    document.getElementById('commentInput').focus();
}

function submitComment() {
    const input = document.getElementById('commentInput');
    const content = input.value.trim();
    if (!content) return;
    const sendBtn = document.querySelector('.comment-input button');
    if (sendBtn && sendBtn.disabled) return; /* 防重复提交 */
    if (sendBtn) sendBtn.disabled = true;
    const body = new URLSearchParams({ content: content });
    if (replyTo) { body.append('parentId', replyTo.parentId); body.append('replyUserId', replyTo.userId); }
    fetch('/api/forum/posts/' + postId + '/comments', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body
    }).then(r => r.json()).then(d => {
        if (d.success) { input.value = ''; replyTo = null; input.placeholder = '写下你的评论...'; loadComments(); }
        else alert(d.message);
    }).catch(() => alert('评论发送失败，请重试'))
      .finally(() => { if (sendBtn) sendBtn.disabled = false; });
}

function requestAi() {
    if (!confirm('请求 AI 答主为你的问题生成回答？（异步生成，稍后刷新查看）')) return;
    fetch('/api/forum/answers/ai', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ postId: postId })
    }).then(r => r.json()).then(d => {
        alert(d.message || '已受理');
        setTimeout(loadComments, 3000); // 生成中，稍后刷新
    });
}

function adoptComment(id) {
    fetch('/api/forum/comments/' + id + '/adopt', { method: 'POST' }).then(r => r.json()).then(d => {
        alert(d.message || '已采纳');
        if (d.success) loadComments();
    });
}

function reportPost() {
    const reason = prompt('请填写举报理由：');
    if (!reason || !reason.trim()) return;
    fetch('/api/forum/reports', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ targetType: 1, targetId: postId, reason: reason.trim() })
    }).then(r => r.json()).then(d => alert(d.message || '已提交'));
}

/** 删帖：楼主→真删；管理员删别人的→管理端下架（status=0 可恢复），成功后回论坛列表 */
function delPost() {
    const isMine = currentUserId != null && String(currentUserId) === String(postAuthorId);
    if (!confirm(isMine ? '确认删除这篇帖子？删除后不可恢复。' : '（管理员）下架该帖子？可在管理端恢复。')) return;
    const url = isMine ? '/api/forum/posts/' + postId
                       : '/api/admin/moderation/action?targetType=1&targetId=' + postId + '&action=hide';
    fetch(url, { method: isMine ? 'DELETE' : 'POST' })
        .then(r => r.json()).then(d => {
            if (d.success) { location.href = 'forum.html'; }
            else alert(d.message || '删除失败');
        }).catch(() => alert('请求失败，请重试'));
}

loadPost();
loadComments();
