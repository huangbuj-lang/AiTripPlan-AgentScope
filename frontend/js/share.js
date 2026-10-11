/* ============================================================ share.js - 行程分享只读页（share.html） 免登录，按 ?c=<share_code> 拉取行程，复用 A4 渲染组件展示。 */

const STRUCT_ICON = { overview: '🗺️', timeline: '🕒', transport: '🚄', stay: '🏨', tips: '📌' };
const STRUCT_CLASS = { overview: 'sec-overview', timeline: 'sec-timeline', transport: 'sec-transport', stay: 'sec-stay', tips: 'sec-tips' };
const CITY_LANDMARKS = {
    '北京': '🏯', '上海': '🗼', '广州': '🗼', '深圳': '🌆', '杭州': '🛶', '苏州': '🏞️',
    '成都': '🐼', '重庆': '🌉', '西安': '🏯', '南京': '🏛️', '武汉': '🏯', '厦门': '🌊',
    '青岛': '🌊', '大理': '🏔️', '丽江': '🏔️', '桂林': '⛰️', '三亚': '🏖️', '珠海': '🌊'
};

//从 URL 取 share_code
const shareCode = new URLSearchParams(location.search).get('c') || '';

//结构化数据存储标记（与 app.js 保持一致）：markdown 原文尾部追加 JSON，供历史行程还原
const PLAN_JSON_MARK = '<!--ATPLAN_JSON_START-->';
const PLAN_JSON_END = '<!--ATPLAN_JSON_END-->';

/** 从 plan_content 里剥离结构化 JSON 标记段，取回纯 markdown（渲染兜底前必须先剥离，否则 JSON 原文会露出来） */
function stripPlanJson(content) {
    if (!content) return content || '';
    const i = content.indexOf(PLAN_JSON_MARK);
    return i >= 0 ? content.slice(0, i).trim() : content;
}

/** 解析 plan_content 里标记包裹的结构化 JSON； */
function parsePlanJsonFromContent(content) {
    if (!content) return null;
    const i = content.indexOf(PLAN_JSON_MARK);
    if (i < 0) return null;
    const j = content.indexOf(PLAN_JSON_END, i);
    if (j < 0) return null;
    try {
        const raw = content.slice(i + PLAN_JSON_MARK.length, j).trim();
        const parsed = JSON.parse(raw);
        //PlanSet（多方案）时取第一个方案用于展示，并保留整个 set 供切换
        if (parsed && parsed.__planSet && parsed.set && parsed.set.plans && parsed.set.plans.length) {
            return { ...parsed.set.plans[0], _planSet: parsed.set };
        }
        return parsed;
    } catch (e) {
        return null;
    }
}

