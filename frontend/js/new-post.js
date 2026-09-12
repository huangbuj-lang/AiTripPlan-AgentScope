/* ============================================================ new-post.js - 发帖页（new-post.html） ============================================================ */

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

/* ============================================================
   下拉框美化（仅发帖页）：把 版块/关联行程 的原生 <select> 换成圆角自定义下拉。
   设计要点（不改动既有逻辑）：
     · 原生 <select> 保留在 DOM 里，只是视觉隐藏 —— 选中后写回它的 value 并派发 change，
       所以 new-post.js 里原有的"读 .value""监听 change"以及 ?boardId= 预选全部照常工作。
     · 用 MutationObserver 监听选项被页面 JS 重新填充（innerHTML）后自动重建菜单，
       因此版块/行程列表异步加载完也能正确显示。
   ============================================================ */
(function enhanceSelects() {
    var IDS = ['boardId', 'tripId'];

    function build(sel) {
        if (!sel || sel.dataset.npDd === '1') return;
        sel.dataset.npDd = '1';

        var wrap = document.createElement('div');
        wrap.className = 'np-dd';
        sel.parentNode.insertBefore(wrap, sel);
        wrap.appendChild(sel);

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'np-dd-btn';
        btn.innerHTML = '<span class="np-dd-val"></span><i class="fa-solid fa-chevron-down np-dd-ar"></i>';
        var menu = document.createElement('div');
        menu.className = 'np-dd-menu';
        wrap.appendChild(btn);
        wrap.appendChild(menu);

        function sync() {
            var opt = sel.options[sel.selectedIndex];
            btn.querySelector('.np-dd-val').textContent = opt ? opt.textContent : '请选择';
            var html = '';
            for (var i = 0; i < sel.options.length; i++) {
                var o = sel.options[i];
                html += '<div class="np-dd-item' + (i === sel.selectedIndex ? ' on' : '') +
                        '" data-i="' + i + '">' + o.textContent + '</div>';
            }
            menu.innerHTML = html;
        }

        btn.addEventListener('click', function (e) {
            e.stopPropagation();
            document.querySelectorAll('.np-dd.open').forEach(function (d) {
                if (d !== wrap) d.classList.remove('open');
            });
            wrap.classList.toggle('open');
        });

        menu.addEventListener('click', function (e) {
            var item = e.target.closest ? e.target.closest('.np-dd-item') : null;
            if (!item) return;
            var o = sel.options[parseInt(item.dataset.i, 10)];
            if (!o) return;
            sel.value = o.value;                                          // 写回原生 select
            sel.dispatchEvent(new Event('change', { bubbles: true }));    // 触发既有监听（结伴字段显隐等）
            wrap.classList.remove('open');
            sync();
        });

        document.addEventListener('click', function () { wrap.classList.remove('open'); });

        // 选项被重新填充（异步加载版块/行程）后自动重建菜单
        new MutationObserver(sync).observe(sel, { childList: true });
        sync();
    }

    function start() { IDS.forEach(function (id) { build(document.getElementById(id)); }); }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
})();
