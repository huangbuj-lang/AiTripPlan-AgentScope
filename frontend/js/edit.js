/* ============================================================
   edit.js — 行程二次编辑页（edit.html）
   加载结构化 plan_json → 编辑每天行程 → 保存 / 增量重排某一天
   ============================================================ */

const tripId = new URLSearchParams(location.search).get('id');
let plan = null; // 内存中的 TripPlanData 对象（编辑时直接改，保存时序列化）

function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

// 判断一个 section 是否为“某一天”的 timeline 区块
function isDaySection(sec) { return sec.type === 'timeline'; }

// 加载行程
function load() {
    fetch('/api/trips/' + tripId + '/edit').then(r => r.json()).then(d => {
        if (!d.success) { alert(d.message || '加载失败'); location.href = 'index.html'; return; }
        plan = d.plan;
        if (!plan.sections) plan.sections = [];
        document.getElementById('planTitle').textContent = plan.title || '行程编辑';
        render();
    }).catch(() => alert('加载失败'));
}

// 渲染可编辑的每天行程 + 只读的其他区块
function render() {
    const body = document.getElementById('editBody');
    let html = '';
    let dayIdx = 0;
    plan.sections.forEach((sec, si) => {
        if (isDaySection(sec)) {
            html += renderDay(sec, si, dayIdx);
            dayIdx++;
        } else {
            html += renderOther(sec);
        }
    });
    body.innerHTML = html || '<div class="empty">该行程没有可编辑的日程</div>';
}

// 渲染某一天的卡片（可编辑 + 添加 + 重排）
function renderDay(sec, si, dayIdx) {
    const title = sec.title || ('第' + (dayIdx + 1) + '天');
    const items = (sec.items || []).map((it, ii) =>
        '<div class="ed-item">' +
        '<input class="ed-time" placeholder="时间" value="' + esc(it.time) + '" oninput="plan.sections[' + si + '].items[' + ii + '].time=this.value">' +
        '<input class="ed-place" placeholder="地点/景点" value="' + esc(it.place) + '" oninput="plan.sections[' + si + '].items[' + ii + '].place=this.value">' +
        '<input class="ed-act" placeholder="活动" value="' + esc(it.activity) + '" oninput="plan.sections[' + si + '].items[' + ii + '].activity=this.value">' +
        '<input class="ed-cost" placeholder="费用" value="' + esc(it.cost) + '" oninput="plan.sections[' + si + '].items[' + ii + '].cost=this.value">' +
        '<button class="ed-del" onclick="delItem(' + si + ',' + ii + ')" title="删除">✕</button>' +
        '</div>'
    ).join('');
    return '<div class="day-card">' +
        '<div class="day-head"><b>' + esc(title) + '</b>' +
        '<span class="day-ops">' +
        '<button class="mini-btn" onclick="addItem(' + si + ')">+ 添加一项</button> ' +
        '<button class="mini-btn regen" onclick="regen(' + si + ',' + dayIdx + ')">🔄 重排这一天</button>' +
        '</span></div>' +
        '<div class="ed-items">' + items + '</div>' +
        '</div>';
}

// 渲染非 timeline 区块（只读展示）
function renderOther(sec) {
    const items = (sec.items || []).map(it => {
        const txt = it.time ? (it.time + ' ' + (it.place || it.activity || it.name || it.text || ''))
            : (it.place || it.activity || it.name || it.from + '→' + it.to || it.text || '');
        return '<li>' + esc(txt) + '</li>';
    }).join('');
    return '<div class="other-card"><b>' + esc(sec.title || '') + '</b><ul>' + items + '</ul></div>';
}

function addItem(si) {
    plan.sections[si].items.push({ time: '', place: '', activity: '', cost: '', note: '' });
    render();
}

function delItem(si, ii) {
    plan.sections[si].items.splice(ii, 1);
    render();
}

// 保存修改
function save() {
    if (!plan) return;
    fetch('/api/trips/' + tripId, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(plan)
    }).then(r => r.json()).then(d => {
        document.getElementById('saveMsg').textContent = d.message || (d.success ? '已保存' : '保存失败');
        document.getElementById('saveMsg').style.color = d.success ? '#00b894' : '#e17055';
    }).catch(() => document.getElementById('saveMsg').textContent = '保存失败');
}

// 增量重排某一天
function regen(si, dayIdx) {
    const instruction = prompt('可输入调整要求（如“换成美食主题”），留空则按原背景重排：');
    document.getElementById('saveMsg').textContent = '重排中，请稍候...';
    fetch('/api/trips/' + tripId + '/regenerate-day', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ dayIndex: dayIdx, instruction: instruction || '' })
    }).then(r => r.json()).then(d => {
        if (d.success && d.plan) { plan = d.plan; render(); document.getElementById('saveMsg').textContent = d.message; document.getElementById('saveMsg').style.color = '#00b894'; }
        else { document.getElementById('saveMsg').textContent = d.message || '重排失败'; document.getElementById('saveMsg').style.color = '#e17055'; }
    }).catch(() => { document.getElementById('saveMsg').textContent = '重排失败'; document.getElementById('saveMsg').style.color = '#e17055'; });
}

load();
