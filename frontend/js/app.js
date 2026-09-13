/** app.js 主页面全部前端逻辑。 */

//===== 状态变量 =====
let polling = null;        // 轮询定时器（/events，已由 SSE 替代，保留兜底）
let resultTimer = null;    // 结果轮询定时器（/result）
let eventSource = null;    // SSE 实时事件流（阶段四 F-3，替代 /events 轮询）
let knownCount = 0;        // 已显示的事件数（保留，向后兼容）
let lastEventCount = 0;    // 发送前的事件总数（用于增量显示）
let lastResultMarkdown = ''; // 最近一次最终结果的 markdown 原文（保存行程用，避免存 HTML）

//会话隔离：每个窗口独立 sessionId（localStorage 持久），请求带上它避免多窗口互扰
let sessionId = (function () {
    let sid = localStorage.getItem('atp_session_id');
    if (!sid) {
        sid = 's' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
        localStorage.setItem('atp_session_id', sid);
    }
    return sid;
})();

//===== 登录状态检查（页面加载时执行） =====


//===== 调度中心 × 生成进度（合并面板）：阶段刻度 / 各环节状态 / 计时 =====
//文案口径（用户要求）：只用大白话，不出现 Agent、编排、校验通过这类专业名词
const THINK_STAGES = [
    { key: '解析', rail: '需求规划完成', doing: '正在理解需求', desc: '正在看清你要去哪里、去几天', pct: 8 },
    { key: '路线', rail: '路线规划完成', doing: '正在规划路线', desc: '正在安排先去哪、后去哪', pct: 20 },
    { key: '行程', rail: '行程规划完成', doing: '正在安排行程', desc: '正在安排每天玩什么、吃什么、住哪儿', pct: 35 },
    { key: '整合', rail: '整合校验完成', doing: '正在整合检查', desc: '正在检查每天的节奏合不合理', pct: 85 },
    { key: '完成', rail: '全部完成', doing: '行程已生成', desc: '正在准备展示', pct: 100 }
];
const THINK_CHIPS = [
    { nm: '需求理解', at: '解析' }, { nm: '路线规划', at: '路线' },
    { nm: '行程安排', at: '行程' }, { nm: '整体检查', at: '整合' }
];
let thinkIdx = -1, thinkClockTimer = null, thinkStartTs = 0;
let thinkFired = new Set();   // 真正到达过的事件类型：没发生过的绝不打勾（用户抓到过假勾）
let thinkSig = '';            // 状态签名：只有变化时才重建，避免闪动
let thinkPct = 0;             // 单调进度：并行阶段后到的低值不回退
/** 面板只在【生成任务进行中】出现：提交/恢复时显示，出结果或取消时收起 */
function showThinkPanel() { const p = document.getElementById('thinkPanel'); if (p) p.style.display = ''; }
function hideThinkPanel() { const p = document.getElementById('thinkPanel'); if (p) p.style.display = 'none'; }

function fmtClock(sec) {
    sec = Math.max(0, Math.floor(sec));
    return String(Math.floor(sec / 60)).padStart(2, '0') + ':' + String(sec % 60).padStart(2, '0');
}
/** 按【真正发生过的事件】渲染刻度与状态块：
 *  ① 只有真到达的阶段才打勾（曾按档位顺序推算 -> 没发生的"路线"也被打勾，用户当场抓出）
 *  ② 状态签名不变就不重建 -> 不闪动
 */
function renderThinkStages() {
    const sig = THINK_STAGES.filter(function (s) { return thinkFired.has(s.key); }).map(function (s) { return s.key; }).join(',');
    if (sig === thinkSig) return;
    thinkSig = sig;
    const firedList = THINK_STAGES.filter(function (s) { return thinkFired.has(s.key); });
    const cur = firedList.length ? firedList[firedList.length - 1].key : null;
    const rail = document.getElementById('tpRail');
    if (rail) rail.innerHTML = THINK_STAGES.map(function (s) {
        const done = thinkFired.has(s.key) && s.key !== cur;
        const cls = done ? 'done' : (s.key === cur ? 'on now' : '');
        return '<div class="tp-node ' + cls + '"><div class="dot"></div><div class="nm">' + s.rail + '</div></div>';
    }).join('');
    const chips = document.getElementById('tpChips');
    if (chips) chips.innerHTML = THINK_CHIPS.map(function (c) {
        const isDone = thinkFired.has(c.at) && c.at !== cur;
        const isRun = c.at === cur;
        const cls = isDone ? 'ok' : (isRun ? 'run' : '');
        const txt = isDone ? '已完成' : (isRun ? '进行中' : '待命');
        return '<span class="tp-chip ' + cls + '"><i class="dotled"></i>' + c.nm + ' \u00b7 ' + txt + '</span>';
    }).join('');
    const st = THINK_STAGES.filter(function (s) { return s.key === cur; })[0] || THINK_STAGES[0];
    const nm = document.getElementById('tpStageName'), ds = document.getElementById('tpStageDesc');
    const fin = thinkFired.has('完成');
    if (nm) nm.textContent = fin ? '行程已生成' : st.doing;
    if (ds) ds.textContent = fin ? '正在准备展示' : st.desc;
}
function setThinkLive(kind) {
    const el = document.getElementById('thinkLive');
    if (!el) return;
    el.className = kind === 'done' ? 'live done' : (kind === 'stop' ? 'live stop' : 'live');
    el.textContent = kind === 'done' ? 'DONE' : (kind === 'stop' ? '已中断' : 'LIVE');
}
function stopThinkClock(final) {
    if (thinkClockTimer) { clearInterval(thinkClockTimer); thinkClockTimer = null; }
    if (final) { const eta = document.getElementById('thinkEta'); if (eta) eta.textContent = '已完成'; }
}
/** 提交 / 页面刷新恢复时重置整个面板 */
function resetThinkPanel(pct, label) {
    thinkIdx = -1;
    thinkFired = new Set(); thinkSig = null; thinkPct = 0;
    showThinkPanel();
    const used = document.getElementById('thinkUsed');
    if (used) used.textContent = '00:00';
    const eta = document.getElementById('thinkEta');
    if (eta) eta.textContent = '预计还需 —';
    setThinkLive('live');
    updateGenProgress(pct, null);
    thinkStartTs = Date.now();
    if (thinkClockTimer) clearInterval(thinkClockTimer);
    thinkClockTimer = setInterval(function () {
        const sec = (Date.now() - thinkStartTs) / 1000;
        const u = document.getElementById('thinkUsed');
        if (u) u.textContent = fmtClock(sec);
        const e2 = document.getElementById('thinkEta');
        //参考值：真实长线约 456~640s；只给时间参考，不改进度数值（不改假进度）
        if (e2) e2.textContent = sec < 420 ? ('预计还需 ' + fmtClock(420 - sec)) : '即将完成';
    }, 1000);
    const nm = document.getElementById('tpStageName'), ds = document.getElementById('tpStageDesc');
    if (nm) nm.textContent = label ? '正在提交任务' : THINK_STAGES[0].doing;
    if (ds) ds.textContent = label || THINK_STAGES[0].desc;
}

//===== 生成进度条（阶段驱动）：提交->解析->路线->行程->整合->完成 =====
const GEN_STAGES = {
    '解析': { pct: 8, label: '解析旅行需求...' },
    '路线': { pct: 20, label: '路线Agent规划路线...' },
    '行程': { pct: 35, label: '行程Agent规划行程...' },
    '整合': { pct: 85, label: '主管整合生成方案...' },
    '完成': { pct: 100, label: '生成完成！' }
};

//平滑推进：行程/整合阶段耗时长（1-2分钟），事件只在阶段开始时发一次，
//之后每 1.5s +1% 缓步推进，避免"半分钟到72%然后卡死"的假进度
let genSmoothTimer = null;
function startGenSmooth(targetMax) {
    stopGenSmooth();
    genSmoothTimer = setInterval(() => {
        const pctEl = document.querySelector('.gen-pct');
        const fill = document.querySelector('.gen-fill');
        if (!pctEl || !fill) { stopGenSmooth(); return; }
        const cur = parseInt(pctEl.textContent, 10) || 0;
        if (cur >= targetMax) { stopGenSmooth(); return; }
        updateGenProgress(cur + 1, null); // label 传 null 表示保留现有文字
    }, 1500);
}
function stopGenSmooth() {
    if (genSmoothTimer) { clearInterval(genSmoothTimer); genSmoothTimer = null; }
}

function genProgressHTML(pct, label) {
    return '<div class="gen-progress"><div class="gen-track"><div class="gen-fill" style="width:' + pct + '%"></div></div>'
        + '<div class="gen-label"><span class="gen-pct">' + pct + '%</span><span class="gen-label-text">' + escapeHtml(label || '') + '</span></div></div>';
}

function updateGenProgress(pct, label) {
    const fill = document.querySelector('#thinkPanel .gen-fill') || document.querySelector('.gen-fill');
    if (!fill) return;
    thinkPct = Math.max(thinkPct, pct);   // 并行阶段后到的低值不回退
    fill.style.width = thinkPct + '%';
    const pctEl = document.querySelector('#thinkPanel .gen-pct') || document.querySelector('.gen-pct');
    if (pctEl) pctEl.textContent = Math.round(thinkPct) + '%';
    renderThinkStages();
    if (thinkPct >= 100) { setThinkLive('done'); stopThinkClock(true); }
}

//页面重载后恢复进行中的任务：localStorage 标记 + /result 探测
//修复竞态：任务恰在页面跳转时完成时，标记可能已被旧页面清除 -> 只要有 sessionId 就探测 /result
function tryRestoreRunning() {
    const mark = localStorage.getItem('atp_gen_running');
    let m = null;
    try { m = mark ? JSON.parse(mark) : null; } catch (e) { localStorage.removeItem('atp_gen_running'); m = null; }
    if (m && m.sid !== sessionId) { localStorage.removeItem('atp_gen_running'); m = null; }
    fetch('/result?sessionId=' + encodeURIComponent(sessionId))
        .then(r => r.text())
        .then(text => {
            if (text.includes('任务不存在')) {
                //任务已失效（服务重启/未登录/从未提交）：清理本地标记，不再卡进度条
                localStorage.removeItem('atp_gen_running');
                //★用户口径：不要自动弹出旧行程。
                //  刷页面前正在看的行程由下面"任务已完成"分支的 fetchResult 恢复，不靠这里；
                //  这里（服务重启/新登录）必须是【干净首页】，绝不"登录进去就自己蹦出一个行程"。
                try { localStorage.removeItem('atp_last_plan_id'); } catch (e) { }
                document.getElementById('sendBtn').disabled = false;
                document.getElementById('cancelBtn').style.display = 'none';
                return;
            }
            if (!text.includes('还在执行中')) {
                //★用户口径：用户按过 × 关闭行程 → 本次不再自动恢复（正在执行中的任务不受影响，见上面的分支）
                if (localStorage.getItem('atp_trip_closed') === sessionId) {
                    localStorage.removeItem('atp_gen_running');
                    document.getElementById('resultBody').innerHTML = '';
                    return;
                }
                //任务已完成：直接渲染最终结果（不丢进度条/结果）
                //页面刷新恢复时跳过「中断」结果：中断提示只在当前页面会话显示，刷新后恢复干净首页（生成中仍会实时显示中断提示）
                if (text.includes('中断') || text.includes('已由用户')) {
                    localStorage.removeItem('atp_gen_running');
                    document.getElementById('resultBody').innerHTML = '';
                    document.getElementById('sendBtn').disabled = false;
                    document.getElementById('cancelBtn').style.display = 'none';
                    return;
                }
                if (text && text.trim()) { fetchResult(); return; }
                return;
            }
            //任务仍在跑：恢复进度条 + 重连事件流 + 结果轮询（仅标记存在时，避免误恢复）
            if (!m) return;
            document.getElementById('resultBody').innerHTML = '';
            resetThinkPanel(30, '正在恢复进度');
            //★兜底：进了"恢复进度"却一直没有任何事件 → 说明任务其实已经没了（服务重启等），
            //  15 秒后清标记并收起，绝不留幽灵进度（用户口径：除服务重启外任务常驻，重启后就该干净）
            if (window.__restoreWatch) clearTimeout(window.__restoreWatch);
            window.__restoreWatch = setTimeout(function () {
                if (thinkFired && thinkFired.size > 0) return;   // 有事件 = 真在跑
                try { localStorage.removeItem('atp_gen_running'); } catch (e) { }
                //★长连接必须关掉：否则浏览器标签会一直转圈（用户当场反馈"一直在转圈"）
                try { if (eventSource) { eventSource.close(); eventSource = null; } } catch (e) { }
                try { if (polling) { clearInterval(polling); polling = null; } } catch (e) { }
                try { if (resultTimer) { clearInterval(resultTimer); resultTimer = null; } } catch (e) { }
                stopThinkClock(false); stopGenSmooth(); hideThinkPanel();
                document.getElementById('resultBody').innerHTML = '';
                document.getElementById('sendBtn').disabled = false;
                document.getElementById('cancelBtn').style.display = 'none';
            }, 15000);
            document.getElementById('sendBtn').disabled = true;
            document.getElementById('cancelBtn').style.display = 'inline-block';
            if (eventSource) eventSource.close();
            eventSource = new EventSource('/events/stream?sessionId=' + encodeURIComponent(sessionId));
            eventSource.addEventListener('event', function (e) {
                try { appendThinkItem(JSON.parse(e.data)); } catch (err) { /* 忽略非法数据 */ }
            });
            if (resultTimer) clearInterval(resultTimer);
            resultTimer = setInterval(fetchResult, 2000);
            //标记保留（任务仍在跑）：任务完成时由 fetchResult 清除
        })
        .catch(() => localStorage.removeItem('atp_gen_running'));
}

function checkLogin() {
    fetch('/api/check-login')
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                document.getElementById('forumBtn').style.display = 'inline-block';
                document.getElementById('profileBtn').style.display = 'inline-block';
                document.getElementById('myPlansBtn').style.display = 'inline-block';
                document.getElementById('logoutBtn').style.display = 'inline-block';

    //★通知入口（用户口径：从论坛页移到首页）——显示按钮 + 拉未读数
    (function () {
        var nb = document.getElementById('notifBtn');
        if (nb) nb.style.display = 'inline-block';
        fetch('/api/notifications/unread-count').then(function (r) { return r.json(); }).then(function (d) {
            var b = document.getElementById('notifBadge');
            if (b && d && d.success && d.count > 0) { b.style.display = 'inline-block'; b.textContent = d.count; }
        }).catch(function () { });
    })();
                //管理员额外显示「管理端」入口
                if (data.role === 'admin') {
                    document.getElementById('adminBtn').style.display = 'inline-block';
                }
                //切页回来：恢复进行中的生成任务进度
                tryRestoreRunning();
            } else {
                //未登录：跳转登录页（相对路径，兼容部署上下文）
                location.href = 'login.html';
            }
        })
        .catch(() => { location.href = 'login.html'; });
}

//退出登录
function logout() {
    fetch('/api/logout', { method: 'POST' })
        .then(r => r.json())
        .then(() => { location.href = 'login.html'; })
        .catch(() => { location.href = 'login.html'; });
}

//保存当前结果到数据库
//结构化数据存储标记：markdown 原文 + 尾部追加 JSON（供历史行程还原轨迹/卡片）
const PLAN_JSON_MARK = '<!--ATPLAN_JSON_START-->';
const PLAN_JSON_END = '<!--ATPLAN_JSON_END-->';

/** 从 plan_content 里剥离结构化 JSON 标记，取回纯 markdown（预览/再保存用） */
function stripPlanJson(content) {
    const i = content.indexOf(PLAN_JSON_MARK);
    return i >= 0 ? content.slice(0, i).trim() : content;
}

/** 解析 plan_content 里的结构化 JSON； */
function parsePlanJson(content) {
    const i = content.indexOf(PLAN_JSON_MARK);
    if (i < 0) return null;
    const j = content.indexOf(PLAN_JSON_END, i);
    if (j < 0) return null;
    try {
        return JSON.parse(content.slice(i + PLAN_JSON_MARK.length, j).trim());
    } catch (e) {
        return null;
    }
}

function savePlan() {
    let text = stripPlanJson(lastResultMarkdown).trim();
    if (!text || text.includes('正在生成') || text.includes('等待生成')) {
        document.getElementById('saveMsg').textContent = '暂无结果可保存';
        return;
    }
    //F-8：若当前有结构化行程数据，把 JSON 追加进 planContent，历史行程可还原轨迹/卡片
    //多方案时保存整个方案集(PlanSet)，历史行程可切换其他方案并再保存
    let payload = text;
    let structuredJson = null;  // 同步写入 plan_json 列（编辑页数据源），与 planContent 标记双写
    if (planSetData && planSetData.plans && planSetData.plans.length > 1) {
        try {
            structuredJson = JSON.stringify({ __planSet: true, set: planSetData });
            payload = text + '\n\n' + PLAN_JSON_MARK + '\n' + structuredJson + '\n' + PLAN_JSON_END;
        } catch (e) { /* JSON 序列化失败则只存 markdown */ }
    } else if (currentPlanData) {
        try {
            structuredJson = JSON.stringify(currentPlanData);
            payload = text + '\n\n' + PLAN_JSON_MARK + '\n' + structuredJson + '\n' + PLAN_JSON_END;
        } catch (e) { /* JSON 序列化失败则只存 markdown */ }
    }
    //从结果里提取城市和天数。
    //1) 结构化行程的 destination（后端解析的准确目的地）
    const prompt = document.getElementById('prompt').value.trim();
    let city = '';
    if (currentPlanData && currentPlanData.destination && String(currentPlanData.destination).trim()
            && String(currentPlanData.destination).trim() !== '未知城市') {
        city = String(currentPlanData.destination).trim();
    } else if (planSetData && planSetData.plans && planSetData.plans.length) {
        const dests = [...new Set((planSetData.plans || []).map(pl => (pl && pl.destination || '').toString().trim()).filter(Boolean))];
        city = dests.length === 1 ? dests[0] : '';
    }
    if (!city) {
        city = prompt.replace(/帮我制定|制定|计划|规划|3日游|2日游|1日游|自驾游|游/g, '').trim() || '未知城市';
    }
    //天数提取：支持阿拉伯数字(7日)和中文数字(七日/七天)
    const daysMatch = prompt.match(/(\d+)日|([一二三四五六七八九十]+)[日天]/);
    let days = 3; // 默认3天（匹配不到时兜底）
    if (daysMatch) {
        if (daysMatch[1]) {
            days = parseInt(daysMatch[1]);
        } else {
            const cn = { '一':1,'二':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9,'十':10 };
            const str = daysMatch[2];
            days = str.includes('十') ? (str.length === 1 ? 10 : 10 + (cn[str[1]] || 0)) : (cn[str] || 3);
        }
    }
    //F-1：保存 markdown 原文（不再存渲染后的 HTML），前端渲染时安全转义
    fetch('/api/save-plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ city: city, days: days, planContent: payload, planJson: structuredJson || '' })
    })
        .then(r => r.json())
        .then(data => {
            document.getElementById('saveMsg').textContent =
                data.success ? '✅ ' + data.message : '❌ ' + data.message;
            if (!data.success && data.message.includes('登录')) location.href = 'login.html';
        })
        .catch(() => { document.getElementById('saveMsg').textContent = '❌ 保存失败，请重试'; });
}

