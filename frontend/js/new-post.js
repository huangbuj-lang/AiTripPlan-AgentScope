/* ============================================================ new-post.js — 发帖页（new-post.html） ============================================================ */

//登录检查 + 加载版块/我的行程
function init() {
    fetch('/api/check-login').then(r => r.json()).then(d => { if (!d.success) location.href = 'login.html'; });
    fetch('/api/forum/boards').then(r => r.json()).then(d => {
        const sel = document.getElementById('boardId');
        sel.innerHTML = (d.boards || []).map(b => '<option value="' + b.id + '">' + b.name + '</option>').join('');
        //从版块页带参进入时自动预选（如 ?boardId=2 旅行问答）
        const want = new URLSearchParams(location.search).get('boardId');
        if (want && sel.querySelector('option[value="' + want + '"]')) {
            sel.value = want;
            sel.dispatchEvent(new Event('change')); // 触发结伴字段显隐 + AI提示更新
        }
    });
    fetch('/api/my-plans').then(r => r.json()).then(d => {
        const sel = document.getElementById('tripId');
        sel.innerHTML = '<option value="">不关联</option>' + (d.plans || []).map(p =>
            '<option value="' + p.id + '">' + (p.city || '未知') + ' · ' + (p.days || '?') + '天</option>').join('');
    });
}

//结伴同行（boardId=3）显示目的地+日期字段
document.getElementById('boardId').addEventListener('change', function () {
    document.getElementById('mateFields').style.display = (this.value === '3') ? 'block' : 'none';
    document.getElementById('aiHint').style.display = (this.value === '2') ? 'block' : 'none';
});

function submit(btn) {
    const title = document.getElementById('title').value.trim();
    const content = document.getElementById('content').value.trim();
    const boardId = document.getElementById('boardId').value;
    if (!title || !content) { setMsg('请填写标题和内容', false); return; }
    //防重复提交：点击即禁用并转圈，成功跳转或失败恢复
    if (btn && btn.disabled) return;
    const origLabel = btn ? btn.innerHTML : '';
    if (btn) { btn.disabled = true; btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> 发布中...'; }
    const restore = () => { if (btn) { btn.disabled = false; btn.innerHTML = origLabel; } };

    const body = new URLSearchParams({ title: title, content: content, boardId: boardId });
    const tripId = document.getElementById('tripId').value;
    if (tripId) body.append('tripId', tripId);
    const destination = document.getElementById('destination').value.trim();
    if (destination) body.append('destination', destination);
    const travelDate = document.getElementById('travelDate').value;
    if (travelDate) body.append('travelDate', travelDate);

    fetch('/api/forum/posts', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body })
        .then(r => r.json())
        .then(d => {
            if (d.success) { location.href = 'forum.html'; }
            else { setMsg(d.message, false); restore(); }
        })
        .catch(() => { setMsg('发布失败，请重试', false); restore(); });
}

function setMsg(text, ok) {
    const el = document.getElementById('msg');
    el.textContent = (ok ? '✅ ' : '❌ ') + text;
    el.className = 'form-msg ' + (ok ? 'ok' : 'err');
}

init();
