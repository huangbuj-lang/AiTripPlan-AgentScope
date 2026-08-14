/* ============================================================
   post.js — 帖子详情页（post.html）
   内容渲染 / 点赞收藏 / 同款行程复制 / 两级评论
   ============================================================ */

const postId = new URLSearchParams(location.search).get('id');
let replyTo = null; // 当前回复的评论（null=回复帖子）

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

// 加载帖子详情
function loadPost() {
    fetch('/api/forum/posts/' + postId).then(r => r.json()).then(d => {
        if (!d.success) { document.getElementById('postCard').innerHTML = '<div class="empty">' + d.message + '</div>'; return; }
        const p = d.post;
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
            '<h1>' + esc(p.title) + '</h1>' +
            '<div class="pd-meta">' +
            '<span><i class="fa-regular fa-user"></i> ' + esc(p.author || '') + '</span>' +
            '<span><i class="fa-regular fa-clock"></i> ' + fmtTime(p.created_at) + '</span>' +
            '<span><i class="fa-regular fa-eye"></i> ' + (p.view_count || 0) + '</span>' +
            (p.destination ? '<span><i class="fa-solid fa-location-dot"></i> ' + esc(p.destination) + '</span>' : '') +
            (p.travel_date ? '<span><i class="fa-regular fa-calendar"></i> ' + esc(p.travel_date) + '</span>' : '') +
            '</div>' +
            '<div class="pd-content">' + DOMPurify.sanitize(renderMarkdown(p.content)) + '</div>' + trip +
            '<div class="pd-actions">' +
            '<button class="act-btn" id="likeBtn" onclick="toggleLike()"><i class="fa-regular fa-heart"></i> 赞 ' + (p.like_count || 0) + '</button>' +
            '<button class="act-btn" id="favBtn" onclick="toggleFav()"><i class="fa-regular fa-star"></i> 收藏</button>' +
            '</div>';
    }).catch(() => { document.getElementById('postCard').innerHTML = '<div class="empty">加载失败</div>'; });
}

function copyTrip() {
    fetch('/api/forum/posts/' + postId + '/copy-trip', { method: 'POST' }).then(r => r.json()).then(d => alert(d.message || '复制成功'));
}

function toggleLike() {
    fetch('/api/forum/posts/' + postId + '/like', { method: 'POST' }).then(r => r.json()).then(d => {
        const btn = document.getElementById('likeBtn');
        btn.classList.toggle('active', d.liked);
        btn.innerHTML = '<i class="fa-regular fa-heart"></i> 赞';
        loadPost();
    });
}

function toggleFav() {
    fetch('/api/forum/posts/' + postId + '/favorite', { method: 'POST' }).then(r => r.json()).then(d => {
        const btn = document.getElementById('favBtn');
        btn.classList.toggle('active', d.favorited);
        alert(d.favorited ? '已收藏' : '已取消收藏');
    });
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
            return '<div class="comment' + (isReply ? ' reply' : '') + '">' +
                '<div class="c-head"><b>' + esc(c.author) + '</b>' +
                (c.reply_author ? ' 回复 <b>' + esc(c.reply_author) + '</b>' : '') +
                '<span style="color:#b2bec3"> · ' + fmtTime(c.created_at) + '</span>' +
                '<span class="c-reply" onclick="setReply(' + c.id + ', ' + c.user_id + ', \'' + esc(c.author) + '\')">回复</span></div>' +
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
    const body = new URLSearchParams({ content: content });
    if (replyTo) { body.append('parentId', replyTo.parentId); body.append('replyUserId', replyTo.userId); }
    fetch('/api/forum/posts/' + postId + '/comments', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body
    }).then(r => r.json()).then(d => {
        if (d.success) { input.value = ''; replyTo = null; input.placeholder = '写下你的评论...'; loadComments(); }
        else alert(d.message);
    });
}

loadPost();
loadComments();