//打开"我的行程"弹层
function showMyPlans() {
    fetch('/api/my-plans')
        .then(r => r.json())
        .then(data => {
            const list = document.getElementById('myPlansList');
            if (!data.success) { list.innerHTML = '<div style="color:#e17055">' + data.message + '</div>'; return; }
            const plans = data.plans || [];
            if (plans.length === 0) {
                list.innerHTML = '<div class="empty">还没有保存的行程，先去规划一个吧！</div>';
            } else {
                list.innerHTML = plans.map(p =>
                    '<div class="plan-card">' +
                    '<div class="plan-card-head">' +
                    '<b>🏙️ ' + (p.city || '未知城市') + '</b>' +
                    '<span class="plan-card-time">' + formatPlanTime(p.create_time || p.created_at) + '</span>' +
                    '</div>' +
                    '<div class="plan-card-preview">' +
                    stripPlanJson(p.plan_content || '').replace(/<[^>]+>/g, ' ').slice(0, 200) + '...' +
                    '</div>' +
                    '<div class="plan-card-actions">' +
                    '<button class="mini-btn" onclick="viewPlan(' + p.id + ')">查看</button> ' +
                    '<button class="mini-btn" onclick="location.href=\'edit.html?id=' + p.id + '\'">编辑</button> ' +
                    '<button class="mini-btn" onclick="sharePlan(' + p.id + ')">分享</button> ' +
                    '<button class="mini-btn danger" onclick="deletePlan(' + p.id + ')">删除</button>' +
                    '</div></div>'
                ).join('');
            }
            document.getElementById('myPlansModal').style.display = 'flex';
        })
        .catch(() => { });
}

//查看某条行程（把内容渲染到主页结果区）
function viewPlan(id) {
    fetch('/api/my-plans')
        .then(r => r.json())
        .then(data => {
            const p = (data.plans || []).find(x => x.id === id);
            if (p) {
                //第0步：优先用结构化 JSON 列渲染（供分享/二次编辑），缺失时退回内嵌JSON/markdown
                let structuredCol = null;
                if (p.plan_json) {
                    try { structuredCol = typeof p.plan_json === 'string' ? JSON.parse(p.plan_json) : p.plan_json; }
                    catch (e) { structuredCol = null; }
                }
                if (structuredCol && (structuredCol.sections || structuredCol.title)) {
                    currentPlanData = structuredCol; // 历史行程也恢复专属地图数据源
                    viewingPlanId = id;              // 记录行程ID：日历改期后保存用
                    rememberPlanId(id);              // ★记住在看哪条：跳转/刷新回来时按它从后端恢复
                    lastResultJson = typeof p.plan_json === 'string' ? p.plan_json : JSON.stringify(structuredCol);
                    document.getElementById('resultBody').innerHTML = renderStructuredPlan(structuredCol);
                    document.getElementById('saveBar').style.display = 'flex';
                    closeMyPlans();
                    document.getElementById('resultBody').scrollIntoView({ behavior: 'smooth' });
                    return;
                }
                let content = p.plan_content || '';
                //F-8：新数据带结构化 JSON 标记 -> 优先还原轨迹/卡片渲染（与生成时一致）
                const structured = parsePlanJson(content);
                if (structured) {
                    if (structured.__planSet && structured.set && structured.set.plans && structured.set.plans.length > 1) {
                        //多方案集：还原完整多方案界面（胶囊可切换，可再保存其他方案）
                        planSetData = structured.set;
                        planSetIndex = 0;
                        document.getElementById('resultBody').innerHTML = renderPlanSet(structured.set);
                        lastResultMarkdown = stripPlanJson(content); // 记录纯 markdown 供再保存
                    } else {
                        currentPlanData = structured;
                        document.getElementById('resultBody').innerHTML = renderStructuredPlan(structured);
                        lastResultMarkdown = stripPlanJson(content); // 记录纯 markdown 供再保存
                    }
                } else {
                    //F-1：区分新旧格式。
                    if (/<[a-zA-Z][^>]*>/.test(content)) {
                        //旧数据：没有 plan-sheet 包裹的纯 HTML，套一层行程单容器
                        if (!/class="[^"]*\bplan-sheet\b/.test(content)) {
                            content = '<div class="plan-sheet"><div class="plan-body">' + content + '</div></div>';
                        }
                        document.getElementById('resultBody').innerHTML = DOMPurify.sanitize(content);
                    } else {
                        document.getElementById('resultBody').innerHTML = renderPlanSheet(content);
                    }
                    lastResultMarkdown = content.replace(/<[^>]+>/g, ''); // 记录查看的行程文本,供再次保存
                }
                //查看历史行程：保持与生成结果一致的操作条(保存/导出)
                document.getElementById('saveBar').style.display = 'flex';
                document.getElementById('saveMsg').textContent = '';
                closeMyPlans();
                document.getElementById('resultBody').scrollIntoView({ behavior: 'smooth' });
            }
        })
        .catch(() => { });
}

//删除某条行程

/** 分享行程：生成免登录分享链接 */
function sharePlan(id) {
    fetch('/api/trips/' + id + '/share', { method: 'POST' })
        //★不用 r.json()：401/500/非 JSON 时也能拿到真实原因（原来一律吞成"请重试"，看不出问题）
        .then(r => r.text().then(t => ({ ok: r.ok, status: r.status, raw: t })))
        .then(res => {
            let data = null;
            try { data = JSON.parse(res.raw); } catch (e) { /* 非 JSON 响应 */ }
            if (data && data.success && data.url) {
                const url = location.origin + data.url;
                const copy = confirm('分享链接（免登录可看）：\n' + url + '\n\n点击「确定」复制链接到剪贴板？');
                if (copy) copyShareUrl(url);
                return;
            }
            const why = (data && data.message)
                ? data.message
                : (res.status === 401 ? '登录已过期，请重新登录后再分享' : ('HTTP ' + res.status + ' ' + String(res.raw || '').slice(0, 60)));
            alert('分享失败：' + why);
        })
        .catch(e => alert('分享失败：请求发不出去（服务未启动或网络中断）' + (e && e.message ? '\n' + e.message : '')));
}

/** 复制分享链接：http 局域网地址下 navigator.clipboard 不可用（非安全上下文），用 execCommand 兜底 */
function copyShareUrl(url) {
    const done = () => {
        const el = document.getElementById('saveMsg');
        if (el) el.textContent = '✅ 分享链接已复制';
    };
    const fallback = () => {
        try {
            const ta = document.createElement('textarea');
            ta.value = url;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            const ok = document.execCommand('copy');
            document.body.removeChild(ta);
            if (ok) done();
        } catch (e) { /* 复制不了也不影响用户手动选中 */ }
    };
    try {
        if (navigator.clipboard && window.isSecureContext) {
            navigator.clipboard.writeText(url).then(done).catch(fallback);
            return;
        }
    } catch (e) { /* 落到兜底 */ }
    fallback();
}

/** 行程创建时间：接口给的是 create_time（ISO/UTC，如 2026-09-12T09:06:51.000Z），本地化显示为 2026-09-12 17:06 */
function formatPlanTime(v) {
    if (!v) return '';
    try {
        const d = new Date(v);
        if (isNaN(d.getTime())) return String(v).replace('T', ' ').slice(0, 16);
        const p = n => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    } catch (e) {
        return String(v).replace('T', ' ').slice(0, 16);
    }
}

function deletePlan(id) {
    if (!confirm('确定删除这条行程吗？')) return;
    fetch('/api/delete-plan?id=' + id, { method: 'POST' })
        .then(r => r.json())
        .then(() => { showMyPlans(); })
        .catch(() => { });
}

//关闭弹层
function closeMyPlans() { document.getElementById('myPlansModal').style.display = 'none'; }

//页面加载时检查登录
checkLogin();

/** 发送任务 部署修复：/app 立即返回（不占长连接）-> 只轮询 /events 看进度 -> 完成后拉 /result 修复手机端浏览器连接数限制导致思考过程不显示的问题 */
function send() {
    const prompt = document.getElementById('prompt').value.trim();
    if (!prompt) { alert('请输入旅行需求！'); return; }
    //方案选择弹窗：单方案 / 多方案（多方案=后端并行聚合3套差异化行程）
    showSchemeModal();
}

//==================== 方案选择弹窗（单/多方案） ====================
let pendingScheme = 'single';
function showSchemeModal() {
    pendingScheme = 'single';
    const s = document.getElementById('optSingle'), m = document.getElementById('optMulti');
    s.classList.add('active'); m.classList.remove('active');
    document.getElementById('schemeModal').style.display = 'flex';
}
function pickScheme(v) {
    pendingScheme = v;
    document.getElementById('optSingle').classList.toggle('active', v === 'single');
    document.getElementById('optMulti').classList.toggle('active', v === 'multi');
}
function closeSchemeModal() {
    document.getElementById('schemeModal').style.display = 'none';
}
/** 把「天数」拼进需求文本 —— 后端 /app 只接 prompt，所以用自然语言附加（核心链路不改） */
function composePrompt(base) {
    let t = String(base == null ? '' : base).trim();
    try {
        const dv = document.getElementById('dayVal');
        const m = (dv ? String(dv.textContent || '') : '').match(/\d+/);
        if (m) t += '（行程共 ' + m[0] + ' 天）';
    } catch (e) { /* 取不到就不加，不影响提交 */ }
    //★新增 1 行（不改上面任何既有逻辑）：预算输入框 → 追加一句带进需求文本
    //  后端 budgetTotal 没有独立参数，只由 requestParser 从需求原文解析（AgentPrompts.requestParser：'用户说"预算5000"→5000'），
    //  所以这里只追加一句，口径交给后端；输入框不填（空）时这一行什么都不加。
    try { const bi = document.getElementById('budgetInput'); const bv = bi ? String(bi.value || '').trim() : ''; if (bv) t += '（人均预算 ' + bv + ' 元）'; } catch (e) { /* 取不到就不加，不影响提交 */ }
    return t;
}

function confirmScheme() {
    document.getElementById('schemeModal').style.display = 'none';
    const prompt = composePrompt(document.getElementById('prompt').value.trim());
    if (!prompt) { alert('请输入旅行需求！'); return; }
    doSubmit(prompt, '', '', '', pendingScheme === 'multi' ? 'true' : 'false');
}

/** 中断任务：调用 /cancel 设置取消标志，主管Agent在下一个事件点停止 不再等任务排队--中断后 RUNNING 释放，可以马上提交新任务 */
function cancelTask() {
    hideThinkPanel();
    if (!confirm('确定要停止当前任务吗？')) return;
    stopGenSmooth();
    localStorage.removeItem('atp_gen_running');
    fetch('/cancel?sessionId=' + encodeURIComponent(sessionId), { method: 'POST' })
        .then(r => r.text())
        .then(msg => {
            document.getElementById('status').textContent = '⏹ 正在停止任务...';
            //停止事件流（结果会在 /result 显示"任务已中断"）
            if (eventSource) { eventSource.close(); eventSource = null; }
            if (polling) { clearInterval(polling); polling = null; }
            //结果轮询继续（等后端写入"已中断"结果）
            if (!resultTimer) {
                resultTimer = setInterval(fetchResult, 2000);
            }
        })
        .catch(() => { /* 网络抖动，忽略 */ });
}

//==================== 预检卡片（功能1：意图拆解）+ 偏好记忆（功能5） ====================

/** 当前选中的偏好（内存态，提交时读取） */
let currentPref = { walkTolerance: '', chronotype: '', museumRatio: '', scheme: 'single' };

/** 弹出预检卡片：回填历史偏好，绑定选项点击 */
function showPrecheck() {
    //重置选中态
    document.querySelectorAll('.precheck-opt').forEach(b => b.classList.remove('active'));
    currentPref = { walkTolerance: '', chronotype: '', museumRatio: '', scheme: 'single' };
    //默认单方案高亮
    highlightOpt('schemeOpts', 'single');
    //拉取历史偏好回填默认值（隐形学习：下次不用重新勾选）
    fetch('/api/get-preference')
        .then(r => r.json())
        .then(d => {
            if (d.success) {
                currentPref = { walkTolerance: d.walkTolerance || '', chronotype: d.chronotype || '', museumRatio: d.museumRatio || '', scheme: currentPref.scheme };
                highlightOpt('walkOpts', d.walkTolerance);
                highlightOpt('chronoOpts', d.chronotype);
                highlightOpt('museumOpts', d.museumRatio);
            }
        })
        .catch(() => { /* 未登录或网络问题：不填默认值 */ });
    document.getElementById('precheckModal').style.display = 'flex';
    //绑定选项点击（每次弹出重新绑定）
    bindPrecheckOpts('walkOpts', 'walkTolerance');
    bindPrecheckOpts('chronoOpts', 'chronotype');
    bindPrecheckOpts('museumOpts', 'museumRatio');
    bindPrecheckOpts('schemeOpts', 'scheme');
}

/** 高亮历史回填的选项 */
function highlightOpt(groupId, value) {
    if (!value) return;
    document.querySelectorAll('#' + groupId + ' .precheck-opt').forEach(b => {
        if (b.getAttribute('data-v') === value) b.classList.add('active');
    });
}

/** 绑定一组选项的点击（单选） */
function bindPrecheckOpts(groupId, key) {
    document.querySelectorAll('#' + groupId + ' .precheck-opt').forEach(b => {
        b.onclick = function () {
            document.querySelectorAll('#' + groupId + ' .precheck-opt').forEach(x => x.classList.remove('active'));
            b.classList.add('active');
            currentPref[key] = b.getAttribute('data-v');
        };
    });
}

/** 关闭预检卡片 */
function closePrecheck() {
    document.getElementById('precheckModal').style.display = 'none';
}

/** 确认生成：skip=true 跳过偏好（全不限）； */
function submitWithPreference(skip) {
    document.getElementById('precheckModal').style.display = 'none';
    const prompt = document.getElementById('prompt').value.trim();
    if (!prompt) { alert('请输入旅行需求！'); return; }
    const walk = skip ? '' : (currentPref.walkTolerance || '');
    const chrono = skip ? '' : (currentPref.chronotype || '');
    const museum = skip ? '' : (currentPref.museumRatio || '');
    const multi = (!skip && currentPref.scheme === 'multi') ? 'true' : 'false';
    //偏好持久化：非跳过时保存（隐形学习）
    if (!skip) {
        fetch('/api/save-preference?walkTolerance=' + encodeURIComponent(walk)
            + '&chronotype=' + encodeURIComponent(chrono)
            + '&museumRatio=' + encodeURIComponent(museum), { method: 'POST' })
            .catch(() => { /* 保存失败不影响生成 */ });
    }
    doSubmit(prompt, walk, chrono, museum, multi);
}

/** 实际提交任务（原 send 的提交逻辑，增加偏好参数） */
function doSubmit(prompt, walk, chrono, museum, multi) {
    if (polling) { clearInterval(polling); polling = null; }
    if (eventSource) { eventSource.close(); eventSource = null; }
    if (resultTimer) { clearInterval(resultTimer); resultTimer = null; }

    //重置界面
    document.getElementById('thinkBody').innerHTML = '';
    document.getElementById('resultBody').innerHTML = '';
    resetThinkPanel(8, '正在接收你的需求');
    localStorage.setItem('atp_gen_running', JSON.stringify({ sid: sessionId, t: Date.now() }));
    localStorage.removeItem('atp_trip_closed'); //★重新生成 = 用户要看新行程，清掉"已关闭"标记（用户口径）
    document.getElementById('status').textContent = '⏳ 任务提交中...';
    document.getElementById('status').classList.add('show');
    document.getElementById('sendBtn').disabled = true;

    //(1) 提交任务（带偏好参数）
    fetch('/app?prompt=' + encodeURIComponent(prompt) + '&sessionId=' + encodeURIComponent(sessionId)
        + '&walkTolerance=' + encodeURIComponent(walk)
        + '&chronotype=' + encodeURIComponent(chrono)
        + '&museumRatio=' + encodeURIComponent(museum)
        + '&multiScheme=' + encodeURIComponent(multi), { method: 'POST' })
        .then(r => r.text())
        .then(msg => {
            if (msg.includes('有任务正在执行')) {
                document.getElementById('resultBody').innerHTML =
                    '<div style="color:#e17055">⚠️ ' + msg + '</div>';
                document.getElementById('status').textContent = '⚠️ 任务未提交';
                document.getElementById('sendBtn').disabled = false;
                if (polling) { clearInterval(polling); polling = null; }
                if (resultTimer) { clearInterval(resultTimer); resultTimer = null; }
                return;
            }
            document.getElementById('cancelBtn').style.display = 'inline-block';
            lastEventCount = 0;
            if (eventSource) eventSource.close();
            lastEventCount = 0;
            eventSource = new EventSource('/events/stream?sessionId=' + encodeURIComponent(sessionId));
            eventSource.addEventListener('event', function (e) {
                try { appendThinkItem(JSON.parse(e.data)); } catch (err) { /* 忽略非法数据 */ }
            });
            if (resultTimer) clearInterval(resultTimer);
            resultTimer = setInterval(fetchResult, 2000);
        })
        .catch(err => {
            document.getElementById('resultBody').innerHTML =
                '<div style="color:#d63031">❌ 请求失败：' + err + '</div>';
            document.getElementById('status').textContent = '❌ 出错了，请重试';
            document.getElementById('sendBtn').disabled = false;
        });
}

/** 拉取最终结果（/result 返回非"执行中" = 完成） */
function fetchResult() {
    fetch('/result?sessionId=' + encodeURIComponent(sessionId))
        .then(r => r.text())
        .then(text => {
            if (text.includes('还在执行中')) return;  // 没完成，继续等

            //完成！
            localStorage.removeItem('atp_gen_running');
            clearInterval(resultTimer); resultTimer = null;
            if (eventSource) { eventSource.close(); eventSource = null; }
            if (polling) { clearInterval(polling); polling = null; }
            document.getElementById('cancelBtn').style.display = 'none'; // 隐藏停止按钮

            //阶段三：优先按结构化 JSON 渲染
            let structured = null;
            try { structured = JSON.parse(text); } catch (e) { /* 非 JSON：按原文处理 */ }
            if (structured && structured.plans && Array.isArray(structured.plans) && structured.plans.length > 1) {
                //功能4：多方案对比（PlanSet）
                lastResultMarkdown = ''; // 多方案暂不支持保存单一 markdown（选中后回填）
                document.getElementById('resultBody').innerHTML = renderPlanSet(structured);
            } else if (structured && (structured.sections || structured.title)) {
                lastResultMarkdown = structured.markdown || ''; // 记录降级 markdown，供保存
                document.getElementById('resultBody').innerHTML = renderStructuredPlan(structured);
            } else {
                lastResultMarkdown = text; // 记录原文，供保存行程使用
                document.getElementById('resultBody').innerHTML = renderPlanSheet(text);
            }
            document.getElementById('status').textContent = '✅ 计划生成完成！';
            document.getElementById('sendBtn').disabled = false;
            //结果出来了，显示"保存到我的行程"操作条
            document.getElementById('saveBar').style.display = 'flex';
            document.getElementById('saveMsg').textContent = '';
        })
        .catch(() => { /* 网络抖动，下轮重试 */ });
}

