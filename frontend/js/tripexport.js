/* ============================================================
 * 行程长图导出（canvas 手绘，【不引任何 CDN / 外部库】，离线可用）
 *
 * 入口：保存条里「打印 / 导出」菜单新增的一项「导出长图」
 *       → 菜单项 onclick="exportLongImage(event)"（只加一个 <a>，既有两项不动）
 *
 * 数据：直接读 app.js 已渲染的行程对象（顶层 let currentPlanData；typeof 保护，读不到就提示先生成）
 *       —— 不重新请求后端，不编造内容，画出来的就是页面上那份行程。
 *
 * 画法：
 *   ① 先用同一套排版逻辑"量"一遍（measureText），算出总高度；
 *   ② 再建 2 倍分辨率（DPR=2，高清）画布，按记录下来的绘制指令重放一遍；
 *   ③ canvas.toBlob → PNG 下载（文件名：漫旅行程_<标题>.png）。
 *   中文字体走系统字体（Microsoft YaHei / PingFang SC / Segoe UI），不加载任何 webfont。
 * ============================================================ */
(function () {
    'use strict';

    var DPR = 2;                 // 高清 2x
    var W = 1080;                // 逻辑宽度（常见的分享长图宽度）
    var PAD = 56;                // 左右留白
    var FONT = '"Microsoft YaHei","PingFang SC","Hiragino Sans GB",-apple-system,"Segoe UI",sans-serif';
    var MONO = 'Consolas,"SF Mono","Courier New",monospace';

    var C = {
        ink: '#1E2E29', sub: '#6C7B74', mute: '#8A948C',
        head: '#0B3D2E', headSub: 'rgba(255,255,255,.84)', headFoot: 'rgba(255,255,255,.6)',
        teal: '#12876f', accent: '#C9A96A', line: '#E4EAE5', bg: '#FBF8F3', card: '#FFFFFF'
    };

    /* ---------------- 小工具 ---------------- */
    function txt(s) { return String(s === undefined || s === null ? '' : s); }
    function stripP(s) {
        var v = txt(s);
        try { if (typeof stripParens === 'function') return stripParens(v); } catch (e) { }
        return v.replace(/[（(][^）)]*[）)]/g, '').replace(/\s+/g, ' ').trim();
    }
    function planOf() {
        try { if (typeof currentPlanData !== 'undefined' && currentPlanData) return currentPlanData; } catch (e) { }
        return null;
    }
    function safeName(s) {
        return txt(s).replace(/[\\/:*?"<>|\s]+/g, '_').slice(0, 40) || '行程';
    }
    function fontPx(font) {
        var m = String(font).match(/(\d+(?:\.\d+)?)px/);
        return m ? Number(m[1]) : 16;
    }
    function lineH(font) { return Math.round(fontPx(font) * 1.55); }
    function makeMeasureCtx() {
        var c = document.createElement('canvas');
        c.width = W;
        c.height = 10;
        return c.getContext('2d');
    }

    /** 按 maxW 折行（中英文都能折；量宽用真实 measureText，maxW<=0 就不折） */
    function wrap(ctx, text, font, maxW) {
        var s = txt(text).replace(/\s+/g, ' ').trim();
        if (!s) return [''];
        ctx.font = font;
        if (!maxW || maxW <= 0) return [s];
        var out = [], line = '';
        for (var i = 0; i < s.length; i++) {
            var ch = s.charAt(i);
            var test = line + ch;
            if (line && ctx.measureText(test).width > maxW) { out.push(line); line = ch; }
            else { line = test; }
        }
        if (line) out.push(line);
        return out;
    }

    /* ---------------- 排版：一次量、一次画（同一份绘制指令数组） ---------------- */
    function buildOps(plan) {
        var ctx = makeMeasureCtx();
        var ops = [];
        var y = 0;

        function rect(x, yy, w, h, fill, r) { ops.push({ k: 'rect', x: x, y: yy, w: w, h: h, fill: fill, r: r || 0 }); }
        function line(x1, y1, x2, y2, stroke, wd) { ops.push({ k: 'line', x1: x1, y1: y1, x2: x2, y2: y2, stroke: stroke, w: wd || 1 }); }
        /** 写一段（自动折行），返回占用的高度 */
        function text(s, x, yy, font, fill, maxW) {
            var ls = wrap(ctx, s, font, maxW);
            var lh = lineH(font);
            for (var i = 0; i < ls.length; i++) {
                ops.push({ k: 'text', text: ls[i], x: x, y: yy + i * lh, font: font, fill: fill });
            }
            return Math.max(1, ls.length) * lh;
        }
        function textH(s, font, maxW) { return Math.max(1, wrap(ctx, s, font, maxW).length) * lineH(font); }

        var sections = plan.sections || [];
        var days = sections.filter(function (s) { return s && (s.type === 'timeline' || /第.+天/.test(s.title || '')); });
        var others = sections.filter(function (s) { return s && s.type !== 'timeline' && !/第.+天/.test(s.title || ''); });
        var title = stripP(plan.title || '旅行计划');
        var weather = '';
        try { weather = (typeof cleanWeatherText === 'function') ? cleanWeatherText(plan.weather) : plan.weather; } catch (e) { weather = plan.weather; }
        var metaBits = [];
        if (plan.days) metaBits.push(txt(plan.days) + ' 天');
        if (plan.destination) metaBits.push(stripP(plan.destination));
        if (plan.date) metaBits.push(txt(plan.date));
        if (weather) metaBits.push(stripP(weather));
        var meta = metaBits.join(' · ');

        var tFont = 'bold 34px ' + FONT;
        var mFont = '16px ' + FONT;
        var innerW = W - PAD * 2;

        /* ---- 头部色块（高度随标题行数自适应） ---- */
        var th = textH(title, tFont, innerW);
        var mh = meta ? textH(meta, mFont, innerW) : 0;
        var headH = 44 + th + 14 + mh + 46;
        rect(0, 0, W, headH, C.head, 0);
        var ty = 44 + fontPx(tFont);                  // 标题首行基线
        text(title, PAD, ty, tFont, '#FFFFFF', innerW);
        var my = ty + (th - fontPx(tFont)) + 30;      // 元信息首行基线
        if (meta) text(meta, PAD, my, mFont, C.headSub, innerW);
        text('漫旅 MANLÜ · 多 Agent 协作生成 · 不含任何报价', PAD, headH - 22, '13px ' + FONT, C.headFoot, innerW);
        y = headH + 36;

        /* ---- 每天一块 ---- */
        var wTime = 62;
        var tSmall = '13px ' + MONO;
        var pFont = 'bold 19px ' + FONT;
        var bFont = '14px ' + FONT;
        days.forEach(function (day, di) {
            var items = (day.items || []).filter(function (it) { return it && (it.time || it.place || it.activity || it.name || it.text); });
            if (!items.length) {
                (day.stops || []).forEach(function (s) { items.push({ time: '', place: '· ' + stripP(s), activity: '' }); });
            }
            if (!items.length) return;
            var textW = innerW - 96 - 26;
            var parts = items.map(function (it) {
                var place = stripP(it.place || it.name || '');
                var body = stripP([it.activity, it.cost, it.note].filter(Boolean).join(' · '));
                if (!place && it.text) body = stripP(it.text);
                var ph = place ? textH(place, pFont, textW) : 0;
                var bh = body ? textH(body, bFont, textW) : 0;
                return { time: txt(it.time).trim(), place: place, body: body, ph: ph, bh: bh, h: ph + bh + 22 };
            });
            var innerH = parts.reduce(function (a, p) { return a + p.h; }, 0);
            var blockH = innerH + 74;
            rect(PAD, y, innerW, blockH, C.card, 14);
            text('DAY ' + String(di + 1).padStart(2, '0') + ' · ' + stripP(day.title || ('第' + (di + 1) + '天')), PAD + 26, y + 36, 'bold 20px ' + FONT, C.head, innerW - 52);
            line(PAD + 26, y + 50, W - PAD - 26, y + 50, C.line, 1);
            var iy = y + 50;
            parts.forEach(function (p) {
                var base = iy + 24;
                if (p.time) text(p.time, PAD + 26, base, tSmall, C.teal, wTime);
                var px = PAD + 96;
                var b2 = base;
                if (p.place) { text(p.place, px, b2, pFont, C.ink, textW); b2 += p.ph; }
                if (p.body) text(p.body, px, b2, bFont, C.sub, textW);
                iy += p.h;
            });
            y += blockH + 22;
        });

        /* ---- 其余区块（交通 / 住宿餐饮 / 实用信息） ---- */
        var oFont = '14.5px ' + FONT;
        others.forEach(function (sec) {
            var rows = (sec.items || []).map(function (it) {
                var t = '';
                try { if (typeof formatSectionItem === 'function') t = formatSectionItem(sec.type || 'other', it); } catch (e) { t = ''; }
                if (!t) t = [it && it.name, it && it.text, it && it.from, it && it.to, it && it.note].filter(Boolean).join(' · ');
                return stripP(t);
            }).filter(function (t) { return !!t; });
            if (!rows.length) return;
            var rowW = innerW - 60;
            var innerH = rows.reduce(function (a, r) { return a + textH(r, oFont, rowW) + 10; }, 0);
            var blockH = innerH + 74;
            rect(PAD, y, innerW, blockH, C.card, 14);
            text(stripP(sec.title || '行程详情'), PAD + 26, y + 36, 'bold 18px ' + FONT, C.head, innerW - 52);
            line(PAD + 26, y + 50, W - PAD - 26, y + 50, C.line, 1);
            var ry = y + 50;
            rows.forEach(function (r) {
                text('· ' + r, PAD + 30, ry + 24, oFont, C.sub, rowW);
                ry += textH(r, oFont, rowW) + 10;
            });
            y += blockH + 22;
        });

        /* ---- 页脚 ---- */
        var foot = '本图由「漫旅 MANLÜ」根据本次已生成的行程绘制 · 生成时间 ' + new Date().toLocaleString('zh-CN')
            + ' · 图内不含任何报价';
        var fFont = '12.5px ' + FONT;
        var fh = textH(foot, fFont, innerW);
        text(foot, PAD, y + 24, fFont, C.mute, innerW);
        y += fh + 34;

        return { ops: ops, height: Math.ceil(y + 30), width: W };
    }

    /* ---------------- 绘制 ---------------- */
    function replay(ctx, ops) {
        ops.forEach(function (op) {
            if (op.k === 'rect') {
                ctx.fillStyle = op.fill;
                if (op.r && ctx.beginPath && ctx.roundRect) {
                    ctx.beginPath();
                    ctx.roundRect(op.x, op.y, op.w, op.h, op.r);
                    ctx.fill();
                } else {
                    ctx.fillRect(op.x, op.y, op.w, op.h);
                }
            } else if (op.k === 'line') {
                ctx.strokeStyle = op.stroke;
                ctx.lineWidth = op.w;
                ctx.beginPath();
                ctx.moveTo(op.x1, op.y1);
                ctx.lineTo(op.x2, op.y2);
                ctx.stroke();
            } else if (op.k === 'text') {
                ctx.font = op.font;
                ctx.fillStyle = op.fill;
                if (ctx.textBaseline !== undefined) ctx.textBaseline = 'alphabetic';
                ctx.fillText(op.text, op.x, op.y);
            }
        });
    }

    /** 画到画布（2x 高清），返回 { canvas, height, ops } */
    function renderToCanvas(plan) {
        var built = buildOps(plan);
        var cv = document.createElement('canvas');
        cv.width = W * DPR;
        cv.height = Math.max(1, built.height) * DPR;
        var ctx = cv.getContext('2d');
        if (ctx.scale) ctx.scale(DPR, DPR);
        ctx.fillStyle = C.bg;
        ctx.fillRect(0, 0, W, built.height);
        replay(ctx, built.ops);
        return { canvas: cv, height: built.height, ops: built.ops.length };
    }

    function savePng(canvas, filename) {
        function finish(blob) {
            if (!blob) return;
            try {
                if (typeof downloadBlob === 'function') { downloadBlob(filename, blob); return; }
            } catch (e) { /* 落到下面的兜底 */ }
            try {
                var url = URL.createObjectURL(blob);
                var a = document.createElement('a');
                a.href = url;
                a.download = filename;
                (document.body || document.documentElement).appendChild(a);
                a.click();
                setTimeout(function () {
                    try { URL.revokeObjectURL(url); } catch (e) { }
                    if (a.parentNode) a.parentNode.removeChild(a);
                }, 2000);
            } catch (e) { }
        }
        try {
            if (typeof canvas.toBlob === 'function') { canvas.toBlob(finish, 'image/png'); return; }
        } catch (e) { /* 落到兜底 */ }
        try {
            var b64 = txt(canvas.toDataURL('image/png')).split(',')[1];
            var bin = (typeof atob === 'function') ? atob(b64) : '';
            var arr = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
            finish(new Blob([arr], { type: 'image/png' }));
        } catch (e) { }
    }

    /** ★对外入口（菜单项 onclick 调它） */
    function exportLongImage(ev) {
        if (ev && ev.stopPropagation) ev.stopPropagation();
        try { if (typeof closeExportMenu === 'function') closeExportMenu(); } catch (e) { }
        var msg = document.getElementById('saveMsg');
        var plan = planOf();
        if (!plan || (!plan.sections && !plan.title)) {
            try { if (typeof hasExportablePlan === 'function' && !hasExportablePlan()) return; } catch (e) { }
            alert('暂无可导出的行程，请先生成计划或查看一条已保存行程');
            return;
        }
        try {
            var r = renderToCanvas(plan);
            savePng(r.canvas, '漫旅行程_' + safeName(plan.title || plan.destination) + '.png');
            if (msg) {
                msg.textContent = '🖼 行程长图已导出（' + r.height + 'px 高 · ' + (W * DPR) + '×' + (r.height * DPR) + ' 高清 PNG）';
                setTimeout(function () { if (txt(msg.textContent).indexOf('行程长图已导出') >= 0) msg.textContent = ''; }, 4000);
            }
        } catch (e) {
            if (msg) msg.textContent = '⚠️ 长图导出失败：' + (e && e.message ? e.message : e);
        }
    }

    window.exportLongImage = exportLongImage;
    window.TripExport = { build: buildOps, renderToCanvas: renderToCanvas, exportLongImage: exportLongImage, wrap: wrap };
})();