function escapeHtml(text) {
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function applyInline(s) {
    return s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/`([^`]+)`/g, '<code>$1</code>');
}

function formatSectionItem(type, item) {
    item = item || {};
    const pick = (...vals) => vals.filter(v => v).join(' · ');
    switch (type) {
        case 'timeline': {
            let t = item.time ? escapeHtml(item.time) + '  ' : '';
            if (item.place) t += '<a class="share-place-link" href="javascript:void(0)" onclick="openDayMapByPlace(\'' + item.place.replace(/\\/g, '\\\\').replace(/'/g, "\\'") + '\');return false" title="查看当天路线图">' + escapeHtml(item.place) + '</a>';
            if (item.activity) t += ' · ' + escapeHtml(item.activity);
            if (item.cost) t += ' · ' + escapeHtml(item.cost);
            return t;
        }
        case 'transport': {
            let t = item.from && item.to ? (item.from + ' → ' + item.to) : (item.from || item.to || '');
            if (item.way || item.duration) t += '（' + pick(item.way, item.duration) + '）';
            return t;
        }
        case 'stay': return pick(item.name, item.kind, item.price);
        case 'tips': return (item.kind ? item.kind + '：' : '') + (item.text || '');
        default: return pick(item.activity, item.place, item.text, item.name);
    }
}

//结构化 TripPlanData -> A4 行程表 HTML（与 app.js 的 renderStructuredPlan 对齐）
function renderStructuredPlan(plan) {
    if (!plan) return '<div class="share-empty">（空行程）</div>';
    const sheet = document.createElement('div');
    sheet.className = 'plan-sheet';

    const header = document.createElement('div');
    header.className = 'plan-header';
    const landmark = CITY_LANDMARKS[plan.destination] || '';
    if (landmark) {
        const lm = document.createElement('div');
        lm.className = 'plan-landmark';
        lm.textContent = landmark;
        header.appendChild(lm);
    }
    const h = document.createElement('h2');
    h.className = 'plan-title';
    h.textContent = plan.title || '旅行规划';
    header.appendChild(h);

    const meta = document.createElement('div');
    meta.className = 'plan-meta';
    const addMeta = (text) => { const s = document.createElement('span'); s.textContent = text; meta.appendChild(s); };
    if (plan.days) addMeta('🗓 行程天数：' + plan.days + ' 天');
    if (plan.date) addMeta('📅 ' + plan.date);
    if (plan.weather) addMeta('🌤 ' + plan.weather);
    addMeta('📝 由 AI 旅行规划助手生成');
    header.appendChild(meta);
    sheet.appendChild(header);

    const sections = plan.sections || [];
    sections.forEach(sec => {
        const type = sec.type || 'other';
        const secDiv = document.createElement('div');
        secDiv.className = 'plan-section ' + (STRUCT_CLASS[type] || 'sec-other');
        const st = document.createElement('div');
        st.className = 'sec-title';
        st.textContent = (STRUCT_ICON[type] || '📋') + ' ' + (sec.title || '行程详情');
        secDiv.appendChild(st);
        const list = document.createElement('ul');
        (sec.items || []).forEach(item => {
            const li = document.createElement('li');
            if (type === 'timeline') {
                li.innerHTML = formatSectionItem(type, item); // 渲染可点击景点链接
            } else {
                li.textContent = formatSectionItem(type, item); // 纯文本，防XSS
            }
            list.appendChild(li);
        });
        secDiv.appendChild(list);
        sheet.appendChild(secDiv);
    });

    if (sections.length === 0 && plan.markdown) {
        const fb = document.createElement('div');
        fb.className = 'plan-section sec-other';
        fb.innerHTML = renderMarkdown(plan.markdown);
        sheet.appendChild(fb);
    }
    return sheet.outerHTML;
}

//简易 markdown 兜底（旧数据无 plan_json 时）
function renderMarkdown(text) {
    if (!text) return '<div class="share-empty">（空行程）</div>';
    let html = escapeHtml(text);
    const lines = html.split('\n');
    let out = '', inUl = false, inOl = false;
    lines.forEach(t => {
        t = t.trim();
        if (/^#{1,4}\s*\S/.test(t)) {
            if (inUl) { out += '</ul>'; inUl = false; }
            if (inOl) { out += '</ol>'; inOl = false; }
            const lv = Math.min(t.match(/^#+/)[0].length, 4);
            out += '<h' + lv + '>' + applyInline(t.replace(/^#+\s*/, '')) + '</h' + lv + '>';
        } else if (/^[-*•]\s/.test(t)) {
            if (!inUl) { out += '<ul>'; inUl = true; }
            out += '<li>' + applyInline(t.replace(/^[-*•]\s/, '')) + '</li>';
        } else if (/^\d+[.、]\s/.test(t)) {
            if (!inOl) { out += '<ol>'; inOl = true; }
            out += '<li>' + applyInline(t.replace(/^\d+[.、]\s/, '')) + '</li>';
        } else if (t === '') {
            if (inUl) { out += '</ul>'; inUl = false; }
            if (inOl) { out += '</ol>'; inOl = false; }
        } else {
            if (inUl) { out += '</ul>'; inUl = false; }
            if (inOl) { out += '</ol>'; inOl = false; }
            out += '<p>' + applyInline(t) + '</p>';
        }
    });
    if (inUl) out += '</ul>';
    if (inOl) out += '</ol>';
    return out;
}

//渲染主逻辑
function render(data) {
    const meta = document.getElementById('shareMeta');
    meta.textContent = (data.city ? '🏙️ ' + data.city : '') + (data.days ? ' · ' + data.days + '天' : '') +
        ' · 👁 ' + data.view_count + ' 次浏览';

    let structured = null;
    if (data.plan_json) {
        try { structured = typeof data.plan_json === 'string' ? JSON.parse(data.plan_json) : data.plan_json; } catch (e) { structured = null; }
    }
    //PlanSet（多方案）解包：plan_json 顶层是 {__planSet:true, set:{plans:[...]}} 容器，
    //取第一套方案用于展示（原代码不解包->误判无结构化数据->整段 markdown 原文渲染）
    if (structured && structured.set && structured.set.plans && structured.set.plans.length) {
        const first = structured.set.plans[0];
        if (first && (first.sections || first.title)) {
            structured = Object.assign({}, first, { _planSet: structured.set });
        }
    }
    //plan_json 列可能为空（旧数据/历史行程只把 JSON 追加在 plan_content 尾部）-从 plan_content 兜底解析
    if (!structured && data.plan_content) {
        structured = parsePlanJsonFromContent(data.plan_content);
    }
    sharePlanData = structured; // 保存当前分享行程（按天跳转用）
    const body = document.getElementById('shareBody');
    if (structured && (structured.sections || structured.title)) {
        body.innerHTML = renderStructuredPlan(structured);
    } else if (data.plan_content) {
        body.innerHTML = renderMarkdown(stripPlanJson(data.plan_content)); // 先剥离 JSON 标记段，防止原文露出
    } else {
        body.innerHTML = '<div class="share-empty">（空行程）</div>';
    }
}

//分享页当前行程数据（render 时保存，供按天跳转使用）
let sharePlanData = null;

/** 点击景点：找所属天 → 调后端生成当天路线图并打开 */
function openDayMapByPlace(name) {
    if (!sharePlanData || !sharePlanData.sections) { alert('行程数据不可用'); return; }
    const days = (sharePlanData.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    let dayIdx = -1;
    for (let i = 0; i < days.length; i++) {
        for (const it of (days[i].items || [])) {
            const pl = (it.place || it.name || '').trim();
            if (pl && (pl === name || pl.indexOf(name) >= 0 || name.indexOf(pl) >= 0)) { dayIdx = i; break; }
        }
        if (dayIdx >= 0) break;
    }
    if (dayIdx < 0) { alert('未找到该景点所属天'); return; }
    const btn = document.createElement('div');
    btn.id = 'shareMapLoading';
    btn.style.cssText = 'position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:rgba(0,0,0,.75);color:#fff;padding:14px 22px;border-radius:10px;z-index:999;font-size:14px';
    btn.textContent = '⏳ 正在生成第' + (dayIdx + 1) + '天路线图...';
    document.body.appendChild(btn);
    fetch('/api/share/' + encodeURIComponent(shareCode) + '/day-map?day=' + (dayIdx + 1))
        .then(r => r.json())
        .then(d => {
            const el = document.getElementById('shareMapLoading');
            if (el) el.remove();
            if (d.ok && d.webUrl) {
                window.open(d.webUrl, '_blank');
            } else {
                alert('生成失败：' + (d.error || '未知错误'));
            }
        })
        .catch(() => {
            const el = document.getElementById('shareMapLoading');
            if (el) el.remove();
            alert('网络异常，请重试');
        });
}

//拉取分享内容
function load(pwd) {
    let url = '/api/share/' + encodeURIComponent(shareCode);
    if (pwd) url += '?password=' + encodeURIComponent(pwd);
    fetch(url)
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                document.getElementById('passwordBox').style.display = 'none';
                render(data);
            } else if (data.needPassword) {
                document.getElementById('passwordBox').style.display = 'block';
                document.getElementById('shareBody').innerHTML = '';
            } else {
                document.getElementById('shareBody').innerHTML = '<div class="share-empty">❌ ' + data.message + '</div>';
            }
        })
        .catch(() => { document.getElementById('shareBody').innerHTML = '<div class="share-empty">加载失败，请检查链接</div>'; });
}

function submitPwd() {
    const pwd = document.getElementById('sharePwd').value;
    if (!pwd) return;
    load(pwd);
}

if (!shareCode) {
    document.getElementById('shareBody').innerHTML = '<div class="share-empty">❌ 缺少分享码</div>';
} else {
    load();
}