/** 追加一条思考过程事件到界面（SSE 与轮询共用） */
function appendThinkItem(ev) {
    //阶段驱动进度条：按事件类型推进（解析/路线/行程/整合/完成）
    const st = GEN_STAGES[ev.type];
    if (st) {
        thinkFired.add(ev.type);   // ★先登记：只有真到达的阶段才会被打勾
        if (window.__restoreWatch) { clearTimeout(window.__restoreWatch); window.__restoreWatch = null; }
        updateGenProgress(st.pct, st.label);
        if (ev.type === '行程') startGenSmooth(80);      // 行程Agent 1-2分钟：35%->80% 平滑
        else if (ev.type === '整合') startGenSmooth(96); // 整合阶段：85%->96% 平滑
        else if (ev.type === '完成') { stopGenSmooth(); stopThinkClock(true); setThinkLive('done'); }
    }
    //调度中心只给用户看人话：内部阶段（诊断/校验）不显示；失败字样、工具名、字符数、括号内容一律不显示
    if (ev.type === '诊断' || ev.type === '校验') return;
    const body = document.getElementById('thinkBody');
    const empty = body.querySelector('.empty');
    if (empty) empty.remove();

    const div = document.createElement('div');
    div.className = 'think-item';

    const time = document.createElement('span');
    time.className = 'think-time';
    time.textContent = ev.time || '';

    const type = document.createElement('span');
    type.className = 'think-type ' + typeClass(ev.type);
    type.textContent = ev.type || '';

    const content = document.createElement('span');
    content.textContent = friendlyThinkText(ev);

    div.appendChild(time);
    div.appendChild(type);
    div.appendChild(content);
    body.appendChild(div);

    body.scrollTop = body.scrollHeight; // 自动滚动到底部
}

/** 轮询事件接口（兜底路径，正常情况下由 SSE 取代） */
function pollEvents() {
    fetch('/events?sessionId=' + encodeURIComponent(sessionId))
        .then(r => r.json())
        .then(evts => {
            const fresh = evts.slice(lastEventCount);
            fresh.forEach(appendThinkItem);
            lastEventCount = evts.length;
        })
        .catch(() => { /* 接口暂不可达，忽略 */ });
}

/** 事件类型 -> 样式类 */
function typeClass(type) {
    if (type === '用户输入') return 't-user';
    if (type === '调用工具') return 't-tool';
    if (type === '思考') return 't-think';
    if (type === '完成') return 't-done';
    return 't-other';
}

/* ============================================================ Markdown 渲染（增强版） 支持：标题 h1-h4 / 表格 / 无序列表 / 有序列表 / 引用 / 分隔线 / 代码块 / 加粗 / 行内代码 / 普通段落 ============================================================ */

