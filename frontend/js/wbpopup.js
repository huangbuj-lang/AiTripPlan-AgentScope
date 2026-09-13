/**
 * ★行中提醒执行提示（弹窗）——管理员专用。
 * 口径（用户明确要求）：
 *   · 只给【管理员账号】看 ✓ 普通用户不弹 ✓
 *   · 前台首页 + 管理端 两个界面都加载 ✓
 *   · 只用【弹窗】提示 ✓ 不写通知模块 ✗ 不产生铃铛红点 ✗
 * 数据来源：GET /api/admin/weather-brief/last-run（非管理员直接拿不到 → 自然不会弹）
 */
(function () {
    var SHOWN_KEY = 'wbpopup_shown_at';     //只记"已经弹过哪一次"，不缓存任何行程内容
    var POLL_MS = 60000;
    var FRESH_MS = 10 * 60 * 1000;          //超过 10 分钟前的执行不再打扰

    function shownAt() { try { return parseInt(localStorage.getItem(SHOWN_KEY) || '0', 10) || 0; } catch (e) { return 0; } }
    function markShown(at) { try { localStorage.setItem(SHOWN_KEY, String(at)); } catch (e) { } }

    function injectStyle() {
        if (document.getElementById('wbPopStyle')) return;
        var st = document.createElement('style');
        st.id = 'wbPopStyle';
        st.textContent =
            '#wbPopup{position:fixed;right:22px;bottom:22px;z-index:9999;width:320px;background:#fff;' +
            'border:1.5px solid #1f2a26;border-radius:14px;box-shadow:0 10px 30px rgba(20,40,30,.18);' +
            'font-family:inherit;overflow:hidden;animation:wbPopIn .25s ease}' +
            '@keyframes wbPopIn{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:none}}' +
            '#wbPopup .wb-pop-title{display:flex;align-items:center;gap:8px;padding:12px 14px 8px;font-size:14.5px;' +
            'font-weight:600;color:#1f2a26}' +
            '#wbPopup .wb-pop-title i{color:#d98324}' +
            '#wbPopup .wb-pop-body{padding:0 14px 12px;font-size:13px;line-height:1.65;color:#5f726b}' +
            '#wbPopup .wb-pop-foot{display:flex;justify-content:flex-end;padding:0 12px 12px}' +
            '#wbPopup .wb-pop-foot button{border:1px solid #dfe6e2;background:#fff;color:#3a6149;' +
            'border-radius:999px;padding:5px 14px;font-size:12.5px;cursor:pointer;font-family:inherit}' +
            '#wbPopup .wb-pop-foot button:hover{background:#f3f7f5}' +
            '#wbPopup .wb-pop-brief{margin-top:8px;padding:8px 10px;background:#f6f4ee;border-radius:9px;' +
            'font-size:12.5px;line-height:1.6;color:#3a4a44}';
        document.head.appendChild(st);
    }

    function popup(d) {
        if (document.getElementById('wbPopup')) return;
        injectStyle();
        var box = document.createElement('div');
        box.id = 'wbPopup';
        var by = d.by === 'manual' ? '手动触发' : '定时自动';
        box.innerHTML =
            '<div class="wb-pop-title"><i class="fa-regular fa-bell"></i> 行中提醒已执行</div>' +
            '<div class="wb-pop-body">' + (d.date || '') + ' ' + (d.atText || '') + ' · ' + by + '<br>' +
            '覆盖 <b>' + (d.sent || '0') + '</b> 条行程（早报只在这里给你看，不发给普通用户）' +
            (d.brief ? '<div class="wb-pop-brief">' + d.brief + '</div>' : '') + '</div>' +
            '<div class="wb-pop-foot"><button type="button" id="wbPopOk">知道了</button></div>';
        document.body.appendChild(box);
        var ok = document.getElementById('wbPopOk');
        if (ok) ok.addEventListener('click', function () { box.remove(); });
        setTimeout(function () { if (box.parentNode) box.remove(); }, 60000);
    }

    function check() {
        fetch('/api/admin/weather-brief/last-run')
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (!d || !d.success || !d.atMillis) return;         //非管理员/无记录 → 什么都不做
                if (d.atMillis <= shownAt()) return;                 //这次已经弹过
                if (Date.now() - d.atMillis > FRESH_MS) { markShown(d.atMillis); return; }
                markShown(d.atMillis);
                popup(d);
            })
            .catch(function () { });
    }

    function boot() { setTimeout(check, 1200); setInterval(check, POLL_MS); }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
    window.WbPopup = { show: popup, check: check };   //便于自验
})();