/** HTML 转义，防止注入 */
function escapeHtml(text) {
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

/** 行内格式：加粗 **xxx**、行内代码 `xxx` */
function applyInline(s) {
    return s
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/`([^`]+)`/g, '<code>$1</code>');
}

/** A4 打印风格：活动类型图标（按 md 设计文档要求，用图标区分活动类型） 依据单元格/列表项关键词，返回对应 emoji 图标： 景点/游览 -> 餐饮/美食 -> 交通/地铁 -> 住宿/酒店 -> 门票/费用 -> 电话/紧急 -> 天气 -> */
/** 活动类型 -> 图标（关键词匹配,兜底返回通用图标,永不空白） */
function activityIcon(text) {
    text = text || '';
    //交通类(按优先级:先具体后通用)
    if (/地铁|高铁|火车|动车|城际/.test(text)) return '🚄';
    if (/公交|巴士|大巴/.test(text)) return '🚌';
    if (/飞机|机场|航班|航空/.test(text)) return '✈️';
    if (/打车|出租车|滴滴/.test(text)) return '🚕';
    if (/自驾|驾车|开车|高速/.test(text)) return '🚗';
    if (/步行|徒步|走路|散步|登山|爬山/.test(text)) return '🥾';
    if (/骑行|自行车|单车|骑行道/.test(text)) return '🚲';
    if (/船|码头|轮渡|游船|帆船/.test(text)) return '⛵';
    if (/缆车|索道/.test(text)) return '🚡';
    //餐饮类
    if (/火锅|涮肉|涮锅/.test(text)) return '🍲';
    if (/烤鸭|烤肉|烧烤|烤鱼/.test(text)) return '🍖';
    if (/海鲜|生蚝|龙虾|螃蟹/.test(text)) return '🦞';
    if (/小吃|夜市|美食街|排档|路边/.test(text)) return '🍢';
    if (/咖啡|奶茶|茶饮|茶馆|下午茶/.test(text)) return '☕';
    if (/早餐|早点|包子|豆浆|豆汁|油条|煎饼/.test(text)) return '🥟';
    if (/甜点|蛋糕|冰淇淋|糖水|甜品/.test(text)) return '🍰';
    if (/面|粉|馄饨|饺子|烧卖/.test(text)) return '🍜';
    if (/餐厅|饭|餐|美食|品尝|小吃/.test(text)) return '🍽️';
    //景点类
    if (/博物馆|展览馆|美术馆|科技馆|纪念馆/.test(text)) return '🏛️';
    if (/故宫|宫殿|王府|皇城|古镇|古城|寺庙|庙|塔|楼阁|园林|颐和园|圆明园|拙政园|留园/.test(text)) return '🏯';
    if (/长城|城墙|遗址|古迹|陵|石窟/.test(text)) return '🧱';
    if (/海滩|沙滩|海岛|海滨|赶海/.test(text)) return '🏖️';
    if (/山|峰|峡谷|瀑布|溶洞|自然|森林|草原|湿地/.test(text)) return '🏔️';
    if (/湖|江|河|水乡|泛舟|划船/.test(text)) return '🌊';
    if (/乐园|游乐场|主题公园|迪士尼|环球/.test(text)) return '🎡';
    if (/动物园|熊猫|海洋馆|水族馆|动物/.test(text)) return '🐼';
    if (/公园|广场|绿地/.test(text)) return '🌳';
    if (/夜景|夜景|灯光秀|日落|日出|观景|俯瞰/.test(text)) return '🌇';
    if (/拍照|出片|摄影|机位|打卡/.test(text)) return '📸';
    if (/购物|商场|街区|步行街|商业街|免税/.test(text)) return '🛍️';
    if (/演出|剧场|音乐|看秀|戏剧/.test(text)) return '🎭';
    if (/大学|高校|校园|学院/.test(text)) return '🎓';
    //住宿类
    if (/酒店|宾馆|饭店/.test(text)) return '🏨';
    if (/民宿|客栈|青旅|青年旅社/.test(text)) return '🏡';
    if (/住宿|入住|休息/.test(text)) return '🛏️';
    //其他
    if (/门票|票价|费用|价格|消费|¥|￥|元/.test(text)) return '🎫';
    if (/电话|紧急|求助|报警|急救|联系|热线/.test(text)) return '📞';
    if (/天气|气温|温度|℃|晴|雨|雪|多云/.test(text)) return '🌤️';
    if (/免税|购物/.test(text)) return '🛒';
    //兜底：通用定位图标，保证永不空白
    return '📍';
}

/** 给文本添加活动类型图标（无匹配则原样返回） */
function withActivityIcon(text) {
    const icon = activityIcon(text);
    return icon ? '<span class="activity-icon">' + icon + '</span>' + text : text;
}

/** 将 markdown 表格行数组渲染为 <table class="plan-table"> */
function buildTable(rows) {
    //解析每行单元格（去掉首尾 | 再按 | 切分）
    const parsed = rows.map(r => {
        let s = r.trim();
        if (s.startsWith('|')) s = s.slice(1);
        if (s.endsWith('|')) s = s.slice(0, -1);
        return s.split('|').map(c => c.trim());
    });
    const header = parsed[0] || [];
    let body = parsed.slice(1);
    //跳过 markdown 分隔行（如 |---|---| 或 |:--|--:|）
    if (body.length && body[0].every(c => /^:?-{2,}:?$/.test(c.replace(/\s/g, '')))) {
        body = body.slice(1);
    }

    //时间轴识别：首列表头是"时间"类关键词时，用时间轴样式渲染（文档"详细时间表区"要求）
    const isTimeline = /时间|时刻|时段|time/.test(header[0] || '');

    let html = '<table class="plan-table' + (isTimeline ? ' timeline-table' : '') + '">';
    if (isTimeline) {
        //时间轴模式：第一列渲染为时间胶囊，其余列正常
        html += '<tbody>';
        body.forEach(row => {
            const timeCell = row[0] || '';
            html += '<tr><td class="timeline-time">' + escapeHtml(timeCell) + '</td>';
            html += '<td>';
            for (let i = 1; i < header.length; i++) {
                const label = header[i] || '';
                const val = row[i] || '';
                if (label && val) {
                    html += '<span class="timeline-item"><b>' + applyInline(label) + '</b>' +
                        withActivityIcon(applyInline(val)) + '</span>';
                }
            }
            if (header.length <= 1) {
                html += withActivityIcon(applyInline(row.slice(1).join(' ')));
            }
            html += '</td></tr>';
        });
        html += '</tbody></table>';
        return html;
    }

    //普通表格模式
    html += '<thead><tr>';
    header.forEach(c => { html += '<th>' + applyInline(c) + '</th>'; });
    html += '</tr></thead><tbody>';
    body.forEach(row => {
        html += '<tr>';
        header.forEach((_, i) => { html += '<td>' + withActivityIcon(applyInline(row[i] || '')) + '</td>'; });
        html += '</tr>';
    });
    html += '</tbody></table>';
    return html;
}

/** 简易 Markdown 渲染（标题/表格/列表/引用/代码块/加粗/行内代码） 先 HTML 转义防注入，再按行解析为 HTML */
function renderMarkdown(text) {
    if (!text) return '<div class="empty">（空响应）</div>';
    let html = escapeHtml(text);
    const lines = html.split('\n');
    let out = '';
    let inUl = false;        // 无序列表块
    let inOl = false;        // 有序列表块
    let inCode = false;      // 代码块
    let tableRows = [];      // 表格行缓存
    let inTable = false;     // 表格块

    //收尾工具函数
    const closeList = () => {
        if (inUl) { out += '</ul>'; inUl = false; }
        if (inOl) { out += '</ol>'; inOl = false; }
    };
    const closeTable = () => {
        if (inTable && tableRows.length) {
            out += buildTable(tableRows);
            tableRows = [];
            inTable = false;
        }
    };

    lines.forEach(line => {
        const t = line.trim();

        //代码块围栏（``` 开头/结束）
        if (/^```/.test(t)) {
            closeList(); closeTable();
            if (inCode) { out += '</pre>'; inCode = false; }
            else { out += '<pre>'; inCode = true; }
            return;
        }
        //代码块内：原样输出
        if (inCode) { out += t + '\n'; return; }

        //表格行（以 | 开头，连续收集）
        if (/^\|/.test(t)) {
            closeList();
            if (!inTable) { tableRows = []; inTable = true; }
            tableRows.push(t);
            return;
        }
        //非表格行时，若在表格中则先结束表格
        if (inTable) closeTable();

        //标题（支持 # 后有无空格，1-4 级）
        if (/^#{1,4}\s*\S/.test(t)) {
            closeList();
            const level = Math.min(t.match(/^#+/)[0].length, 4);
            const title = applyInline(t.replace(/^#+\s*/, ''));
            out += '<h' + level + '>' + title + '</h' + level + '>';
            return;
        }
        //无序列表项（- * ）
        if (/^[-*•]\s/.test(t)) {
            closeTable(); closeList();
            if (!inUl) { out += '<ul>'; inUl = true; }
            out += '<li>' + withActivityIcon(applyInline(t.replace(/^[-*•]\s/, ''))) + '</li>';
            return;
        }
        //有序列表项
        if (/^\d+[.、]\s/.test(t)) {
            closeTable(); closeList();
            if (!inOl) { out += '<ol>'; inOl = true; }
            out += '<li>' + withActivityIcon(applyInline(t.replace(/^\d+[.、]\s/, ''))) + '</li>';
            return;
        }
        //引用
        if (/^>\s?/.test(t)) {
            closeList();
            out += '<blockquote>' + applyInline(t.replace(/^>\s?/, '')) + '</blockquote>';
            return;
        }
        //分隔线
        if (/^---+$/.test(t) || /^\*\*\*+$/.test(t) || /^___+$/.test(t)) {
            closeList();
            out += '<hr>';
            return;
        }
        //空行：关闭列表
        if (t === '') { closeList(); return; }

        //普通段落
        closeList();
        out += '<p>' + applyInline(t) + '</p>';
    });

    //收尾
    if (inUl) out += '</ul>';
    if (inOl) out += '</ol>';
    if (inCode) out += '</pre>';
    if (inTable) closeTable();

    return out;
}

/* ============================================================ A4 旅行规划表（按《旅行规划表设计提示词》区块渲染） 区块：标题区 / 概览 / 时间表(时间轴) / 交通 / 住宿餐饮 / 实用信息 新增：行程概览区（自动从时间表中提取当天活动）+ 时间轴样式 ============================================================ */

/** 区块分类规则：标题关键词 -> 样式类 + 图标 */
const SECTION_RULES = [
    { cls: 'sec-overview',  icon: '🗺️', keys: ['概览', '简介', '亮点', '摘要', 'overview'] },
    { cls: 'sec-timeline',  icon: '🕒', keys: ['时间表', '时间轴', '详细行程', '日程', '行程安排', '安排', 'timeline'] },
    { cls: 'sec-transport', icon: '🚄', keys: ['交通', '路线', '换乘', '地铁', '公交', 'transport'] },
    { cls: 'sec-stay',      icon: '🏨', keys: ['住宿', '酒店', '餐饮', '美食', '餐厅', '用餐', 'stay'] },
    { cls: 'sec-tips',      icon: '📌', keys: ['实用', '提示', '注意', '预算', '费用', '电话', '行李', '花费', 'tips'] },
];

/** 根据标题文本判断所属区块 */
function classifySection(title) {
    for (const rule of SECTION_RULES) {
        if (rule.keys.some(k => title.includes(k))) return rule;
    }
    return { cls: 'sec-other', icon: '📋', keys: [] };
}

/** 将 markdown 最终结果渲染为 A4 行程规划表 HTML 结构：.plan-sheet ├── .plan-header（行程标题区：主标题 + 天数 meta） └── .plan-section x N（概览/时间表/交通/住宿餐饮/实用信息） */
function renderPlanSheet(text) {
    if (!text) return '<div class="empty">（空响应）</div>';

    const mdHtml = renderMarkdown(text);
    const temp = document.createElement('div');
    temp.innerHTML = mdHtml;

    const sheet = document.createElement('div');
    sheet.className = 'plan-sheet';

    //第一个标题：若不是区块关键词，视为行程主标题放进标题区
    const headings = temp.querySelectorAll('h1, h2, h3');
    const titleNode = headings.length ? headings[0] : null;
    const firstRule = titleNode ? classifySection(titleNode.textContent || '') : null;
    const hasRealTitle = !!titleNode && firstRule.keys.length === 0;

    //---- 行程标题区 ----
    const header = document.createElement('div');
    header.className = 'plan-header';
    const titleText = hasRealTitle ? titleNode.textContent.trim() : '旅行规划';
    const h = document.createElement('h2');
    h.className = 'plan-title';
    h.textContent = titleText;
    header.appendChild(h);

    //meta 行：从标题/全文提取行程天数（支持阿拉伯数字与中文数字，如"3日游"/"三日游"）
    const meta = document.createElement('div');
    meta.className = 'plan-meta';
    const cnDigits = { '零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9 };
    /** 中文数字 -> 整数（支持 一~九、十、X十、X十Y） */
    function cnNumToInt(s) {
        if (/^[零一二两三四五六七八九]$/.test(s)) return cnDigits[s];
        const m = s.match(/^([零一二两三四五六七八九])?十([零一二三四五六七八九])?$/);
        if (m) return (m[1] ? cnDigits[m[1]] : 1) * 10 + (m[2] ? cnDigits[m[2]] : 0);
        return NaN;
    }
    let planDays = null;
    //优先从主标题提取（"上海一日游"->1、"北京三日游"->3）
    const titleNum = (titleText || '').match(/(\d+)\s*日/);
    const titleCn = (titleText || '').match(/([零一二两三四五六七八九十]+)\s*日/);
    if (titleNum) planDays = parseInt(titleNum[1], 10);
    else if (titleCn) { const d = cnNumToInt(titleCn[1]); if (!isNaN(d)) planDays = d; }
    //标题没有时再从全文提取（注意避免误中日期里的"N日"）
    if (planDays === null) {
        const fullNum = text.match(/(\d+)\s*日/);
        const fullCn = text.match(/([零一二两三四五六七八九十]+)\s*日/);
        if (fullNum) planDays = parseInt(fullNum[1], 10);
        else if (fullCn) { const d = cnNumToInt(fullCn[1]); if (!isNaN(d)) planDays = d; }
    }
    if (planDays !== null && !isNaN(planDays)) {
        const d = document.createElement('span');
        d.textContent = '🗓 行程天数：' + planDays + ' 天';
        meta.appendChild(d);
    }
    //行程标题区增强：提取出行日期（如 2025年3月30日）
    const dateMatch = text.match(/(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日/);
    if (dateMatch) {
        const ds = document.createElement('span');
        ds.textContent = '📅 ' + dateMatch[1] + '年' + dateMatch[2] + '月' + dateMatch[3] + '日';
        meta.appendChild(ds);
    }
    //行程标题区增强：提取天气摘要（如 **天气**：阴，13C/7C，东风1-3级）
    //修复：原正则"天气[\s:：]*"会误匹配"天气与注意事项"标题（天气后直接跟"与"没冒号）-> 标签栏出现孤零零的"与注意事项"
    //改为要求"天气"后必须紧跟冒号/空白/内容分隔，排除标题误匹配
    const weatherMatch = text.match(/天气[\s:：]+([^\n]{2,24})/);
    if (weatherMatch && !weatherMatch[1].startsWith("与") && !weatherMatch[1].startsWith("和")) {
        const w = weatherMatch[1].replace(/^[*\s:：]+/, '').replace(/[，。;；,]\s*$/, '').trim();
        if (w) {
            const ws = document.createElement('span');
            ws.textContent = '🌤 ' + w;
            meta.appendChild(ws);
        }
    }
    const gen = document.createElement('span');
    gen.textContent = '📝 由 AI 旅行规划助手生成';
    meta.appendChild(gen);
    header.appendChild(meta);
    sheet.appendChild(header);

    //---- 正文分区块 ----
    let section = null;       // 当前区块容器
    let usedTitle = false;    // 主标题是否已放入标题区

    /** 确保存在当前区块（无则创建默认区） */
    const ensureSection = () => {
        if (!section) {
            section = document.createElement('div');
            section.className = 'plan-section sec-other';
            const st = document.createElement('div');
            st.className = 'sec-title';
            st.innerHTML = '<span class="sec-icon">📋</span><span>行程详情</span>';
            section.appendChild(st);
            sheet.appendChild(section);
        }
        return section;
    };

    /** 按分类规则开启新区块 */
    const newSection = (rule, rawTitle) => {
        section = document.createElement('div');
        section.className = 'plan-section ' + rule.cls;
        const st = document.createElement('div');
        st.className = 'sec-title';
        st.innerHTML = '<span class="sec-icon">' + rule.icon + '</span><span>' +
            applyInline(escapeHtml(rawTitle)) + '</span>';
        section.appendChild(st);
        sheet.appendChild(section);
        return section;
    };

        /* 行程概览区：遍历正文，把"第X天"标题及其下的首个表格/列表提取为 概览卡片（目的地+当天要点），符合文档"按日期分区的行程简表"要求 */
    const overview = document.createElement('div');
    overview.className = 'plan-section sec-overview';
    const ovTitle = document.createElement('div');
    ovTitle.className = 'sec-title';
    ovTitle.innerHTML = '<span class="sec-icon">🗺️</span><span>行程概览</span>';
    overview.appendChild(ovTitle);

    const dayHeadings = Array.from(temp.querySelectorAll('h1, h2, h3, h4'))
        //匹配中文"第一天/第1天" 或 英文"Day 1"（DeepSeek 倾向输出 Day N，Qwen 倾向中文）
        .filter(h => /第\s*[一二三四五六七八九十\d]+\s*[天日]|Day\s*\d+/i.test(h.textContent || ''));

    let overviewBuilt = false;
    if (dayHeadings.length > 0) {
        const grid = document.createElement('div');
        grid.className = 'overview-grid';
        dayHeadings.forEach(h => {
            const card = document.createElement('div');
            card.className = 'overview-card';
            const dayName = document.createElement('b');
            //"Day 1：xxx" -> "第1天：xxx"（保持概览卡片中文显示一致）
            dayName.textContent = h.textContent.trim()
                .replace(/^Day\s*(\d+)/i, '第$1天');
            card.appendChild(dayName);
            //用户要求：概览卡片只显示标题，不提取内容（内容在下面行程详情里完整展示）
            //移除原"取标题后表格/列表/段落"逻辑--卡片保持简洁美观
            grid.appendChild(card);
        });
        overview.appendChild(grid);
        sheet.appendChild(overview);
        overviewBuilt = true;
    }

    Array.from(temp.childNodes).forEach(node => {
        //忽略纯文本/换行节点
        if (node.nodeType !== 1) return;

        const tag = node.tagName.toLowerCase();

        //主标题已放入标题区，正文跳过
        if (hasRealTitle && !usedTitle && node === titleNode) {
            usedTitle = true;
            return;
        }

        //日期/天气摘要已放入标题区 meta，正文段落中跳过，避免重复
        if (tag === 'p' && /^(日期|天气)[:：]/.test((node.textContent || '').trim())) {
            return;
        }

        //标题 -> 开启新区块（按关键词分类）
        if (tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4') {
            const rule = classifySection(node.textContent || '');
            newSection(rule, node.textContent.trim());
            return;
        }

        //其他内容（段落/表格/列表等）放入当前区块
        ensureSection().appendChild(node);
    });

    //空正文兜底
    if (sheet.querySelectorAll('.plan-section').length === 0) {
        const emptyDiv = document.createElement('div');
        emptyDiv.className = 'empty';
        emptyDiv.textContent = '（空响应）';
        ensureSection().appendChild(emptyDiv);
    }

    return sheet.outerHTML;
}

/* ============================================================ 结构化行程渲染（阶段三：直接渲染后端 JSON，不再正则猜测） 后端 /result 返回 TripPlanData JSON（含降级 markdown 字段）， 此处直接按 sections 分区块渲染，替代 classifySection/activityIcon 等猜测逻辑。 */

/** 区块类型 -> 图标 / 样式类 */
const STRUCT_ICON = { overview: '🗺️', timeline: '🕒', transport: '🚄', stay: '🏨', tips: '📌' };
const STRUCT_CLASS = { overview: 'sec-overview', timeline: 'sec-timeline', transport: 'sec-transport', stay: 'sec-stay', tips: 'sec-tips' };

/** F-5：城市 -> 地标 emoji（标题区差异化装饰） */
const CITY_LANDMARKS = {
    '北京': '🏯', '上海': '🗼', '广州': '🗼', '深圳': '🌆', '杭州': '🛶', '苏州': '🏞️',
    '成都': '🐼', '重庆': '🌉', '西安': '🏯', '南京': '🏛️', '武汉': '🏯', '厦门': '🌊',
    '青岛': '🌊', '大理': '🏔️', '丽江': '🏔️', '桂林': '⛰️', '三亚': '🏖️', '惠州': '🏞️',
    '珠海': '🌊', '长沙': '🏞️', '天津': '🏛️', '哈尔滨': '❄️', '敦煌': '🏜️', '拉萨': '🏔️'
};

/** 按区块类型把一条 item 拼成可读文本 */
function formatSectionItem(type, item) {
    item = item || {};
    const pick = (...vals) => vals.filter(v => v).join(' · ');
    switch (type) {
        case 'timeline':
            return (item.time ? item.time + '  ' : '') + pick(item.place, item.activity, item.cost);
        case 'transport': {
            //★交通条目的信息不一定都在 from/to —— 实测有「途经主要公路」「逐日车程汇总」这类
            //  只有 activity/text 的条目（无 from/to）。原来只拼 from→to，这类条目会被拼成空串、
            //  再被 stripParens 清掉、最后被 filter 丢整条 → "交通与车程"只剩 1 条。
            //  另外 from===to（环线起终点重合）时 from→to 对用户没意义，改用 activity 描述。
            const sameCity = item.from && item.to && item.from === item.to;
            let t = '';
            if (item.from && item.to && !sameCity) t = item.from + ' → ' + item.to;
            else if (item.from && !item.to) t = item.from;
            else if (item.to && !item.from) t = item.to;
            if (!t) t = pick(item.activity, item.text);
            else {
                const extra = pick(item.activity, item.text);
                if (extra && extra !== t) t += ' · ' + extra;
            }
            if (item.way || item.duration) t += '（' + pick(item.way, item.duration) + '）';
            return t;
        }
        case 'stay':
            return pick(item.name, item.category, item.price);
        case 'tips':
            return (item.kind ? item.kind + '：' : '') + (item.text || '');
        default:
            return pick(item.activity, item.place, item.text, item.name);
    }
}


/** ★用户口径：详情弹窗里酒店/餐饮那行的【地名标题】（奎屯市/阜康市这类）应跳高德，不弹卡片；只判断行政区名，别的照旧 */
function isCityOrAreaName(s) {
    const t = stripParens(String(s == null ? '' : s)).trim();
    if (!t || t.length > 8) return false;
    return /(市|县|区|镇|乡|州|盟|旗)$/.test(t) && !/景区|城区|分区|园区|街区|厂区/.test(t);
}

/** 去掉括号及其内容（交付文案不允许出现补充说明/过程备注），并压掉多余空白 */
function stripParens(s) {
    let t = String(s == null ? '' : s);
    t = t.replace(/[（(][^）)]*[）)]/g, ' ');    // 中英文圆括号
    t = t.replace(/[【\[][^】\]]*[】\]]/g, ' ');  // 方括号
    return t.replace(/\s+/g, ' ').trim();
}

/** 过程性/内部字样黑名单 —— 这些是给开发看的，不给用户看 */
const NOISE_WORDS = ['数据获取失败', '获取失败', '未采用', '衔接失败', '衔接数据', '校验', '硬违反',
    '可疑', '无法判定', '打回', '降级交付', '无数据', '失败'];
/** 是否属于过程性/内部字样（命中则不显示该条） */
function isNoise(s) {
    const t = String(s == null ? '' : s);
    return NOISE_WORDS.some(w => t.includes(w));
}

/** 调度中心每一步的友好说法（拿不到干净内容时就用它） */
const THINK_STAGE_TEXT = {
    '解析': '正在理解你的需求', '偏好': '正在整理出行偏好', '行程': '正在规划行程',
    'Agent结果': '专家正在规划', '整合': '正在整理行程', '诊断': '规划中',
    '校验': '正在核对行程', '完成': '行程已生成', '取消': '已取消'
};
/** 调度中心文案：只输出给用户看的人话 —— 去掉括号、失败字样、工具名、字符数、校验术语 */
function friendlyThinkText(ev) {
    const stage = ev.type || '';
    let c = stripParens(String(ev.content || ''))
        .replace(/[✅⚠️❌★·•]+/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    const dirty = !c || isNoise(c) || /成功|失败|字符|工具|条时长为空|硬违反|原始产出/.test(c);
    return dirty ? (THINK_STAGE_TEXT[stage] || '规划中...') : c.slice(0, 60);
}

//==================== 功能4：多方案对比（MVP） ====================

/** 当前多方案集合（内存态） + 当前选中索引 */
let planSetData = null;
let planSetIndex = 0;

const STYLE_LABELS = { food: '🍜 美食向', photo: '📷 出片向', budget: '💰 省钱向' };

/** 渲染多方案对比：顶部风格标签切换 + 当前方案详情 + 选中按钮 */
function renderPlanSet(set) {
    planSetData = set;
    planSetIndex = 0;
    const plans = set.plans || [];
    if (plans.length === 0) return '<div class="empty">（多方案为空）</div>';

    //顶部风格胶囊栏（复用 scheme 样式）
    let tabs = '<div class="schemes"><div class="schemes-label">旅行风格 · PICK A STYLE</div><div class="scheme-row">';
    plans.forEach((p, i) => {
        const label = STYLE_LABELS[set.styles?.[i]] || ('方案' + (i + 1));
        tabs += '<div class="scheme' + (i === 0 ? ' active' : '') + '" onclick="switchPlan(' + i + ')">' + label + '</div>';
    });
    tabs += '</div></div>';

    //当前方案详情
    tabs += '<div id="plansetBody" class="planset-body"></div>';

    //渲染当前方案（异步回填 body）
    setTimeout(() => { refreshPlanSetBody(); }, 0);
    return tabs;
}

/** 刷新当前选中方案的详情区 */
function refreshPlanSetBody() {
    if (!planSetData) return;
    const p = planSetData.plans[planSetIndex];
    const label = STYLE_LABELS[planSetData.styles?.[planSetIndex]] || '';
    const slogan = planSetData.slogans?.[planSetIndex] || '';
    let html = '<div class="planset-slogan">' + label + (slogan ? ' · ' + slogan : '') + '</div>';
    html += renderStructuredPlan(p); // 复用单方案渲染
    document.getElementById('plansetBody').innerHTML = html;
    //更新胶囊高亮(胶囊在 resultBody 里)
    document.querySelectorAll('#resultBody .scheme').forEach((b, i) => {
        b.classList.toggle('active', i === planSetIndex);
    });
    //记录当前方案 markdown(供保存当前看的方案)
    lastResultMarkdown = p.markdown || '';
}

/** 切换到第 i 套方案 */
function switchPlan(i) {
    planSetIndex = i;
    refreshPlanSetBody();
}

/** 当前渲染中的行程数据（供详情弹窗读取） */
let currentPlanData = null;

/** 将结构化 TripPlanData 渲染为「轨迹 + 天卡片」结构 */
function renderStructuredPlan(plan) {
    if (!plan) return '<div class="empty">（空响应）</div>';
    currentPlanData = plan;
    const sections = plan.sections || [];

    //提取天数区块（timeline）与其余区块
    const days = sections.filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const others = sections.filter(s => s.type !== 'timeline' && !/第.+天/.test(s.title || ''));

    //标题头
    let html = '<div class="plan-header">';
    const landmark = CITY_LANDMARKS[plan.destination] || '';
    html += '<h2 class="plan-title">' + escapeHtml(plan.title || '旅行规划') + '</h2>';
    html += '<div class="plan-meta">';
    html += '<button type="button" class="meta-btn" onclick="openInsightModal()" title="查看该城市价格预测与未来7天客流">📈 价格·客流预测</button>';
    //★区域天气（用户口径）：行程涉及的城市/县 × 未来三天，城市级；数据由行程生成后异步取（不占核心流程）
    html += '<button type="button" class="meta-btn" onclick="openRegionWeather()" title="查看行程涉及区域的未来三天天气（城市级）">🌤 区域天气</button>';
    //★用户口径：行程页只能通过这个 × 关闭（刷新/跳转返回后仍然显示，不会自动消失）
    html += '<button type="button" class="meta-btn trip-close" onclick="closeTripView()" title="关闭行程（关闭后刷新不再自动恢复）">✕ 关闭</button>';
    if (plan.days) html += '<span>🗓 <b>' + plan.days + '</b> 天</span>';
    if (plan.date) html += '<span class="date-chip" id="dateChip" style="cursor:pointer" title="点击查看日历" onclick="openTripCalendar()">📅 ' + escapeHtml(plan.date) + '</span>';
    const wth = cleanWeatherText(plan.weather);
    if (wth) html += '<span>🌤 ' + escapeHtml(wth) + '</span>';
    html += '</div></div>';

    //参考卡已收纳为上方「 价格-客流预测」按钮弹窗，不再自动浮出

    //无 timeline 区块：整单走 markdown 兜底（历史行程兼容）
    if (days.length === 0) {
        html += '<div class="plan-sheet">'
            + (plan.markdown ? renderMarkdown(plan.markdown) : '<div class="empty">（空响应）</div>')
            + '</div>';
        return html;
    }

    //===== 轨迹时间轴（第 1 天） =====
    const day0 = days[0];
    const d0Items = (day0.items || []).filter(it => it && (it.time || it.place || it.activity));
    //★用户口径：轨迹展示不能有重复地点 —— 同一天内同一地点只保留首次出现（详情弹窗不受影响）
    const d0Shown = dedupeByPlace(d0Items);   //★用户口径：轨迹与概览卡片完全一致（去掉最多6个的限制）
    html += '<div class="route-line">'
        + '<svg class="bg" viewBox="0 0 1050 100" preserveAspectRatio="none" fill="none">'
        + '<path d="' + routePath(d0Shown.length) + '" stroke="rgba(31,174,143,.55)" stroke-width="2.4" stroke-dasharray="6 8"/></svg>'
        + '<div class="stops">'
        + d0Shown.map((it, i) => {
            const hl = i === 1 || i === d0Shown.length - 2; // 关键点高亮（第2个与倒数第2个）
            //★用户口径：轨迹里的酒店/餐饮也点开卡片、不跳转（其余条目仍是原来的高德链接）
            const pl = poiOrLinkHtml(it, it.place || it.activity || '');
            return '<div class="stop' + (hl ? ' hl' : '') + '">'
                + '<div class="dot"></div><div class="tm">' + escapeHtml(it.time || '') + '</div>'
                + '<div class="pl">' + pl.html + '</div>'
                + '<div class="d">' + escapeHtml(it.cost || '') + '</div></div>';
        }).join('')
        + '</div></div>';

    //===== 天卡片：保持【原有样式】逐行列出当天站点（地点名由后端 LLM 在 stops 里给） =====
    html += '<div class="days">';
    days.forEach((day, di) => {
        const items = (day.items || []).filter(it => it && (it.time || it.place || it.activity));
        //★用户口径：概览卡片与上方「轨迹时间轴」显示一致 —— 同样逐条列出当天的【每一条安排】
        //  （出发/交通/景点/住宿/餐饮），文案与上方完全同源：place || activity。
        //  卡片样式与页面其他部分一律不动。后端 stops 仅在没有可用条目时兜底。
        //★用户口径：概览卡片不能有重复地点 —— 与轨迹同样按"首次出现"去重（详情弹窗不受影响）
        //★保留原始 item（酒店/餐饮要能点开卡片，用户口径）；文案/去重/兜底逻辑一字不改
        let stopObjs = dedupeByPlace(items)
            .map(it => ({ pl: stripParens(it.place || it.activity || ''), it: it }))
            .filter(o => o.pl && !isNoise(o.pl));
        if (!stopObjs.length) {
            let bs = (day.stops || []).map(s => stripParens(s)).filter(s => s && !isNoise(s));
            bs = bs.filter((s, i) => bs.indexOf(s) === i); // 后端兜底数据也去重
            stopObjs = bs.map(s => ({ pl: s, it: null })); // 兜底数据没有原始 item，只能渲染成纯链接
        }
        html += '<div class="day-card' + (di === 0 ? ' active' : '') + '" onclick="selectDay(' + di + ')">'
            + '<div class="day-top">'
            + '<span class="n">DAY ' + String(di + 1).padStart(2, '0') + '</span>'
            + '<button class="expand" id="dayMapBtn_' + di + '" title="查看当天高德路线图" onclick="event.stopPropagation();openDayMap(' + di + ')"><i class="fa-solid fa-map-location-dot"></i></button>'
            + '<button class="expand" title="查看当日详细规划" onclick="event.stopPropagation();openDayModal(' + di + ')"><i class="fa-solid fa-pen"></i></button>'
            + '</div>'
            + stopObjs.map(o => {
                //★用户口径：概览卡片里的酒店/餐饮也点开卡片、不跳转（其余条目仍是原来的高德链接）
                const p = poiOrLinkHtml(o.it, o.pl);
                //★用户口径：卡片结构改成与轨迹完全一致（轨迹是 <div class="pl">，卡片原来是两层 span ✗
                //  实测同一地名轨迹能点、卡片不能点）
                return '<div class="item"><span class="ic">' + activityIcon(o.pl) + '</span>'
                    + '<span class="pl">' + p.html + '</span></div>';
            }).join('')
            + '</div>';
    });
    html += '</div>';

    //===== 其余区块（交通/住宿/贴士） =====
    if (others.length > 0) {
        html += '<div class="plan-sheet">';
        others.forEach(sec => {
            const type = sec.type || 'other';
            //交付文案清洗：去掉括号及内容、滤掉过程性/内部字样（数据获取失败/未采用/校验…），
            //实用信息与住宿餐饮只保留前几条（"推荐几个就行"）
            //★保留原始 item（仅供"住宿行可点"用；文本渲染逻辑一字未改）
            let rows = (sec.items || [])
                .map(it => ({ text: stripParens(formatSectionItem(type, it)), item: it || {} }))
                .filter(r => r.text && !isNoise(r.text));
            if (type === 'tips' || type === 'stay') rows = rows.slice(0, 6);
            if (!rows.length) return;
            html += '<div class="plan-section ' + (STRUCT_CLASS[type] || 'sec-other') + '">'
                + '<div class="sec-title"><span class="sec-icon">' + (STRUCT_ICON[type] || '📋') + '</span><span>' + escapeHtml(stripParens(sec.title || '行程详情')) + '</span></div><ul>'
                + rows.map(r => {
                    //★用户口径：住宿行可点 → 弹高德评分/电话/地址（不跳转）
                    //  住宿与餐饮推荐(stay) 和 实用信息(tips) 两个区块里的酒店都要能点
                    //  （tips 区块把酒店写在 text 里，靠 isHotelItem/hotelNameOf 统一识别）
                    //  非酒店行输出与改动前完全一致（还是 <li>文本</li>）
                    if ((type === 'stay' || type === 'tips') && (isHotelItem(r.item) || isFoodItem(r.item))) {
                        //★酒店 / 餐饮都做成带虚线的可点元素（同款效果：点开卡片看评分·电话·地址）
                        const wrapped = wrapHotelNames(escapeHtml(r.text), r.item);
                        if (wrapped.firstId) {
                            return '<li class="poi-row" onclick="openPoiCardById(\'' + wrapped.firstId + '\')" title="查看高德信息">'
                                + wrapped.html + '<span class="poi-go">' + (isHotelItem(r.item) ? '🏨' : '🍽') + '</span></li>';
                        }
                    }
                    return '<li>' + escapeHtml(r.text) + '</li>';
                }).join('')
                + '</ul></div>';
        });
        html += '</div>';
    }

    //★行程已生成完整。区域天气/地点价格改为【按需查询】（点"区域天气""价格·客流预测"或
    //  直接点酒店时才查）—— 原来在这里就一次性查 30+ 个点要 20 多秒，用户一点酒店就撞上它。
    //  （行程生成/校验等核心链路与此无关，一行没动。）

    return html;
}

/** 按当日景点数粗估徒步公里数（不用太具体：4个景点≈3km，每多2个+1km，封顶8km） */
function estWalkKm(itemCount) {
    const n = itemCount || 0;
    const km = Math.min(8, Math.max(3, 3 + Math.floor((n - 3) / 2)));
    return km;
}

/**
 * 详情弹窗专用：地图链接 +（可选）酒店标记。
 * 可点行里必须阻止冒泡 —— 否则点"地图链接"会同时弹出酒店卡片（等于改坏了原有功能）。
 * hid 为空时返回值与改动前的 amapLink(...) 完全一致。
 */
function amapLinkWrap(it, hid, icon) {
    const link = amapLink(stripParens(it.place || it.activity || ''), cityOfItem(it));
    if (!hid) return link;
    return '<span onclick="event.stopPropagation()">' + link + '</span><span class="poi-go">' + (icon || '🏨') + '</span>';
}

/** 记住"当前正在看的行程 id"——只记 id，不在浏览器缓存行程内容（行程始终以后端 trip_plans 为准） */
function rememberPlanId(id) {
    try { if (id) localStorage.setItem('atp_last_plan_id', String(id)); } catch (e) { }
}

/**
 * ★跳转/刷新回来时把行程恢复出来。
 *   行程是**后端的数据**（trip_plans 表，生成完就入库）—— 从服务器取回，浏览器不存副本。
 *   优先取"上次在看的那条"，没有就取最新一条；用户点过 × 才不再自动恢复。
 */
function restoreLastPlanFromServer(done) {
    try {
        if (localStorage.getItem('atp_trip_closed') === sessionId) { if (done) done(false); return; }
    } catch (e) { }
    fetch('/api/my-plans')
        .then(r => r.json())
        .then(d => {
            const plans = (d && d.plans) || [];
            if (!plans.length) { if (done) done(false); return; }
            let wantId = 0;
            try { wantId = Number(localStorage.getItem('atp_last_plan_id') || 0); } catch (e) { }
            const p = plans.find(x => Number(x.id) === wantId) || plans[0];
            let plan = p.plan_json;
            if (typeof plan === 'string') { try { plan = JSON.parse(plan); } catch (e) { plan = null; } }
            if (!plan || !plan.sections) { if (done) done(false); return; }
            currentPlanData = plan;
            viewingPlanId = p.id;
            document.getElementById('resultBody').innerHTML = renderStructuredPlan(plan);
            document.getElementById('sendBtn').disabled = false;
            const cb = document.getElementById('cancelBtn');
            if (cb) cb.style.display = 'none';
            if (done) done(true);
        })
        .catch(() => { if (done) done(false); });
}

/** 用户口径：行程页只能通过右上角 × 关闭；关闭后刷新/跳转返回都不再自动恢复（重新生成才显示） */
function closeTripView() {
    try { localStorage.setItem('atp_trip_closed', sessionId); } catch (e) { }
    try { localStorage.removeItem('atp_gen_running'); } catch (e) { }
    try { localStorage.removeItem('atp_last_plan_id'); } catch (e) { } //★点了 × 就不再自动恢复行程
    const rb = document.getElementById('resultBody');
    if (rb) rb.innerHTML = '';
    const tb = document.getElementById('thinkBody');
    if (tb) tb.innerHTML = '<div class="item"><span class="ts">--:--</span><span class="msg">发送需求后，这里会实时显示每一步进展</span></div>';
    stopThinkClock(false); stopGenSmooth(); thinkIdx = -1; hideThinkPanel();
    thinkFired = new Set(); thinkSig = null; thinkPct = 0;
    setThinkLive('live');
    const u2 = document.getElementById('thinkUsed'); if (u2) u2.textContent = '00:00';
    const e3 = document.getElementById('thinkEta'); if (e3) e3.textContent = '预计还需 —';
    updateGenProgress(0, null);
    const f2 = document.querySelector('#thinkPanel .gen-fill'); if (f2) f2.style.width = '0%';
    const p2 = document.querySelector('#thinkPanel .gen-pct'); if (p2) p2.textContent = '0%';
    const n2 = document.getElementById('tpStageName'); if (n2) n2.textContent = '等待开始';
    const d2 = document.getElementById('tpStageDesc'); if (d2) d2.textContent = '发送需求后开始生成行程';
}

/** 地名 -> 高德定位链接(点击先查经纬度->直接定位;查不到降级搜索页) */
function amapLink(place, cityOverride) {
    const name = (place || '').trim();
    if (!name) return '';
    //★优先用"这个地点自己所在的城市"（如 奎屯市），没有才退回行程目的地
    const city = String(cityOverride || '').trim()
        || ((currentPlanData && currentPlanData.destination) ? currentPlanData.destination.trim() : '');
    if (window.AmapNav && AmapNav.setCity) { try { AmapNav.setCity(city); } catch (e) { } }
    const navBase = (window.AmapNav && AmapNav.url) ? AmapNav.url(name, city)
        : ((window.AmapNav && AmapNav.url) ? AmapNav.url(name, city)
            : ('https://ditu.amap.com/search?query=' + encodeURIComponent((String(city || '').trim() ? String(city).trim() + ' ' : '') + name)));
    //★根因修复：name/city 会进入【属性】里（href/title/onclick）✗
    //  名字里只要带 " 或 < 就会把标签截断 → 页面显示成乱码文字（实测"好运酒店"那行整段代码露出来）
    //  → 属性值一律转义；JS 字符串里再转义引号
    const attr = String(name).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const attrCity = String(city).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const jsName = String(name).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"').replace(/</g, '\\u003c');
    const jsCity = String(city).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"').replace(/</g, '\\u003c');
    return '<a class="place-link" href="' + navBase
        + '" target="_blank" rel="noopener noreferrer" onclick="event.stopPropagation();gotoAmap(this.href, \'' + jsName + '\', \'' + jsCity + '\');return false" title="在高德查看并导航到「' + attr + '」">'
        + escapeHtml(name) + '</a>';
}

/** 点击景点：跳马蜂窝搜索页 */
function gotoAmap(fallbackUrl, name, city) {
    //★用户口径：概览/轨迹/卡片里点地点名 → 高德一键导航（不再跳马蜂窝）
    var u = '';
    try { u = (window.AmapNav && AmapNav.url) ? AmapNav.url(name, city) : ''; } catch (e) { }
    window.open(u || fallbackUrl || ((window.AmapNav && AmapNav.url) ? AmapNav.url(name, city)
            : ('https://ditu.amap.com/search?query=' + encodeURIComponent((String(city || '').trim() ? String(city).trim() + ' ' : '') + name))), '_blank');
}

/** 按点数生成轨迹曲线路径 */
/** 提取第 dayIdx 天（0基）的地点序列； */
function dayPlaces(dayIdx) {
    if (!currentPlanData || !currentPlanData.sections) return null;
    const days = (currentPlanData.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const day = days[dayIdx];
    if (!day) return null;
    const places = [];
    const push = (v) => {
        const s = stripParens(String(v == null ? '' : v).trim());
        if (!s || s.length > 24 || isNoise(s)) return;
        if (!places.includes(s)) places.push(s);
    };
    for (const it of (day.items || [])) {
        //★按【当天真实行进顺序】取点：交通条目只有 from→to（如 乌鲁木齐→天山天池），
        //  景点/住宿/餐饮条目只有 place。
        //  原来只收 place → 当天"出发点"丢失、终点也可能缺（实测 乌鲁木齐→天山天池→奎屯市
        //  的地图只画出 天山天池→阜康 一段），路线因此不完整。
        if (it.from || it.to) { push(it.from); push(it.to); }
        else push(it.place || it.name);
        if (places.length >= 8) break; // 高德网页路线链接不宜过长，取当天主要节点
    }
    return { title: day.title || ('第' + (dayIdx + 1) + '天'), places: places };
}

//按天路线缓存：dayMapUrls[i] = 第i天(0基)的 ditu 路线链接（含全部当天景点）
//缓存绑定行程指纹(标题+天数的hash)：切换行程后即使天数索引相同也会强制重新生成，杜绝跨行程串图
let dayMapUrls = [];
let dayMapTitles = [];
let dayMapBusy = false;
let dayMapPlanFp = '';
function planFingerprint() {
    if (!currentPlanData) return '';
    const days = (currentPlanData.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const titles = days.map(d => d.title || '').join('|');
    return (currentPlanData.title || '') + '#' + titles;
}

/** 生成第 dayIdx 天的路线图并打开（若已生成直接打开； */
function openDayMap(dayIdx, cb) {
    const d = dayPlaces(dayIdx);
    if (!d || !d.places.length) { if (cb) cb(false); return; }
    if (dayMapUrls[dayIdx] && dayMapPlanFp === planFingerprint()) {
        window.open(dayMapUrls[dayIdx], '_blank');
        if (cb) cb(true);
        return;
    }
    if (dayMapPlanFp !== planFingerprint()) { // 行程已切换：旧缓存作废
        dayMapUrls = [];
        dayMapTitles = [];
        dayMapPlanFp = planFingerprint();
    }
    if (dayMapBusy) { // 生成中：稍后重试
        setTimeout(() => openDayMap(dayIdx, cb), 600);
        return;
    }
    //点击即时反馈：按钮转圈+状态提示，避免"没反应"的几秒空隙
    const btn = document.getElementById('dayMapBtn_' + dayIdx);
    const orig = btn ? btn.innerHTML : '';
    if (btn) btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i>';
    const msg = document.getElementById('saveMsg');
    if (msg) msg.textContent = '🗺️ 正在生成第' + (dayIdx + 1) + '天高德路线图...';
    dayMapBusy = true;
    fetch('/api/amap/personal-map', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: d.title, destination: (currentPlanData && currentPlanData.destination) || '', days: [d] })
    })
        .then(r => r.json())
        .then(res => {
            dayMapBusy = false;
            //恢复时重新按id查节点(闭包里的btn可能因行程重渲染已成死节点,导致按钮卡在转圈)
            const cur = document.getElementById('dayMapBtn_' + dayIdx);
            if (cur) cur.innerHTML = '<i class="fa-solid fa-map-location-dot"></i>';
            if (msg) msg.textContent = '';
            if (res.ok && res.webUrl) {
                dayMapUrls[dayIdx] = res.webUrl;
                dayMapTitles[dayIdx] = d.title;
                window.open(res.webUrl, '_blank');
                if (cb) cb(true);
            } else {
                if (msg) msg.textContent = '⚠️ 路线图生成失败，请重试';
                if (cb) cb(false);
            }
        })
        .catch(() => {
            dayMapBusy = false;
            const cur = document.getElementById('dayMapBtn_' + dayIdx);
            if (cur) cur.innerHTML = '<i class="fa-solid fa-map-location-dot"></i>';
            if (msg) msg.textContent = '⚠️ 网络异常，请重试';
            if (cb) cb(false);
        });
}

function routePath(n) {
    n = Math.max(2, n || 2);
    const pts = [];
    for (let i = 0; i < n; i++) {
        const x = 60 + (i * (930 / (n - 1)));
        const y = i % 2 === 0 ? 30 : 66;
        pts.push(x + ' ' + y);
    }
    let path = 'M ' + pts[0];
    for (let i = 1; i < pts.length; i++) {
        const prev = pts[i - 1].split(' ');
        const cur = pts[i].split(' ');
        const mx = ((+prev[0] + +cur[0]) / 2).toFixed(0);
        path += ' Q ' + mx + ' ' + (Math.min(+prev[1], +cur[1]) - 24) + ' ' + cur[0] + ' ' + cur[1];
    }
    return path;
}

/** 点击天卡片：切换轨迹显示该天 */
function selectDay(di) {
    if (!currentPlanData) return;
    const days = (currentPlanData.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    if (!days[di]) return;
    const day = days[di];
    const items = (day.items || []).filter(it => it && (it.time || it.place || it.activity));
    //★用户口径：点天卡片切换时，轨迹必须与概览卡片【完全一致】——
    //  用同一套去重（首次渲染用的是 dedupeByPlace(...).slice(0,6)），否则会冒出重复地名（实测"奎屯市"出现两次）
    const shown = dedupeByPlace(items);   //★同上：不再限制 6 个，与卡片一致
    //重绘轨迹
    const routeBox = document.querySelector('.route-line');
    if (routeBox) {
        routeBox.innerHTML = ''
            + '<svg class="bg" viewBox="0 0 1050 100" preserveAspectRatio="none" fill="none">'
            + '<path d="' + routePath(shown.length) + '" stroke="rgba(31,174,143,.55)" stroke-width="2.4" stroke-dasharray="6 8"/></svg>'
            + '<div class="stops">'
            + shown.map((it, i) => {
                const hl = i === 1 || i === items.length - 2;
                return '<div class="stop' + (hl ? ' hl' : '') + '">'
                    + '<div class="dot"></div><div class="tm">' + escapeHtml(it.time || '') + '</div>'
                    + '<div class="pl">' + amapLink(it.place || it.activity || '', cityOfItem(it)) + '</div>'
                    + '<div class="d">' + escapeHtml(it.cost || '') + '</div></div>';
            }).join('')
            + '</div>';
    }
    //卡片高亮
    document.querySelectorAll('.day-card').forEach((c, i) => c.classList.toggle('active', i === di));
    //状态副标题
    const st = document.getElementById('journeyDay');
    if (st) st.textContent = (day.title || ('第 ' + (di + 1) + ' 天')) + ' 的轨迹';
}

/** 打开某天详情弹窗 */
function openDayModal(di) {
    if (!currentPlanData) return;
    const days = (currentPlanData.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const day = days[di];
    if (!day) return;
    const items = (day.items || []).filter(it => it && (it.time || it.place || it.activity));
    document.getElementById('modalEm').textContent = activityIcon(day.title || '');
    document.getElementById('modalTitle').textContent = 'DAY ' + String(di + 1).padStart(2, '0') + ' · ' + (day.title || ('第 ' + (di + 1) + ' 天'));
    document.getElementById('modalSub').textContent = items.length + ' 个安排 · ' + currentPlanData.destination || '';
    document.getElementById('modalBody').innerHTML =
        items.filter(it => !isNoise(it.place || '') && !isNoise(it.activity || '')).map(it => {
            const hl = it.time && /^1[2-8]:/.test(it.time); // 午餐/下午时段高亮（近似）
            //★用户口径：住宿条目可点 → 弹高德给的评分/电话/地址（不跳转）
            //★住宿条目：把【每个】酒店名都做成带虚线的可点元素（用户口径：两个酒店都要能点、要有虚线）
            //★住宿/餐饮条目：把【每个】酒店/餐厅名都做成带虚线的可点元素（用户口径：餐饮与酒店同款）
            const dRaw = stripParens([it.activity, it.cost, it.note].filter(Boolean).join(' · '));
            const poiOk = isHotelItem(it) || isFoodItem(it);
            const wrapped = poiOk ? wrapHotelNames(escapeHtml(dRaw), it) : { html: escapeHtml(dRaw), firstId: '' };
            const hid = wrapped.firstId;
            const poiIcon = isHotelItem(it) ? '🏨' : (isFoodItem(it) ? '🍽' : '🏨');
            //★用户口径：地名是行政区名（奎屯市/阜康市这类）的那一行 → 不弹卡片，只让地名跳高德；
            //  同一行里的【店名】照旧弹卡片（汉庭酒店那类，用户确认是正常的）
            //★用户口径：不再弹卡片 → 整行不绑定"打开卡片"，地点名走高德链接
            return '<div class="detail-item' + (hl ? ' hl' : '') + (hid ? ' poi-row' : '') + '"'
                + '>'
                + '<span class="detail-time">' + escapeHtml(it.time || '') + '</span>'
                + '<div class="detail-body"><div class="p"><span class="em">' + activityIcon(it.activity || it.place || '') + '</span>'
                //★标题就是 place（实测酒店/餐厅名就写在这个字段）→ 也要带虚线、点开卡片且不跳转
                + amapLinkWrap(it, '', poiIcon) + '</div>'
                + '<div class="d">' + wrapped.html + '</div></div></div>';
        }).join('')
        + '<div class="detail-foot">'
        + '<span>📍 <b>' + items.length + '</b> 个安排</span>'
        + (items[0] ? '<span>🕐 <b>' + escapeHtml(items[0].time || '') + '</b> 起</span>' : '')
        + '</div>';
    document.getElementById('modalMask').classList.add('show');
}

/** 关闭详情弹窗 */
function closeModal() {
    document.getElementById('modalMask').classList.remove('show');
}

/** 生成旧式 A4 plan-sheet HTML（导出 Word 用，含全部区块） */
function renderLegacySheet(plan) {
    if (!plan) return '';
    const sections = plan.sections || [];
    let html = '<div class="plan-sheet">';
    html += '<div class="plan-header">';
    const landmark = CITY_LANDMARKS[plan.destination] || '';
    html += '<h2 class="plan-title">' + escapeHtml(plan.title || '旅行规划') + '</h2>';
    html += '<div class="plan-meta">';
    if (plan.days) html += '<span>🗓 行程天数：' + plan.days + ' 天</span>';
    if (plan.date) html += '<span class="date-chip" style="cursor:pointer" title="点击查看日历" onclick="openTripCalendar()">📅 ' + escapeHtml(plan.date) + '</span>';
    const wth = cleanWeatherText(plan.weather);
    if (wth) html += '<span>🌤 ' + escapeHtml(wth) + '</span>';
    html += '<span>📝 由 AI 旅行规划助手生成</span></div></div>';
    sections.forEach(sec => {
        const type = sec.type || 'other';
        html += '<div class="plan-section ' + (STRUCT_CLASS[type] || 'sec-other') + '">'
            + '<div class="sec-title"><span class="sec-icon">' + (STRUCT_ICON[type] || '📋') + '</span><span>' + escapeHtml(sec.title || '行程详情') + '</span></div><ul>';
        (sec.items || []).forEach(it => {
            const t = formatSectionItem(type, it);
            if (t) html += '<li>' + escapeHtml(t) + '</li>';
        });
        html += '</ul></div>';
    });
    //F-6 实用信息兜底
    if (!sections.some(s => s.type === 'tips' && (s.items || []).length > 0)) {
        html += '<div class="plan-section sec-tips"><div class="sec-title">📌 实用信息</div><ul>'
            + '<li>电话：报警 110 / 急救 120 / 火警 119</li>'
            + '<li>电话：政务服务热线 12345</li>'
            + '<li>提示：出行前确认天气与景区开放时间</li>'
            + '<li>行李：身份证、手机充电宝、常用药品、防晒用品</li>'
            + '</ul></div>';
    }
    html += '</div>';
    return html;
}

//弹窗遮罩点击 / Esc 关闭
document.addEventListener('click', function (e) {
    const mask = document.getElementById('modalMask');
    if (mask && e.target === mask) mask.classList.remove('show');
});
document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') document.getElementById('modalMask').classList.remove('show');
});

/* ============================================================ 打印 / 导出（最终旅行计划面板右上角按钮） PDF：window.print()，由 print.css 控制 A4 版式 Word：Blob 生成 HTML 格式 .doc 文件（Word 可直接打开） ============================================================ */

/** 展开 / 收起导出菜单 */
function toggleExportMenu(ev) {
    if (ev && ev.stopPropagation) ev.stopPropagation();
    const menu = document.getElementById('exportMenu');
    const isOpen = menu.style.display === 'block';
    menu.style.display = isOpen ? 'none' : 'block';
    if (!isOpen) {
        //点击页面其他位置时关闭菜单
        setTimeout(() => {
            document.addEventListener('click', closeExportMenu, { once: true });
        }, 0);
    }
}

/** 关闭导出菜单 */
function closeExportMenu() {
    const menu = document.getElementById('exportMenu');
    if (menu) menu.style.display = 'none';
}

/** 判断当前是否有可导出的计划内容 */
function hasExportablePlan() {
    const body = document.getElementById('resultBody');
    const txt = (body.textContent || '').trim();
    if (!txt || txt.includes('正在生成') || txt.includes('等待生成') || txt.includes('（空响应）')) {
        alert('暂无可导出的旅行计划，请先生成计划或查看一条已保存行程');
        return false;
    }
    return true;
}

/** 导出 PDF：生成打印视图(概览+详情)，调用浏览器打印对话框 */
function exportPDF(ev) {
    if (ev && ev.stopPropagation) ev.stopPropagation();
    closeExportMenu();
    if (!hasExportablePlan()) return;
    buildPrintView();   // 构造打印视图：顶部每天概览 + 下面每天详情
    window.print();
}

/** 构造打印视图内容：顶部=每天概览(与生成一致)，下面=每天详情(弹窗内容) */
function openTripCalendar() {
    const plan = currentPlanData || {};
    const days = Math.min(30, Math.max(1, Number(plan.days) || 1));
    const start = parsePlanDate(plan.date) ? new Date(parsePlanDate(plan.date)) : new Date();
    start.setHours(0, 0, 0, 0);
    calRange = { start: start, days: days };
    //视图钳制：行程起始日早于本月 -> 从本月开始看（范围外日期已置灰不可点）
    const now = new Date();
    const view = start < new Date(now.getFullYear(), now.getMonth(), 1)
        ? { y: now.getFullYear(), m: now.getMonth() }
        : { y: start.getFullYear(), m: start.getMonth() };
    calView = view;
    ensureCalModal();
    document.getElementById('calModal').style.display = 'flex';
    renderCalGrid();
}

function closeTripCalendar() {
    const m = document.getElementById('calModal');
    if (m) m.style.display = 'none';
    if (!calRange || !currentPlanData) return;
    //修复：仅当用户点了新出发日才算改期
    const oldDate = currentPlanData.date || '';
    const oldStart = parsePlanDate(oldDate);
    if (oldStart) {
        if (localDateString(calRange.start) === oldStart) return;
    } else {
        if (fmtTripDateRange(calRange.start, calRange.days) === oldDate) return;
    }
    const newDate = fmtTripDateRange(calRange.start, calRange.days);
    currentPlanData.date = newDate;
    applyTripDateChange(newDate);
}

function ensureCalModal() {
    if (document.getElementById('calModal')) return;
    const wrap = document.createElement('div');
    wrap.id = 'calModal';
    wrap.style.cssText = 'display:none;position:fixed;inset:0;background:rgba(30,46,41,.45);z-index:9999;align-items:center;justify-content:center';
    wrap.onclick = e => { if (e.target === wrap) closeTripCalendar(); };
    wrap.innerHTML =
        '<div style="background:#fff;border-radius:18px;padding:22px 24px;width:min(92vw,380px);box-shadow:0 18px 60px rgba(0,0,0,.18);font-family:inherit">' +
        '<div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:14px">' +
        '<button id="calPrev" style="border:none;background:#f2f8f6;border-radius:9px;width:32px;height:32px;cursor:pointer;font-size:15px;color:#1FAE8F">‹</button>' +
        '<b id="calTitle" style="font-size:15.5px;color:#1E2E29;letter-spacing:.5px"></b>' +
        '<button id="calNext" style="border:none;background:#f2f8f6;border-radius:9px;width:32px;height:32px;cursor:pointer;font-size:15px;color:#1FAE8F">›</button>' +
        '</div>' +
        '<div id="calWeekRow" style="display:grid;grid-template-columns:repeat(7,1fr);text-align:center;font-size:11.5px;color:#74857F;margin-bottom:4px"></div>' +
        '<div id="calGrid" style="display:grid;grid-template-columns:repeat(7,1fr);gap:2px"></div>' +
        '<div style="margin-top:12px;display:flex;align-items:center;gap:14px;font-size:11.5px;color:#74857F">' +
        '<span><i style="display:inline-block;width:10px;height:10px;border-radius:3px;background:#1FAE8F;margin-right:4px"></i>行程日期</span>' +
        '<span style="margin-left:auto"><button onclick="closeTripCalendar()" style="border:none;background:#f2f8f6;border-radius:8px;padding:6px 16px;cursor:pointer;color:#74857F;font-size:12.5px">关闭</button></span>' +
        '</div></div>';
    document.body.appendChild(wrap);
    document.getElementById('calPrev').onclick = () => calNav(-1);
    document.getElementById('calNext').onclick = () => calNav(1);
}

function renderCalGrid() {
    const { y, m } = calView;
    const now = new Date();
    const minD = new Date(now.getFullYear(), now.getMonth(), 1);   // 本月1日
    const maxD = new Date(now.getFullYear() + 2, now.getMonth(), 1); // 未来两年
    document.getElementById('calTitle').textContent = y + ' 年 ' + (m + 1) + ' 月';
    document.getElementById('calWeekRow').innerHTML = ['日','一','二','三','四','五','六'].map(w => '<span>' + w + '</span>').join('');

    const grid = document.getElementById('calGrid');
    grid.innerHTML = '';
    const first = new Date(y, m, 1);
    const daysInMonth = new Date(y, m + 1, 0).getDate();
    const startPad = first.getDay(); // 周几开头

    const rangeStart = calRange.start;
    const rangeEnd = new Date(rangeStart); rangeEnd.setDate(rangeEnd.getDate() + calRange.days - 1);
    const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    const inRange = d => d >= rangeStart && d <= rangeEnd;

    //视图越界（早于本月1日 / 晚于两年后）禁止翻到
    document.getElementById('calPrev').disabled = new Date(y, m, 1) <= minD;
    document.getElementById('calNext').disabled = new Date(y, m, 1) >= maxD;

    for (let i = 0; i < startPad; i++) grid.appendChild(document.createElement('span'));
    for (let d = 1; d <= daysInMonth; d++) {
        const cur = new Date(y, m, d);
        const cell = document.createElement('span');
        cell.textContent = d;
        cell.style.cssText = 'text-align:center;padding:7px 0;font-size:13px;border-radius:9px;cursor:pointer;transition:.12s;color:#1E2E29';
        const outOfBound = cur < minD || cur > new Date(maxD.getFullYear(), maxD.getMonth() + 1, 0);
        if (outOfBound) {
            cell.style.color = '#c4d2cc';
            cell.style.cursor = 'default';
        } else if (inRange(cur)) {
            const isStart = sameDay(cur, rangeStart);
            cell.style.background = '#1FAE8F';
            cell.style.color = '#fff';
            cell.style.fontWeight = '700';
            if (isStart) cell.style.boxShadow = '0 0 0 2px rgba(31,174,143,.28)';
            cell.title = isStart ? '行程开始' : '行程中';
        }
        if (!outOfBound) {
            cell.onmouseenter = () => { if (!inRange(cur)) cell.style.background = '#e8f8f5'; };
            cell.onmouseleave = () => { if (!inRange(cur)) cell.style.background = ''; };
            cell.onclick = () => {
                //点任意日期 -> 从该日起按行程天数重新高亮
                calRange = { start: cur, days: calRange.days };
                renderCalGrid();
            };
        }
        grid.appendChild(cell);
    }
}

function calNav(delta) {
    let m = calView.m + delta;
    if (m < 0) { m = 11; calView.y--; }
    if (m > 11) { m = 0; calView.y++; }
    calView.m = m;
    renderCalGrid();
}

function parsePlanDate(value) {
    if (!value) return null;
    const iso = String(value).match(/(\d{4})-(\d{1,2})-(\d{1,2})/);
    if (iso) return localDateString(new Date(Number(iso[1]), Number(iso[2]) - 1, Number(iso[3])));
    const cn = String(value).match(/(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日/);
    if (cn) return localDateString(new Date(Number(cn[1]), Number(cn[2]) - 1, Number(cn[3])));
    //"8月27日-29日"区间格式：无年份，取区间首日
    const range = String(value).match(/(\d{1,2})月(\d{1,2})日-(\d{1,2})月(\d{1,2})日/);
    if (range) {
        const now = new Date();
        return localDateString(new Date(now.getFullYear(), Number(range[1]) - 1, Number(range[2])));
    }
    //"8月27日"单日格式
    const single = String(value).match(/(\d{1,2})月(\d{1,2})日/);
    if (single) {
        const now = new Date();
        return localDateString(new Date(now.getFullYear(), Number(single[1]) - 1, Number(single[2])));
    }
    return null;
}

function parsePlanEndDate(value) {
    if (!value) return null;
    const range = String(value).match(/(\d{1,2})月(\d{1,2})日-(?:(\d{1,2})月)?(\d{1,2})日/);
    if (!range) return null;
    const start = parsePlanDate(value);
    if (!start) return null;
    const startDt = new Date(start);
    //"27日-29日"：结束月缺省=开始月
    const endMonth = range[3] ? Number(range[3]) - 1 : startDt.getMonth();
    const endDt = new Date(startDt.getFullYear(), endMonth, Number(range[4]));
    if (endDt < startDt) endDt.setFullYear(endDt.getFullYear() + 1); // 跨年区间（12月-次年1月）
    return localDateString(endDt);
}

function fmtTripDateRange(start, days) {
    const end = new Date(start); end.setDate(end.getDate() + Math.max(1, days) - 1);
    if (days <= 1 || start.getMonth() !== end.getMonth()) {
        return (start.getMonth() + 1) + '月' + start.getDate() + '日-' + (end.getMonth() + 1) + '月' + end.getDate() + '日';
    }
    return (start.getMonth() + 1) + '月' + start.getDate() + '日-' + end.getDate() + '日';
}

function localDateString(date) {
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return date.getFullYear() + '-' + month + '-' + day;
}

/** 清洗天气摘要：剔除 LLM 偶尔输出的 markdown 表格残留（如 "| 白天/夜间温度 | 风力 |"） */
function cleanWeatherText(w) {
    if (!w) return w;
    const s = String(w).trim();
    if (/^\|.+\|$/.test(s)) return ''; // 整行管道符表格结构 -> 无有效天气内容
    return s;
}

function applyTripDateChange(newDate) {
    //1) 立即刷新主界面日期徽章 + 打印视图（同一数据源）
    const chip = document.querySelector('.plan-meta .date-chip'); // 修复：定位可见徽章(#printView 里也有同名副本，getElementById 会拿错)
    if (chip) chip.textContent = '📅 ' + newDate;
    if (typeof buildPrintView === 'function') buildPrintView(); // 打印/PDF 导出用的隐藏视图
    lastResultMarkdown = (stripPlanJson(lastResultMarkdown) + '\n\n出行的日期是 ' + newDate + '。').trim(); // markdown 摘要同步
    //2) 持久化
    if (!viewingPlanId) return;
    fetch('/api/trips/' + viewingPlanId, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(currentPlanData)
    }).catch(() => { /* 静默：网络异常时下次改期或保存行程会再次落库 */ });
}

let calView = null;          // 当前日历视图 {y, m}（m: 0-11）
let calRange = null;         // 高亮区间 {start: Date, days: n}
let viewingPlanId = null;    // 当前查看的历史行程ID（日历改期后保存用）
let lastResultJson = '';     // 最近一次结构化结果 JSON（供再保存）

async function loadTravelInsight(plan) {
    const box = document.getElementById('travelInsight');
    if (!box || !plan || !plan.destination) return;

    const startDate = parsePlanDate(plan.date) || localDateString(new Date());
    const days = Math.min(30, Math.max(1, Number(plan.days) || 1));
    const params = new URLSearchParams({
        city: plan.destination,
        startDate: startDate,
        days: String(days)
    });

    try {
        const res = await fetch('/api/insights?' + params.toString(), { headers: { 'Accept': 'application/json' } });
        const data = await res.json();
        if (!res.ok || !data || !data.available || !box.isConnected) {
            box.remove();
            return;
        }
        renderTravelInsight(box, plan.destination, data);
    } catch (e) {
        if (box.isConnected) box.remove();
    }
}

function renderTravelInsight(box, city, data) {
    /* 动态定价（酒店/餐饮/门票价格块）已按需求移除，只保留客流指数 */
    const forecast = Array.isArray(data.forecast) ? data.forecast : [];
    const metrics = [];

    if (forecast.length > 0) {
        const values = forecast.map(d => Number(d.index)).filter(Number.isFinite);
        if (values.length > 0) {
            const avg = values.reduce((sum, v) => sum + v, 0) / values.length;
            metrics.push({
                label: '客流指数',
                value: avg.toFixed(2),
                note: crowdLevel(avg)
            });
        }
    }

    if (metrics.length === 0) {
        box.remove();
        return;
    }

    const head = document.createElement('div');
    head.className = 'ti-head';
    const title = document.createElement('div');
    title.className = 'ti-title';
    title.textContent = '目的地参考 · ' + city;
    const badge = document.createElement('span');
    badge.className = 'ti-badge';
    badge.textContent = '城市级';
    head.append(title, badge);

    const grid = document.createElement('div');
    grid.className = 'ti-grid';
    metrics.forEach(metric => {
        const item = document.createElement('div');
        item.className = 'ti-item';
        const name = document.createElement('span');
        name.className = 'ti-label';
        name.textContent = metric.label;
        const value = document.createElement('b');
        value.className = 'ti-value';
        value.textContent = metric.value;
        item.append(name, value);
        if (metric.note) {
            const note = document.createElement('span');
            note.className = 'ti-note';
            note.textContent = metric.note;
            item.appendChild(note);
        }
        grid.appendChild(item);
    });

    const source = document.createElement('div');
    source.className = 'ti-source';
    source.textContent = '参考数据，非实时报价';
    box.append(head, grid, source);
}

/* ============ 预测弹窗：行程头部「 价格-客流预测」按钮 ============ */

/** 打开预测弹窗：展示该城市价格预测 + 未来客流趋势（原顶部自动浮出的参考卡收纳为此弹窗） */
let lastInsightData = null;   //★最近一次 /api/insights 的结果（价格块取回后用它整体重绘）
/** 当前弹窗是不是"我"这个（两个弹窗共用同一个模态框，光看 show 会串内容） */
function isModalOf(keyword) {
    try {
        const mask = document.getElementById('modalMask');
        if (!mask || !mask.classList.contains('show')) return false;
        const t = String((document.getElementById('modalTitle') || {}).textContent || '');
        const sub = String((document.getElementById('modalSub') || {}).textContent || '');
        return (t + ' ' + sub).indexOf(keyword) >= 0;
    } catch (e) { return false; }
}

function openInsightModal() {
    const plan = currentPlanData;
    if (!plan || !plan.destination) return;
    const city = String(plan.destination).trim();
    document.getElementById('modalEm').textContent = '📈';
    document.getElementById('modalTitle').textContent = city + ' · 价格与客流预测';
    document.getElementById('modalSub').textContent = '城市级预测 · 仅供参考';
    lastInsightData = null;
    paintInsightModal();       //★先画"价格预估加载中"；价格/客流任一先到都会整体重画
    document.getElementById('modalMask').classList.add('show');

    //★价格预估（三部分：单次/每天/全程人均）—— 独立接口，知识库优先、外部 LLM 兜底。
    //  取到后只刷新正文，不重开弹窗（避免递归）。
    loadTripPrice(plan, () => {
        if (!isModalOf('价格与客流预测')) return;   //★弹窗已被换成别的（如区域天气）就别乱刷，避免两个弹窗串内容
        paintInsightModal();       //★价格晚到也要把正文整体重画（旧文案不会残留）
    });

    //客流预测固定查「未来 7 天」（数据管道窗口=今天+1 起 7 天），与行程日期无关
    const startDate = localDateString(new Date(Date.now() + 86400e3));
    const params = new URLSearchParams({ city: city, startDate: startDate, days: '7' });
    fetch('/api/insights?' + params.toString(), { headers: { 'Accept': 'application/json' } })
        .then(r => r.json())
        .then(data => {
            const body = document.getElementById('modalBody');
            if (!data || !data.available) {
                //★用户口径：客流/基准价缺失就不显示这段话（避免和价格块重复、误导）
                if (isModalOf('价格与客流预测')) paintInsightModal();
                return;
            }
            lastInsightData = data;
            if (isModalOf('价格与客流预测')) paintInsightModal();
        })
        .catch(() => {
            const body = document.getElementById('modalBody');
            body.innerHTML = '<div class="state-box" style="padding:34px">预测数据加载失败，请稍后重试</div>';
        });
}

/* ============================================================
   ★行程附加信息（区域天气 / 地点价格）—— 独立于核心流程
   用户口径：行程【生成完整之后】再取数；依据已生成的行程；同一份行程不重复获取。
   实现：后端 /api/trip-extras 逐个查高德（进程内缓存），前端只负责抽取入参 + 渲染。
   ============================================================ */
let tripExtras = null;      // { weather:[...], prices:[...] }
let tripExtrasKey = '';     // 已取数的行程指纹（不重复获取）
let tripExtrasBusy = false;

/** 从已生成的行程里抽取入参：城市清单（取交通条目的 from/to）+ 关键地点（住宿/餐饮/景点） */
function extrasInputOf(plan) {
    const cities = [];
    //★三类分开收集，最后按 住宿 → 餐饮 → 景点 拼接：
    //  酒店的评分/电话/地址是用户要点的，必须**优先**进名单。
    //  （原来按当天顺序统一收集再截断 → 8 天行程里靠后的酒店被 cut 掉 → 点开只显示"暂无"。）
    const hotels = [], foods = [], sights = [], seen = {};
    const addTo = (list, nm, city, category) => {
        const s = stripParens(String(nm || '').trim());
        if (!s || s.length > 24 || seen[s]) return;
        seen[s] = 1;
        list.push({ name: s, city: city || '', category: category || '' });
    };
    try {
        const days = (plan.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
        days.forEach(day => {
            //当天所在城市：优先用交通条目的 to（如「精河县」），没有就用 from
            let dayCity = '';
            (day.items || []).forEach(it => {
                const to = String(it.to || '').trim(), from = String(it.from || '').trim();
                if (!dayCity && to) dayCity = to;
                [from, to].forEach(c => { if (c && c.length <= 12 && cities.indexOf(c) < 0) cities.push(c); });
            });
            (day.items || []).forEach(it => {
                //① 住宿条目里的酒店名（详情弹窗点的就是它）
                if (isHotelItem(it)) {
                    const hn = hotelNameOf(it);
                    if (hn) { addTo(hotels, hn, dayCity, '住宿'); return; }
                    //★住宿类但找不到具体酒店名（place 只有城市名）→ 不要把"奎屯市"这种城市当酒店塞进名单
                    if (/住宿|酒店|宾馆|民宿/.test(String(it.category || ''))) return;
                }
                //② 其它条目按类别分流
                const nm = String(it.place || it.name || '').trim();
                const cat = String(it.category || '').trim();
                if (!nm || nm.length > 24) return;
                if (/住宿|酒店|宾馆|民宿/.test(cat)) addTo(hotels, nm, dayCity, cat);
                else if (/餐饮|美食|菜/.test(cat)) addTo(foods, nm, dayCity, cat);
                else if (cat && !/景点|门票|景区/.test(cat)) return; // 交通条目不查
                else addTo(sights, nm, dayCity, cat);
            });
        });
        //★「住宿与餐饮推荐」(stay) 与「实用信息」(tips) 区块里的酒店也要取数
        (plan.sections || []).filter(s => s.type === 'stay' || s.type === 'tips').forEach(sec => {
            (sec.items || []).forEach(it => {
                const nm = hotelNameOf(it) || String(it.name || '').trim();
                if (!nm) return;
                const cat = String(it.category || it.kind || '').trim();
                //★必须带城市（it.place 就是当天城市，如"博乐市"/"那拉提镇"）：
                //  不带城市时高德会在全国范围取第一条同名结果 → "汉庭酒店"被解析到河北廊坊、
                //  "博雅丽景酒店"被解析到北京上地（实测踩坑）。
                const cityHint = stripParens(String(it.place || '').trim());
                if (isHotelItem(it) || /住宿|酒店|宾馆|民宿/.test(cat)) addTo(hotels, nm, cityHint, cat || '住宿');
                else if (/餐饮|美食/.test(cat)) addTo(foods, nm, cityHint, cat);
            });
        });
    } catch (e) { /* 抽取失败就不取数，行程不受影响 */ }
    return { cities: cities.slice(0, 12), places: hotels.concat(foods, sights).slice(0, 48) };
}

/** 行程生成完整后调用：异步取数并缓存（同一份行程只取一次） */
function loadTripExtras(plan, done) {
    plan = plan || currentPlanData; // 不传就按当前行程
    try {
        if (!plan || !plan.sections) { if (done) done(false); return; }
        const key = planFingerprint();
        if (tripExtras && tripExtrasKey === key) { if (done) done(true); return; } // 已有数据：不重复查
        if (tripExtrasBusy) { setTimeout(() => loadTripExtras(plan, done), 800); return; }
        const input = extrasInputOf(plan);
        if (!input.cities.length && !input.places.length) { if (done) done(false); return; }
        tripExtrasBusy = true;
        tripExtrasKey = key;
        fetch('/api/trip-extras', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json;charset=UTF-8' },
            body: JSON.stringify(input)
        })
            .then(r => r.json())
            .then(d => { if (d && d.success) tripExtras = d; })
            .catch(() => { /* 取数失败：卡片显示"暂无"，行程不受影响 */ })
            .finally(() => { tripExtrasBusy = false; if (done) done(!!tripExtras); });
    } catch (e) { tripExtrasBusy = false; if (done) done(false); }
}

/** 高德参考价区块（放进行程详情/价格弹窗；没有数据就返回空串，不显示该块） */
function extrasPriceBlockHtml() {
    const ps = (tripExtras && tripExtras.prices) || [];
    if (!ps.length) return '';
    let html = '<div style="font-weight:700;font-size:14px;margin:2px 0 8px;color:var(--ink)">🏷 高德参考价'
        + '<span style="font-weight:400;font-size:11.5px;color:var(--muted)">（来自高德 POI，人均/门票，仅供参考）</span></div>'
        + '<div class="wx-table"><table><tbody>';
    ps.forEach(p => {
        const price = p.cost ? (Number(p.cost) > 0 ? '约 ' + Math.round(Number(p.cost)) + ' 元' : '') : '';
        const rating = p.rating ? '评分 ' + p.rating : '';
        const extra = [price, rating].filter(Boolean).join(' · ') || '暂无价格';
        html += '<tr><td>' + escapeHtml(p.name) + '</td><td>' + escapeHtml(extra) + '</td></tr>';
    });
    html += '</tbody></table></div><div style="height:14px"></div>';
    return html;
}

/** 区域天气弹窗：行程涉及的每个城市/县 × 未来三天（城市级，来自高德） */
function openRegionWeather() {
    loadTripExtras(); //★按需取数：打开天气弹窗时才批量查城市（页面渲染时不再查）
    const body = document.getElementById('modalBody');
    document.getElementById('modalEm').textContent = '🌤';
    document.getElementById('modalTitle').textContent = '区域天气 · 行程涉及城市';
    document.getElementById('modalSub').textContent = '城市级预报 · 数据来自高德 · 仅供参考';
    document.getElementById('modalMask').classList.add('show');

    if (!tripExtras) {
        body.innerHTML = '<div class="state-box" style="padding:34px"><div class="big">⏳</div>天气数据加载中…<br>'
            + '<span style="font-size:12px;color:var(--muted)">首次取数需要几秒（高德限速 3 次/秒）</span></div>';
        //加载完成后自动刷新一次（用户不用手动再点）
        let tries = 0;
        const t = setInterval(() => {
            tries++;
            if (tripExtras || tries > 12) {
                clearInterval(t);
                if (tripExtras && isModalOf('区域天气')) openRegionWeather();   //★只刷自己那个弹窗，别把价格弹窗顶掉
            }
        }, 1200);
        return;
    }
    const ws = (tripExtras && tripExtras.weather) || [];
    if (!ws.length) {
        body.innerHTML = '<div class="state-box" style="padding:34px"><div class="big">📭</div>暂无天气数据'
            + '<br><span style="font-size:12px;color:var(--muted)">高德只提供今天起 4 天的预报；行程日期在窗口外时查不到</span></div>';
        return;
    }
    let html = '<div class="wx-wrap">';
    ws.forEach(w => {
        html += '<div class="wx-city"><div class="wx-city-name">' + escapeHtml(w.city || '')
            + (w.province && w.province !== w.city ? ' · ' + escapeHtml(w.province) : '') + '</div><div class="wx-days">';
        (w.days || []).forEach(d => {
            html += '<div class="wx-day">'
                + '<div class="wx-date">' + escapeHtml(String(d.date || '').slice(5)) + ' ' + escapeHtml(d.week || '') + '</div>'
                + '<div class="wx-txt">' + escapeHtml(d.dayWeather || '') + '</div>'
                + '<div class="wx-temp">' + escapeHtml(d.nightTemp || '') + '~' + escapeHtml(d.dayTemp || '') + '℃</div>'
                + '</div>';
        });
        html += '</div></div>';
    });
    html += '</div>';
    body.innerHTML = html;
}

/**
 * ★去重（用户口径）：同一天内同一个地点只保留【首次出现】，用于「轨迹时间轴」和「概览卡片」。
 * 详情弹窗（openDayModal）不用它 —— 那边允许出现重复。
 */
function dedupeByPlace(items) {
    const seen = new Set();
    const out = [];
    (items || []).forEach(it => {
        if (!it) return;
        const raw = stripParens(it.place || it.activity || '');
        const key = String(raw).replace(/\s+/g, '');
        if (!key || isNoise(key) || seen.has(key)) return;
        seen.add(key);
        out.push(it);
    });
    return out;
}

/* ============================================================
   ★酒店/餐饮卡片（用户口径）：点「住宿与餐饮推荐」里的住宿行、以及当天详情弹窗里的住宿条目
   → 弹出高德给的评分/电话/地址（不跳转、不开新页）。
   数据来自 /api/trip-extras 已经取到并缓存的 prices（后端进程内缓存 → 同一份行程只取一次）。
   ============================================================ */
let poiRows = {};   // 生成期登记的点击项 id -> {name, city, category}
let poiSeq = 0;

/** 登记一个可点击的 POI，返回它的 id（避免把中文名写进 onclick 属性里） */
function registerPoi(name, city, category) {
    const nm = String(name || '').trim();
    if (!nm) return '';
    const id = 'poi' + (++poiSeq);
    poiRows[id] = { name: nm, city: String(city || '').trim(), category: String(category || '').trim() };
    return id;
}

function openPoiCardById(id) {
    const r = poiRows[id];
    if (r) openPoiCard(r.name, r.city, r.category);
}

/** 从已取到的 extras 里按名字找高德信息（评分/电话/地址） */
function poiInfoOf(name) {
    try {
        const key = stripParens(String(name || '')).replace(/\s+/g, '');
        if (!key || !tripExtras) return null;
        const list = tripExtras.prices || [];
        for (const p of list) {
            if (stripParens(String(p.name || '')).replace(/\s+/g, '') === key) return p;
        }
        //宽松匹配：门店名带后缀时（"汉庭酒店" vs "汉庭酒店(奎屯店)"）也能命中
        for (const p of list) {
            const pn = stripParens(String(p.name || '')).replace(/\s+/g, '');
            if (pn && (pn.indexOf(key) >= 0 || key.indexOf(pn) >= 0)) return p;
        }
    } catch (e) { /* 查不到就按"暂无"展示 */ }
    return null;
}

/** 点酒店 → 弹卡片（只展示高德信息，不跳转）
 *  ★命中缓存就直接画；没命中就【只查这一个点】（约 1 秒）—— 不再等页面上那一整批 37 个点的结果
 */
function openPoiCard(name, city, category, retried) {
    if (!name) return;
    const info = poiInfoOf(name);
    document.getElementById('modalEm').textContent = '🏨';
    document.getElementById('modalTitle').textContent = name;
    document.getElementById('modalSub').textContent = [city, category].filter(Boolean).join(' · ') || '高德 POI 信息';
    const body = document.getElementById('modalBody');
    document.getElementById('modalMask').classList.add('show');
    if (!info) {
        body.innerHTML = '<div class="state-box" style="padding:30px"><div class="big">⏳</div>正在查询「' + escapeHtml(name) + '」…<br><span style="font-size:12px;color:var(--muted)">单个地点约 1 秒</span></div>';
        if (!retried) {
            //★单点查询：只把这一个地点发给后端（后端按地点进程内缓存，重复点不会重复查）
            fetchOnePoi(name, city, category, () => openPoiCard(name, city, category, true));
        } else {
            body.innerHTML = '<div class="state-box" style="padding:30px"><div class="big">📭</div>暂无「' + escapeHtml(name) + '」的高德信息<br><span style="font-size:12px;color:var(--muted)">该地点未在高德检索到</span></div>';
        }
        return;
    }
    const cost = (info.cost && Number(info.cost) > 0) ? ('约 ' + Math.round(Number(info.cost)) + ' 元' + (/餐饮|美食|菜/.test(String(info.category || '')) ? '（人均）' : '')) : '暂无';
    const rows = [
        ['⭐ 评分', info.rating || '暂无'],
        ['📞 电话', info.tel || '暂无'],
        ['📍 地址', info.address || '暂无'],
        ['💰 人均/门票', cost]
    ];
    body.innerHTML = '<div class="poi-card"><table><tbody>'
        + rows.map(r => '<tr><td class="pk">' + r[0] + '</td><td>' + escapeHtml(String(r[1])) + '</td></tr>').join('')
        + '</tbody></table>'
        + (info.matched ? '<div class="poi-hit">高德命中：' + escapeHtml(String(info.matched)) + '</div>' : '')
        + '<div class="poi-src">数据来自高德 POI（参考信息，非实时房价/票价）</div></div>';
}

/** 单点查询高德信息，结果并入缓存后回调（失败也回调，由调用方显示"暂无"） */
function fetchOnePoi(name, city, category, done) {
    let finished = false;
    const fire = () => { if (!finished) { finished = true; if (done) done(); } };
    try {
        fetch('/api/trip-extras', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json;charset=UTF-8' },
            body: JSON.stringify({ cities: [], places: [{ name: name, city: city || '', category: category || '住宿' }] })
        })
            .then(r => r.json())
            .then(d => {
                const list = (d && d.prices) || [];
                if (list.length) {
                    if (!tripExtras) tripExtras = { prices: [], weather: [] };
                    if (!tripExtras.prices) tripExtras.prices = [];
                    if (!tripExtras.prices.some(p => p.name === list[0].name)) tripExtras.prices.push(list[0]);
                }
                fire();
            })
            .catch(fire);
    } catch (e) { fire(); }
    setTimeout(fire, 12000); // 兜底：最长等 12 秒，避免一直转圈
}

/** 判断一条住宿/餐饮条目是不是"酒店类"（只有酒店行可点，用户口径） */
function isHotelItem(it) {
    const s = [it && it.category, it && it.name, it && it.text, it && it.kind, it && it.activity, it && it.place]
        .filter(Boolean).join(' ');
    return /住宿|酒店|宾馆|民宿|客栈|客房|度假村/.test(s);
}

/**
 * 从条目文本里挑出"酒店名"：
 *  - 住宿区块(timeline/stay)：酒店名在 activity（"入住奎屯 · 汉庭酒店"）或 name
 *  - ★当天住宿条目：实测酒店名写在【note】里（activity="入住奎屯"、place="奎屯市"、
 *    note="汉庭酒店·驿马巷美食街酒店／西岸酒店"）—— 不认 note 就会取不到名字，
 *    导致详情弹窗绑不上点击、取数名单把城市名当成酒店。
 *  - 实用信息区块(tips)：酒店名在 text 里
 */
function hotelNamesOf(it) {
    const out = [];
    const push = (s) => String(s == null ? '' : s).split(/[·、,，/／；;]/)
        .forEach(x => {
            const t = stripParens(x).trim();
            //★不能带句读（"…酒店；晚餐于酒店或镇上" 这种半句话不是酒店名）
            if (!t || t.length > 24 || /[，,。；;！!？?]/.test(t)) return;
            if (!/酒店|宾馆|民宿|客栈|客房|度假村/.test(t)) return;
            if (out.indexOf(t) < 0) out.push(t);
        });
    push(it && it.name);
    push(it && it.activity);
    push(it && it.place);
    push(it && it.text);
    push(it && it.note);
    return out;
}

/** 取第一个酒店名（兼容旧调用） */
function hotelNameOf(it) {
    const a = hotelNamesOf(it);
    return a.length ? a[0] : '';
}

/** 判断一条条目是不是"餐饮类"（用户口径：餐饮要和酒店一样可点） */
function isFoodItem(it) {
    const s = [it && it.category, it && it.kind, it && it.activity, it && it.text]
        .filter(Boolean).join(' ');
    return /餐饮|餐厅|美食|菜馆|酒楼|饭馆|饭店|小吃/.test(s) || /早餐|午餐|晚餐|用餐|就餐/.test(s);
}

/** 从餐饮条目里挑出店名（区块条目用 name；当天条目的店名写在 activity 里，如"晚餐 · 奎城味到和田烧烤"） */
function restaurantNamesOf(it) {
    const out = [];
    //★只认"店名"，不把备注/菜品名/地点词也做成可点
    const bad = /景区|镇$|市$|县$|区$|广场|美食街|附近|或|湖$|山$|公园$|古城$|峡谷$|草原$|街$|路$|巷$|服务区|大道$/;
    const add = (s) => {
        const t = stripParens(String(s == null ? '' : s)).trim();
        if (!t || t.length > 24 || bad.test(t)) return;
        if (/^(早餐|午餐|晚餐|用餐|就餐|午饭|晚饭|夜宵|酒店|民宿|餐厅)$/.test(t)) return;
        if (!/[\u4e00-\u9fa5A-Za-z]/.test(t)) return;
        if (out.indexOf(t) < 0) out.push(t);
    };
    //① 区块条目：name 就是店名（与页面上显示的文字完全一致）
    if (it && it.name) add(it.name);
    //★真实店名常常就写在 place 字段（实测 place="苍狼白鹿特色美食"，name 为空）→ 必须收进来
    //  但 place 也可能是"赛里木湖""精河县"这种地名，靠 bad 规则挡掉
    if (it && it.place) add(it.place);
    if (out.length) return out;
    //② 当天条目：店名写在 activity / note 里（实测 activity="晚餐"，店名在 note 里）
    //   ★按"或"分成备选段，每段各取第一个候选项 —— 两个备选餐厅都要能点（用户口径）
    //   （只取第一个是为了滤掉"烤全羊""庭院新疆菜歌舞餐厅"这类菜品/描述）
    const src2 = String((it && it.activity) || '') + ' · ' + String((it && it.note) || '');
    src2.split(/[或]|或者/).forEach(seg => {
        const parts = String(seg).split(/[·、,，/／；;]/);
        for (let i = 0; i < parts.length; i++) {
            const t = stripParens(parts[i]).trim();
            if (!t) continue;
            if (/^(早餐|午餐|晚餐|用餐|就餐|已经|可选|备选)$/.test(t)) continue;
            add(t);
            break;
        }
    });
    return out;
}

/** 取第一个餐饮店名 */
function foodNameOf(it) {
    const a = restaurantNamesOf(it);
    return a.length ? a[0] : '';
}

/**
 * ★把一个条目里出现的【每个酒店名】都换成带虚线的可点元素
 *   （用户口径：一条住宿常有 2 个备选酒店，都要能点；酒店名要像景点名一样有虚线提示）。
 *   返回 { html, firstId }
 */
/**
 * 取"像城市"的名字给高德查询用（带城市查才不会张冠李戴）。
 * 实测 item.place 有时是城市（"奎屯市"），有时却是店名（"金美大酒店"）——
 * 直接拿它当城市会查不到。这里从 place/note/activity/text 里挑第一个地名片段，优先 市/县/州。
 */
function cityOfItem(it) {
    const raw = [it && it.place, it && it.note, it && it.activity, it && it.text]
        .filter(Boolean).map(x => String(x)).join(' ');
    // 必须从词边界开始，避免把"金美大酒店 精河县"整段吃进去
    const B = '(?:^|[\\s·，,、；;/／(（])';
    const m1 = raw.match(new RegExp(B + '([\\u4e00-\\u9fa5]{2,6}?(?:市|县|州))'));
    if (m1) return m1[1];
    const m2 = raw.match(new RegExp(B + '([\\u4e00-\\u9fa5]{2,6}?(?:区|镇))'));
    return m2 ? m2[1] : '';
}

/**
 * 概览卡片 / 轨迹时间轴里的一个地点怎么渲染：
 *   酒店、餐饮 → 弹高德卡片（不跳转，用户口径）；其余 → 保持原来的高德链接（一字不改）
 * 返回 { html, id }
 */
function poiOrLinkHtml(it, text) {
    const t = String(text == null ? '' : text);
    try {
        if (it && (isHotelItem(it) || isFoodItem(it))) {
            const w = wrapHotelNames(escapeHtml(t), it);
            if (w.firstId) return { html: w.html, id: w.firstId };
        }
    } catch (e) { /* 出问题就退回原来的链接渲染，绝不让页面坏掉 */ }
    return { html: amapLink(t, cityOfItem(it)), id: '' };
}

function wrapHotelNames(html, item) {
    let out = String(html == null ? '' : html), firstId = '';
    try {
        const city = cityOfItem(item);   //★不能直接拿 place 当城市（实测 place 有时是店名"金美大酒店"）
        //★酒店 + 餐饮都做成可点（用户口径：餐饮与酒店同款效果：虚线 + 点开卡片）
        const targets = [];
        if (isHotelItem(item)) hotelNamesOf(item).forEach(n => targets.push({ n: n, cat: '住宿' }));
        if (isFoodItem(item)) restaurantNamesOf(item).forEach(n => targets.push({ n: n, cat: '餐饮' }));
        targets.forEach(t => {
            const pid = registerPoi(t.n, city, t.cat);
            if (!pid) return;
            if (!firstId) firstId = pid;
            const eh = escapeHtml(t.n);
            if (eh && out.indexOf(eh) >= 0) {
                //★用户口径：不再弹卡片 → 店名直接跳高德搜索页（效果与点击轨迹地点完全一样）
                out = out.replace(eh, amapLink(t.n, cityOfItem(item)));   //参数名是 item（不是 it）
            }
        });
    } catch (e) { /* 替换失败就用原文 */ }
    return { html: out, firstId: firstId };
}

/** 渲染预测弹窗正文：价格预测表（基准->预测价+浮动）+ 未来客流柱状 */
/* ===== 价格预估（独立接口 /api/trip-price）=====
   用户口径：① 知识库有数据以知识库为准 ✓ ② 缺的由外部 LLM 兜底 ✓
             ③ 分三部分：单次人均 / 每天人均 / 全程人均 ✓ ④ 查不到写"暂无" ✓ 不编造 ✓ */
let tripPrice = null, tripPriceKey = '';

/** 把行程整理成接口入参：每天的城市 + 需要估价的酒店/餐饮/景点 */
function priceInputOf(plan) {
    const days = (plan && plan.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const out = [];
    days.forEach((d, di) => {
        const items = [];
        (d.items || []).forEach(it => {
            if (!it) return;
            let nm = '', cat = '';
            if (isHotelItem(it)) { const a = hotelNamesOf(it); if (a.length) { nm = a[0]; cat = '住宿'; } }
            else if (isFoodItem(it)) { const a = restaurantNamesOf(it); if (a.length) { nm = a[0]; cat = '餐饮'; } }
            else if (it.place && /景区|景点|大峡谷|湖|山|古城|草原|公园/.test(String(it.place))) {
                nm = stripParens(String(it.place)).trim(); cat = '景点';
            }
            if (!nm || isNoise(nm)) return;
            const c = cityOfItem(it) || '';
            if (items.some(x => x.name === nm + cat)) return;
            items.push({ name: nm, city: c, category: cat });
        });
        const city = items.length ? (items[0].city || '') : '';
        out.push({ date: '第' + (di + 1) + '天', city: city, items: items.slice(0, 8) });
    });
    return { destination: (plan && (plan.destination || plan.title)) || '', days: out };
}

/** 取价格（带缓存；失败只影响这一块，不影响行程） */
function loadTripPrice(plan, done) {
    try {
        const inp = priceInputOf(plan);
        const key = String(inp.destination) + '|' + inp.days.map(d => d.city + ':' + d.items.map(i => i.name).join(',')).join(';');
        if (tripPrice && tripPriceKey === key) { if (done) done(); return; }
        fetch('/api/trip-price', {
            method: 'POST', headers: { 'Content-Type': 'application/json;charset=UTF-8' },
            body: JSON.stringify(inp)
        }).then(r => r.text()).then(t => {
            try { tripPrice = JSON.parse(t); } catch (e) { tripPrice = null; }
            tripPriceKey = key;
            if (done) done();
        }).catch(() => { if (done) done(); });
    } catch (e) { if (done) done(); }
}

/** 三部分：单次人均 / 每天人均 / 全程人均 */
function priceBlockHtml() {
    if (tripPrice === null) {
        return '<div class="state-box" style="padding:12px;margin-bottom:10px"><span style="font-size:12.5px;color:var(--muted)">📊 价格预估加载中…（首次约需十几秒）</span></div>';
    }
    const mx = (tripPrice && tripPrice.matrix) || null;
    if (!tripPrice || !tripPrice.success || !mx) {
        return '<div class="state-box" style="padding:12px;margin-bottom:10px"><span style="font-size:12.5px;color:var(--muted)">📊 价格预估暂不可用</span></div>';
    }
    const cols = mx.columns || ['美食', '酒店', '景区'];
    const TH = 'style="font-weight:600;font-size:12.5px;color:var(--muted);padding:6px 10px;border-bottom:1px solid var(--sand-200);text-align:center"';
    const TH0 = 'style="font-weight:600;font-size:12.5px;color:var(--muted);padding:6px 10px;border-bottom:1px solid var(--sand-200);text-align:left"';
    const TD = 'style="font-size:13px;padding:7px 10px;text-align:center;border-bottom:1px solid rgba(0,0,0,.05);font-variant-numeric:tabular-nums"';
    const TD0 = 'style="font-size:12.5px;font-weight:600;padding:7px 10px;color:var(--ink);white-space:nowrap;border-bottom:1px solid rgba(0,0,0,.05)"';
    const cell = (row, c) => {
        const v = row && row[c];
        return v ? '<b>¥' + escapeHtml(String(v)) + '</b>' : '<span style="color:var(--muted)">暂无</span>';
    };
    const rowHtml = (label, row) => '<tr><td ' + TD0 + '>' + label + '</td>'
        + cols.map(c => '<td ' + TD + '>' + cell(row, c) + '</td>').join('') + '</tr>';

    let h = '<div style="font-weight:700;font-size:14px;margin:2px 0 8px;color:var(--ink)">📊 价格预估 '
        + '<span style="font-weight:400;font-size:11.5px;color:var(--muted)">人均，仅供参考</span></div>';
    h += '<table style="width:100%;border-collapse:collapse"><thead><tr>'
        + '<th ' + TH0 + '></th>' + cols.map(c => '<th ' + TH + '>' + escapeHtml(c) + '</th>').join('')
        + '</tr></thead><tbody>'
        + rowHtml('单次人均', mx.single)
        + rowHtml('每天人均', mx.perDay)
        + rowHtml('全程人均', mx.total)
        + '</tbody></table>';
    h += '<div style="font-size:11.5px;color:var(--muted);margin-top:6px">'
        + '数据来自知识库，未覆盖的按行程估算 · '
        + '单次=每餐/每晚/每张票 · 每天=单次×每天条数 · 全程=每天×' + escapeHtml(String(mx.days || 0)) + '天'
        + (mx.totalSum ? '（全程人均合计约 ¥' + escapeHtml(String(mx.totalSum)) + '）' : '')
        + '</div>';
    return h;
}

/** 客流/基准价部分（原有知识库渲染），有数据才画 */
function insightKbPart(data) {
    if (!data) return '';
    let h = '';
    const host = { set innerHTML(v) { h = v; } };
    try { renderInsightModalBody(host, data); } catch (e) { return ''; }
    return h;
}

/** 统一重画弹窗正文：价格块 + 客流（谁先到都能正确合上，不会残留旧文案） */
function paintInsightModal() {
    const body = document.getElementById('modalBody');
    if (!body) return;
    body.innerHTML = priceBlockHtml() + insightKbPart(lastInsightData);
}

function renderInsightModalBody(bodyEl, data) {
    const pricing = data.pricing || {};
    const forecast = Array.isArray(data.forecast) ? data.forecast : [];
    let html = '';   //（价格块由 paintInsightModal 统一在最前面画，这里不重复）

    //★用户口径：价格只保留最上面那张 3×3 表（美食/酒店/景区 × 单次/每天/全程 人均），
    //  原来这张「💴 价格预测（基准价/预测价/浮动）」旧表已移除；客流预测保持原样不动。

    //-- 未来客流 --
    html += '<div style="font-weight:700;font-size:14px;margin:18px 0 2px;color:var(--ink)">🚶 客流预测 <span style="font-weight:400;font-size:11.5px;color:var(--muted)">未来 7 天客流指数（与出行日期无关）</span></div>';
    if (forecast.length) {
        const vals = forecast.map(f => Number(f.index)).filter(Number.isFinite);
        const max = Math.max.apply(null, vals.concat([1]));
        html += '<div class="pf-bars">' + forecast.map(f => {
            const v = Number(f.index);
            if (!Number.isFinite(v)) return '';
            const h = Math.max(5, Math.round(v / max * 56));
            const day = String(f.day || '').slice(5).replace('-', '/');
            const cls = v >= 1.2 ? ' hot' : (v >= 0.8 ? ' warm' : '');
            return '<div class="pf-bar-col"><div class="pf-bar' + cls + '" style="height:' + h + 'px"><span>' + v.toFixed(1) + '</span></div>' +
                '<div class="pf-bar-day">' + day + '</div><div class="pf-bar-note">' + crowdLevel(v) + '</div></div>';
        }).join('') + '</div>';
        html += '<div style="font-size:11px;color:var(--muted);margin-top:4px">柱高=客流指数：≥1.2 偏挤（红）· 0.8~1.2 中等（金）· &lt;0.8 宽松（蓝）；数据源自平台历史行程统计</div>';
    } else {
        html += '<div class="state-box" style="padding:20px">暂无客流预测数据</div>';
    }
    bodyEl.innerHTML = html;
}

function crowdLevel(value) {
    if (value < 0.8) return '较宽松';
    if (value < 1.2) return '中等';
    return '较高';
}

function formatCny(value) {
    const num = Number(value);
    if (!Number.isFinite(num)) return '-';
    return '¥' + (Number.isInteger(num) ? num.toLocaleString('zh-CN') : num.toFixed(1).replace(/\.0$/, ''));
}

function newSession() {
    //重置为全新会话：清掉旧 sessionId 关联的事件/结果，防上次任务残留
    sessionId = 's' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    return sessionId;
}

function findDayIndexOfPlace(name) {
    if (!currentPlanData || !currentPlanData.sections) return -1;
    const days = (currentPlanData.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    for (let i = 0; i < days.length; i++) {
        const items = (days[i].items || []).filter(it => it && (it.place || it.name));
        for (const it of items) {
            const pl = (it.place || it.name || '').trim();
            if (pl && (pl === name || pl.indexOf(name) >= 0 || name.indexOf(pl) >= 0)) return i;
        }
    }
    return -1;
}

function buildPrintView() {
    const box = document.getElementById('printView');
    if (!box) return;
    const plan = currentPlanData;
    if (!plan) { box.innerHTML = ''; return; }

    //★★用户口径：打印/导出的效果要和「分享链接展示的效果」一致。
    //   分享页（share.js）用的就是主页面这套渲染（.plan-sheet / .plan-header / .sec-title / 天卡片…），
    //   所以打印视图也直接复用它，不再用下面那套 pv-* 独立布局（那套跟分享页长得不一样）。
    //   下面原有实现保留（当前 return 之后不再执行），便于需要时回滚。
    box.innerHTML = renderStructuredPlan(plan);
    return;
    const sections = plan.sections || [];
    const days = sections.filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const others = sections.filter(s => s.type !== 'timeline' && !/第.+天/.test(s.title || ''));

    let html = '';
    //标题头
    html += '<div class="pv-header">';
    html += '<h1>' + escapeHtml(plan.title || '旅行规划') + '</h1>';
    html += '<div class="pv-meta">';
    if (plan.days) html += '<span>🗓 行程天数：<b>' + plan.days + '</b> 天</span>';
    if (plan.date) html += '<span class="date-chip" style="cursor:pointer" title="点击查看日历" onclick="openTripCalendar()">📅 ' + escapeHtml(plan.date) + '</span>';
    const wth = cleanWeatherText(plan.weather);
    if (wth) html += '<span>🌤 ' + escapeHtml(wth) + '</span>';
    html += '</div></div>';

    //===== 第一部分：每天概览（轨迹点 + 天卡片内容，与生成时一致） =====
    if (days.length > 0) {
        html += '<div class="pv-part"><div class="pv-part-title">概览 · OVERVIEW</div>';
        days.forEach((day, di) => {
            const items = (day.items || []).filter(it => it && (it.time || it.place || it.activity));
            const title = 'DAY ' + String(di + 1).padStart(2, '0') + ' · ' + (day.title || ('第 ' + (di + 1) + ' 天'));
            html += '<div class="pv-day">'
                + '<div class="pv-day-head"><span class="pv-day-n">' + escapeHtml(title) + '</span></div>'
                + '<div class="pv-day-stops">'
                + items.map(it =>
                    '<div class="pv-stop"><span class="pv-time">' + escapeHtml(it.time || '') + '</span>'
                    + '<span class="pv-place">' + escapeHtml(it.place || it.activity || '') + '</span>'
                    + (it.cost ? '<span class="pv-cost">' + escapeHtml(it.cost) + '</span>' : '')
                    + '</div>').join('')
                + '</div></div>';
        });
        html += '</div>';
    }

    //===== 第二部分：每天详情（弹窗完整内容） =====
    if (days.length > 0) {
        html += '<div class="pv-part"><div class="pv-part-title">每日详情 · DAILY DETAILS</div>';
        days.forEach((day, di) => {
            const items = (day.items || []).filter(it => it && (it.time || it.place || it.activity));
            const title = 'DAY ' + String(di + 1).padStart(2, '0') + ' · ' + (day.title || ('第 ' + (di + 1) + ' 天'));
            html += '<div class="pv-day-detail">'
                + '<div class="pv-detail-head">' + escapeHtml(title) + '</div>'
                + '<table class="pv-table"><tbody>'
                + items.map(it =>
                    '<tr><td class="pv-t">' + escapeHtml(it.time || '') + '</td>'
                    + '<td><b>' + escapeHtml(it.place || it.activity || '') + '</b>'
                    + [it.activity, it.cost, it.note].filter(Boolean).map(x => escapeHtml(x)).join(' · ')
                    + '</td></tr>').join('')
                + '</tbody></table></div>';
        });
        html += '</div>';
    }

    //交通/住宿/贴士等其余区块
    if (others.length > 0) {
        html += '<div class="pv-part"><div class="pv-part-title">其他信息</div>';
        others.forEach(sec => {
            html += '<div class="pv-other"><div class="pv-detail-head">' + escapeHtml(sec.title || '') + '</div><ul>';
            (sec.items || []).forEach(it => {
                const t = formatSectionItem(sec.type || 'other', it);
                if (t) html += '<li>' + escapeHtml(t) + '</li>';
            });
            html += '</ul></div>';
        });
        html += '</div>';
    }

    box.innerHTML = html;
}

/** 导出 Word：Blob 生成 HTML 格式 .doc 文件 */
function exportWord(ev) {
    if (ev && ev.stopPropagation) ev.stopPropagation();
    closeExportMenu();
    if (!hasExportablePlan()) return;

    //新结构：用 currentPlanData 生成一份干净的旧式 plan-sheet 供导出（避免轨迹/卡片结构进 Word）
    let sheet = document.querySelector('#resultBody .plan-sheet');
    if ((!sheet || !sheet.querySelector('.sec-title')) && currentPlanData) {
        const tmp = document.createElement('div');
        tmp.innerHTML = renderLegacySheet(currentPlanData); // 生成纯 plan-sheet 结构
        sheet = tmp.querySelector('.plan-sheet') || tmp;
    }
    const titleNode = sheet ? sheet.querySelector('.plan-title') : null;
    const title = (titleNode ? titleNode.textContent : '旅行计划').trim() || '旅行计划';

    //Word 内嵌样式：保证用 Word 打开时排版正确
    const wordCss = [
        'body { font-family: "Microsoft YaHei", SimSun, sans-serif; color: #2d3436; font-size: 13px; line-height: 1.7; }',
        '.plan-header { text-align: center; border-bottom: 2px solid #00b894; padding-bottom: 8px; margin-bottom: 12px; }',
        '.plan-title { font-size: 22px; margin: 0 0 6px; }',
        '.plan-meta { color: #636e72; font-size: 12px; }',
        '.plan-meta span { margin: 0 8px; }',
        '.plan-section { margin-bottom: 14px; }',
        '.sec-title { font-size: 16px; font-weight: bold; color: #0984e3; border-left: 4px solid #0984e3; padding-left: 8px; margin: 10px 0 6px; }',
        'table { border-collapse: collapse; width: 100%; margin: 8px 0; }',
        'table th, table td { border: 1px solid #b2bec3; padding: 6px 8px; font-size: 12px; }',
        'table th { background: #f0f8ff; }',
        'ul, ol { margin: 6px 0 6px 20px; }',
        'p { margin: 6px 0; }',
        'blockquote { border-left: 3px solid #dfe6e9; padding-left: 10px; color: #636e72; margin: 6px 0; }',
        'pre, code { background: #f5f5f5; padding: 2px 5px; font-size: 12px; }',
        'hr { border: none; border-top: 1px dashed #ccc; margin: 10px 0; }'
    ].join('\n');

    //组装 Word 可打开的 HTML 文档（加 \ufeff BOM 保证中文编码正确）
    const doc = '<html xmlns:o="urn:schemas-microsoft-com:office:office" ' +
        'xmlns:w="urn:schemas-microsoft-com:office:word" ' +
        'xmlns="http://www.w3.org/TR/REC-html40">' +
        '<head><meta charset="UTF-8"><title>' + escapeHtml(title) + '</title>' +
        '<style>' + wordCss + '</style></head>' +
        '<body>' + sheet.outerHTML + '</body></html>';

    const blob = new Blob(['\ufeff', doc], { type: 'application/msword' });
    //文件名：旅行计划_标题（去除非法字符）
    const safeTitle = title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 30);
    downloadBlob('旅行计划_' + safeTitle + '.doc', blob);
}

/** 触发浏览器下载 Blob 文件 */
function downloadBlob(filename, blob) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/* ============================================================ 打印/导出按钮自动显隐 结果区存在可导出的计划内容时显示按钮，否则隐藏 （监听 resultBody 内容变化：fetchResult 渲染 / viewPlan 加载历史行程都会触发） ============================================================ */
function initPrintButton() {
    const body = document.getElementById('resultBody');
    if (!body) return;

    const update = function () {
        //操作条(打印/导出 + 保存):结果区有内容才显示
        const saveBar = document.getElementById('saveBar');
        const text = (body.textContent || '').trim();
        const has = text && !text.includes('正在生成') && !text.includes('等待生成') && !body.querySelector('.gen-progress') && !text.includes('（空响应）');
        if (saveBar) saveBar.style.display = has ? 'flex' : 'none';
        //★对话式改行程（仅新增这 1 行，不改本函数既有逻辑）：结果区有行程就挂输入条，清空就收起
        if (window.TripChat) { if (has) window.TripChat.attach(viewingPlanId, currentPlanData); else window.TripChat.detach(); }
    if (has) hideThinkPanel();   // 结果已出来 -> 收起调度中心面板
    };

    //监听结果区内容变化，实时控制按钮显隐
    const observer = new MutationObserver(update);
    observer.observe(body, { childList: true, subtree: true, characterData: true });

    //页面加载时初始化一次
    update();
}

/* ★对话式改行程（新增：只有这一段，不改本文件任何既有函数）：
   把后端权威数据写回本模块的模块内状态（lastResultMarkdown / currentPlanData / viewingPlanId）。
   tripchat.js 重排成功后调用 window.TripChatState.set(markdown, plan, planId)；
   没有这个钩子时 savePlan() 读到的 lastResultMarkdown 是空的（或还是旧的那一天）→ "暂无结果可保存"。 */
window.TripChatState = {
    set: function (markdown, planData, planId) {
        if (typeof markdown === 'string' && markdown.trim()) lastResultMarkdown = markdown;
        if (planData) currentPlanData = planData;
        if (planId) viewingPlanId = planId;
    }
};
initPrintButton();

/* ★行中提醒直达（用户口径）：从通知点进来时地址栏带 ?planId=xx → 直接把那条行程打开。
   只认显式参数，不做任何"自动恢复上次行程"（服务重启后仍是干净首页）。 */
(function atpWeatherBriefJump() {
    try {
        var m = /[?&]planId=(\d+)/.exec(location.search || '');
        if (!m) return;
        var pid = m[1];
        var tries = 0;
        var timer = setInterval(function () {
            tries++;
            if (typeof viewPlan === 'function') { clearInterval(timer); try { viewPlan(Number(pid)); } catch (e) { } }
            else if (tries > 40) { clearInterval(timer); }
        }, 250);
    } catch (e) { }
})();
