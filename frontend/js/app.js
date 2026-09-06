/* ============================================================
   app.js — 主页面（index.html）全部前端逻辑
   由原 index.html 内联 <script> 拆分而来，功能完全保留：
   登录检查 / 退出 / 保存行程 / 我的行程 / 提交任务 / 轮询事件 / 取结果
   新增功能：
   1) renderMarkdown 增强：支持 markdown 表格 / 有序列表 / 行内代码
   2) renderPlanSheet：把最终计划渲染为 A4 打印风格行程规划表（分区块）
   3) 打印 / 导出：PDF（window.print()+print.css）与 Word（Blob .doc）
   ============================================================ */

// ===== 状态变量 =====
let polling = null;        // 轮询定时器（/events，已由 SSE 替代，保留兜底）
let resultTimer = null;    // 结果轮询定时器（/result）
let eventSource = null;    // SSE 实时事件流（阶段四 F-3，替代 /events 轮询）
let knownCount = 0;        // 已显示的事件数（保留，向后兼容）
let lastEventCount = 0;    // 发送前的事件总数（用于增量显示）
let lastResultMarkdown = ''; // 最近一次最终结果的 markdown 原文（保存行程用，避免存 HTML）

// ★会话隔离：每个窗口独立 sessionId（localStorage 持久），请求带上它避免多窗口互扰
let sessionId = (function () {
    let sid = localStorage.getItem('atp_session_id');
    if (!sid) {
        sid = 's' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
        localStorage.setItem('atp_session_id', sid);
    }
    return sid;
})();

// ===== ★ 登录状态检查（页面加载时执行） =====

// ===== ★生成进度条（事件驱动真进度 + 宽段慢爬，单调递增防回落）：实测耗时构成(川西7天骨架) ≈
//   候选+路线 3.5-4min / 行程并行 6-7min / 审查+返工 3-7min → 权重按真实占比铺开，
//   避免旧版"解析→行程事件一跳 85%、97% 卡 10 分钟"的前快后慢。
//   pct 锚点：候选≈14 路线≈30 行程(骨架 32→66 由"第N天生成完成"逐批推进) 审查≈72→96(REVISE 返工回落 78 重爬) 定稿 98 完成 100
const GEN_STAGES = {
    '解析': { pct: 6, label: '解析旅行需求...' },
    '候选': { pct: 14, label: '查询三域候选...' },
    '路线': { pct: 30, label: '路线Agent规划真实衔接...' },
    '行程': { pct: 72, label: '行程Agent并行规划每天...' },
    '审查': { pct: 96, label: '独立审查行程质量...' },
    '定稿': { pct: 99, label: '主管整合生成方案...' },
    '完成': { pct: 100, label: '生成完成！' }
};
// 已完成的骨架天计数（"第N天生成完成"事件推进用，新任务清零）
let genDayN = 0;

// ★平滑推进：阶段事件只在开始发一次，之后缓步爬升覆盖真实耗时（3s/步）：
//   行程≈150s → 35→84；审查/返工 ≈1-2min → 60→94；避免"半分钟到80%然后卡死"或"8%死停后突跳100"
let genSmoothTimer = null;
function startGenSmooth(targetMax, stepMs) {
    stopGenSmooth();
    genSmoothTimer = setInterval(() => {
        const pctEl = document.querySelector('.gen-pct');
        const fill = document.querySelector('.gen-fill');
        if (!pctEl || !fill) { stopGenSmooth(); return; }
        const cur = parseInt(pctEl.textContent, 10) || 0;
        if (cur >= targetMax) { stopGenSmooth(); return; }
        updateGenProgress(cur + 1, null); // label 传 null 表示保留现有文字
    }, stepMs || 3000);
}
function stopGenSmooth() {
    if (genSmoothTimer) { clearInterval(genSmoothTimer); genSmoothTimer = null; }
}

function genProgressHTML(pct, label) {
    return '<div class="gen-progress"><div class="gen-track"><div class="gen-fill" style="width:' + pct + '%"></div></div>'
        + '<div class="gen-label"><span class="gen-pct">' + pct + '%</span><span class="gen-label-text">' + escapeHtml(label || '') + '</span></div></div>';
}

function updateGenProgress(pct, label, force) {
    const fill = document.querySelector('.gen-fill');
    if (!fill) return;
    // ★单调递增：进度只增不减（REVISE 重编/路线补查会重发"路线/行程"事件，不再把进度打回低位）
    //   force=true：审查 REVISE 返工时允许小幅回落（限审查带内），让"返工重爬"可见而非 97% 卡死
    const pctEl = document.querySelector('.gen-pct');
    if (pctEl) {
        const cur = parseInt(pctEl.textContent, 10) || 0;
        if (!force && pct < cur) pct = cur;
        pctEl.textContent = pct + '%';
    }
    fill.style.width = pct + '%';
    const lbl = document.querySelector('.gen-label-text');
    if (lbl && label !== null && label !== undefined) lbl.textContent = label;
}

// ★页面重载后恢复进行中的任务：localStorage 标记 + /result 探测
// 修复竞态：任务恰在页面跳转时完成时，标记可能已被旧页面清除 → 只要有 sessionId 就探测 /result
// ★2026-09-06 修复"切页后任务消失"：newSession() 生成的新 sid 从不同步 localStorage 的 atp_session_id，
//   旧逻辑遇 sid 不匹配即清标记 → 提交后刷新/切页必然恢复失败，任务在跑却无界面。
//   现改为：优先探测标记里的 sid（真正提交过任务的会话），命中则在跑/已完成 → 把会话切过去并同步 localStorage。
function tryRestoreRunning() {
    const mark = localStorage.getItem('atp_gen_running');
    let m = null;
    try { m = mark ? JSON.parse(mark) : null; } catch (e) { localStorage.removeItem('atp_gen_running'); m = null; }
    const probe = (m && m.sid) ? m.sid : sessionId;
    probeRunning(probe, probe !== sessionId);
}

/** 探测 sid 的任务状态；fallback=true 表示探测的是标记 sid，失效时回退探测当前会话一次 */
function probeRunning(probeSid, fallback) {
    fetch('/result?sessionId=' + encodeURIComponent(probeSid))
        .then(r => r.text())
        .then(text => {
            if (text.includes('任务不存在') || text.includes('中断') || text.includes('已由用户')) {
                // 标记任务已失效/被取消：清标记；若探测的是标记 sid 且与当前会话不同 → 回退探测当前会话一次
                localStorage.removeItem('atp_gen_running');
                if (fallback) { probeRunning(sessionId, false); return; }
                resetGenUI();
                return;
            }
            // ★采用标记任务的会话（与当前不一致时切换并同步，使后续轮询/事件流/取结果指向真实任务）
            if (probeSid !== sessionId) {
                sessionId = probeSid;
                localStorage.setItem('atp_session_id', probeSid);
            }
            if (!text.includes('还在执行中')) {
                // 任务已完成：直接渲染最终结果（不丢进度条/结果）
                if (text && text.trim()) { fetchResult(); return; }
                resetGenUI();
                return;
            }
            restoreRunningUI();
        })
        .catch(() => {
            localStorage.removeItem('atp_gen_running');
            if (fallback) probeRunning(sessionId, false);
        });
}

/** 任务仍在跑：恢复进度条 + 历史事件回填思考日志 + 重连事件流 + 结果轮询（标记保留，完成时由 fetchResult 清除） */
function restoreRunningUI() {
    document.getElementById('resultBody').innerHTML = genProgressHTML(30, '任务进行中，正在恢复进度...');
    document.getElementById('sendBtn').disabled = true;
    document.getElementById('cancelBtn').style.display = 'inline-block';
    // 先拉历史事件补思考日志（切页前的阶段展示不丢），按最后阶段事件校准进度，再连 SSE 续传
    fetch('/events?sessionId=' + encodeURIComponent(sessionId))
        .then(r => r.json())
        .then(evts => {
            const body = document.getElementById('thinkBody');
            if (body) body.innerHTML = '';
            const list = evts || [];
            list.forEach(appendThinkItem);
            const last = list[list.length - 1];
            // 恢复校准：按最后里程碑事件推进（天计数从最后"第N天生成完成"恢复，保证后续续推不低估）
            if (last) {
                const md = last.content && last.content.match(/第(\d+)天生成完成/);
                if (md) genDayN = parseInt(md[1], 10);
                advanceGen(last);
            }
            lastEventCount = list.length;
            if (eventSource) eventSource.close();
            eventSource = new EventSource('/events/stream?sessionId=' + encodeURIComponent(sessionId));
            eventSource.addEventListener('event', function (e) {
                try { appendThinkItem(JSON.parse(e.data)); } catch (err) { /* 忽略非法数据 */ }
            });
            if (resultTimer) clearInterval(resultTimer);
            resultTimer = setInterval(fetchResult, 2000);
            // ★标记保留（任务仍在跑）：任务完成时由 fetchResult 清除；这里删会导致再次返回首页无法恢复
        })
        .catch(() => {
            if (eventSource) eventSource.close();
            eventSource = new EventSource('/events/stream?sessionId=' + encodeURIComponent(sessionId));
            eventSource.addEventListener('event', function (e) {
                try { appendThinkItem(JSON.parse(e.data)); } catch (err) { /* 忽略非法数据 */ }
            });
            if (resultTimer) clearInterval(resultTimer);
            resultTimer = setInterval(fetchResult, 2000);
        });
}

/** 无进行中任务/已失效：恢复默认可提交界面 */
function resetGenUI() {
    document.getElementById('resultBody').innerHTML = '';
    document.getElementById('sendBtn').disabled = false;
    document.getElementById('cancelBtn').style.display = 'none';
    localStorage.removeItem('atp_gen_running');
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
                // ★管理员额外显示「管理端」入口
                if (data.role === 'admin') {
                    document.getElementById('adminBtn').style.display = 'inline-block';
                }
                // ★切页回来：恢复进行中的生成任务进度
                tryRestoreRunning();
            } else {
                // 未登录：跳转登录页（相对路径，兼容部署上下文）
                location.href = 'login.html';
            }
        })
        .catch(() => { location.href = 'login.html'; });
}

// 退出登录
function logout() {
    fetch('/api/logout', { method: 'POST' })
        .then(r => r.json())
        .then(() => { location.href = 'login.html'; })
        .catch(() => { location.href = 'login.html'; });
}

// 保存当前结果到数据库
// ★结构化数据存储标记：markdown 原文 + 尾部追加 JSON（供历史行程还原轨迹/卡片）
const PLAN_JSON_MARK = '<!--ATPLAN_JSON_START-->';
const PLAN_JSON_END = '<!--ATPLAN_JSON_END-->';

/** 从 plan_content 里剥离结构化 JSON 标记，取回纯 markdown（预览/再保存用） */
function stripPlanJson(content) {
    const i = content.indexOf(PLAN_JSON_MARK);
    return i >= 0 ? content.slice(0, i).trim() : content;
}

/** 解析 plan_content 里的结构化 JSON；无标记/解析失败返回 null */
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
    // ★F-8：若当前有结构化行程数据，把 JSON 追加进 planContent，历史行程可还原轨迹/卡片
    // 多方案时保存整个方案集(PlanSet)，历史行程可切换其他方案并再保存
    let payload = text;
    let structuredJson = null;  // ★同步写入 plan_json 列（编辑页数据源），与 planContent 标记双写
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
    // 从结果里提取城市和天数。★城市提取优先级（先权威值、正则只兜底——否则带景点名的需求会被正则猜空成"未知城市"）：
    // 1) 结构化行程的 destination（后端解析的准确目的地）；2) 多方案集里各方案一致的 destination；3) prompt 正则兜底
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
    // ★天数提取：支持阿拉伯数字(7日)和中文数字(七日/七天)
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
    // ★F-1：保存 markdown 原文（不再存渲染后的 HTML），前端渲染时安全转义
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

// 打开"我的行程"弹层
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
                    '<span class="plan-card-time">' + (p.created_at || '') + '</span>' +
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

// 查看某条行程（把内容渲染到主页结果区）
function viewPlan(id) {
    fetch('/api/my-plans')
        .then(r => r.json())
        .then(data => {
            const p = (data.plans || []).find(x => x.id === id);
            if (p) {
                // ★第0步：优先用结构化 JSON 列渲染（供分享/二次编辑），缺失时退回内嵌JSON/markdown
                let structuredCol = null;
                if (p.plan_json) {
                    try { structuredCol = typeof p.plan_json === 'string' ? JSON.parse(p.plan_json) : p.plan_json; }
                    catch (e) { structuredCol = null; }
                }
                if (structuredCol && (structuredCol.sections || structuredCol.title)) {
                    currentPlanData = structuredCol; // ★历史行程也恢复专属地图数据源
                    viewingPlanId = id;              // ★记录行程ID：日历改期后保存用
                    lastResultJson = typeof p.plan_json === 'string' ? p.plan_json : JSON.stringify(structuredCol);
                    document.getElementById('resultBody').innerHTML = renderStructuredPlan(structuredCol);
                    document.getElementById('saveBar').style.display = 'flex';
                    closeMyPlans();
                    document.getElementById('resultBody').scrollIntoView({ behavior: 'smooth' });
                    return;
                }
                let content = p.plan_content || '';
                // ★F-8：新数据带结构化 JSON 标记 → 优先还原轨迹/卡片渲染（与生成时一致）
                const structured = parsePlanJson(content);
                if (structured) {
                    if (structured.__planSet && structured.set && structured.set.plans && structured.set.plans.length > 1) {
                        // ★多方案集：还原完整多方案界面（胶囊可切换，可再保存其他方案）
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
                    // ★F-1：区分新旧格式。旧数据是 HTML → DOMPurify 消毒；新数据是 markdown → 安全渲染
                    if (/<[a-zA-Z][^>]*>/.test(content)) {
                        // 旧数据：没有 plan-sheet 包裹的纯 HTML，套一层行程单容器
                        if (!/class="[^"]*\bplan-sheet\b/.test(content)) {
                            content = '<div class="plan-sheet"><div class="plan-body">' + content + '</div></div>';
                        }
                        document.getElementById('resultBody').innerHTML = DOMPurify.sanitize(content);
                    } else {
                        document.getElementById('resultBody').innerHTML = renderPlanSheet(content);
                    }
                    lastResultMarkdown = content.replace(/<[^>]+>/g, ''); // 记录查看的行程文本,供再次保存
                }
                // ★查看历史行程：保持与生成结果一致的操作条(保存/导出)
                document.getElementById('saveBar').style.display = 'flex';
                document.getElementById('saveMsg').textContent = '';
                closeMyPlans();
                document.getElementById('resultBody').scrollIntoView({ behavior: 'smooth' });
            }
        })
        .catch(() => { });
}

// 删除某条行程

/**
 * 分享行程：生成免登录分享链接
 */
function sharePlan(id) {
    fetch('/api/trips/' + id + '/share', { method: 'POST' })
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                const url = location.origin + data.url;
                const msg = '分享链接（免登录可看）：\n' + url;
                const copy = confirm(msg + '\n\n点击「确定」复制链接到剪贴板？');
                if (copy) {
                    copyText(url);
                }
            } else {
                alert('分享失败：' + data.message);
            }
        })
        .catch(() => { alert('分享失败，请重试'); });
}

// ★兼容 HTTP 明文页面的剪贴板复制：navigator.clipboard 在 http:// 下不可用，用 execCommand 兜底
function copyText(text) {
    // 优先：现代 Clipboard API（仅 HTTPS/localhost 可用）
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(() => {
            alert('✅ 分享链接已复制到剪贴板');
        }).catch(() => {
            // 失败降级到 execCommand
            legacyCopy(text);
        });
    } else {
        legacyCopy(text);
    }
}
function legacyCopy(text) {
    try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.top = '-9999px';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        if (ok) {
            alert('✅ 分享链接已复制到剪贴板');
        } else {
            alert('复制失败，请手动复制链接：\n' + text);
        }
    } catch (e) {
        alert('复制失败，请手动复制链接：\n' + text);
    }
}

function deletePlan(id) {
    if (!confirm('确定删除这条行程吗？')) return;
    fetch('/api/delete-plan?id=' + id, { method: 'POST' })
        .then(r => r.json())
        .then(() => { showMyPlans(); })
        .catch(() => { });
}

// 关闭弹层
function closeMyPlans() { document.getElementById('myPlansModal').style.display = 'none'; }

// 页面加载时检查登录
checkLogin();

/**
 * 发送任务
 * ★部署修复：/app 立即返回（不占长连接）→ 只轮询 /events 看进度 → 完成后拉 /result
 * 修复手机端浏览器连接数限制导致思考过程不显示的问题
 */
function send() {
    const prompt = document.getElementById('prompt').value.trim();
    if (!prompt) { alert('请输入旅行需求！'); return; }
    // ★方案选择弹窗：单方案 / 多方案（多方案=后端并行聚合3套差异化行程）
    showSchemeModal();
}

// ==================== ★方案选择弹窗（单/多方案） ====================
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
function confirmScheme() {
    document.getElementById('schemeModal').style.display = 'none';
    const prompt = document.getElementById('prompt').value.trim();
    if (!prompt) { alert('请输入旅行需求！'); return; }
    doSubmit(prompt, '', '', '', pendingScheme === 'multi' ? 'true' : 'false');
}

/**
 * ★中断任务：调用 /cancel 设置取消标志，主管Agent在下一个事件点停止
 * 不再等任务排队——中断后 RUNNING 释放，可以马上提交新任务
 */
function cancelTask() {
    if (!confirm('确定要停止当前任务吗？')) return;
    stopGenSmooth();
    localStorage.removeItem('atp_gen_running');
    fetch('/cancel?sessionId=' + encodeURIComponent(sessionId), { method: 'POST' })
        .then(r => r.text())
        .then(msg => {
            document.getElementById('status').textContent = '⏹ 正在停止任务...';
            // 停止事件流（结果会在 /result 显示"任务已中断"）
            if (eventSource) { eventSource.close(); eventSource = null; }
            if (polling) { clearInterval(polling); polling = null; }
            // 结果轮询继续（等后端写入"已中断"结果）
            if (!resultTimer) {
                resultTimer = setInterval(fetchResult, 2000);
            }
        })
        .catch(() => { /* 网络抖动，忽略 */ });
}

// ==================== ★预检卡片（功能1：意图拆解）+ 偏好记忆（功能5） ====================

/** 当前选中的偏好（内存态，提交时读取） */
let currentPref = { walkTolerance: '', chronotype: '', museumRatio: '', scheme: 'single' };

/** 弹出预检卡片：回填历史偏好，绑定选项点击 */
function showPrecheck() {
    // 重置选中态
    document.querySelectorAll('.precheck-opt').forEach(b => b.classList.remove('active'));
    currentPref = { walkTolerance: '', chronotype: '', museumRatio: '', scheme: 'single' };
    // 默认单方案高亮
    highlightOpt('schemeOpts', 'single');
    // 拉取历史偏好回填默认值（隐形学习：下次不用重新勾选）
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
    // 绑定选项点击（每次弹出重新绑定）
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

/** 确认生成：skip=true 跳过偏好（全不限）；否则带勾选偏好提交 */
function submitWithPreference(skip) {
    document.getElementById('precheckModal').style.display = 'none';
    const prompt = document.getElementById('prompt').value.trim();
    if (!prompt) { alert('请输入旅行需求！'); return; }
    const walk = skip ? '' : (currentPref.walkTolerance || '');
    const chrono = skip ? '' : (currentPref.chronotype || '');
    const museum = skip ? '' : (currentPref.museumRatio || '');
    const multi = (!skip && currentPref.scheme === 'multi') ? 'true' : 'false';
    // ★偏好持久化：非跳过时保存（隐形学习）；跳过不覆盖历史偏好
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
    newSession(); // ★每次新任务强制新会话：防止沿用历史 sessionId 提交，导致 task_history 的 submit_ms 停在旧时间、任务监控按旧时间归档看不到新任务
    if (polling) { clearInterval(polling); polling = null; }
    if (eventSource) { eventSource.close(); eventSource = null; }
    if (resultTimer) { clearInterval(resultTimer); resultTimer = null; }

    // 重置界面
    document.getElementById('thinkBody').innerHTML = '';
    document.getElementById('resultBody').innerHTML = genProgressHTML(8, '正在提交任务...');
    localStorage.setItem('atp_gen_running', JSON.stringify({ sid: sessionId, t: Date.now() }));
    document.getElementById('status').textContent = '⏳ 任务提交中...';
    document.getElementById('status').classList.add('show');
    document.getElementById('sendBtn').disabled = true;

    // ① 提交任务（带偏好参数）
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
            // ★候选带慢爬：候选查询 ~60-75s，从 2 爬向 14（6s/步），候选完成事件到即接管
            genDayN = 0;
            updateGenProgress(2, '解析旅行需求...');
            startGenSmooth(14, 6000);
        })
        .catch(err => {
            document.getElementById('resultBody').innerHTML =
                '<div style="color:#d63031">❌ 请求失败：' + err + '</div>';
            document.getElementById('status').textContent = '❌ 出错了，请重试';
            document.getElementById('sendBtn').disabled = false;
        });
}

/**
 * 拉取最终结果（/result 返回非"执行中" = 完成）
 */
function fetchResult() {
    fetch('/result?sessionId=' + encodeURIComponent(sessionId))
        .then(r => r.text())
        .then(text => {
            if (text.includes('还在执行中')) return;  // 没完成，继续等

            // 完成！渲染为 A4 行程规划表
            localStorage.removeItem('atp_gen_running');
            clearInterval(resultTimer); resultTimer = null;
            if (eventSource) { eventSource.close(); eventSource = null; }
            if (polling) { clearInterval(polling); polling = null; }
            document.getElementById('cancelBtn').style.display = 'none'; // 隐藏停止按钮

            // ★阶段三：优先按结构化 JSON 渲染；旧 markdown / 错误文本走降级路径
            let structured = null;
            try { structured = JSON.parse(text); } catch (e) { /* 非 JSON：按原文处理 */ }
            if (structured && structured.plans && Array.isArray(structured.plans) && structured.plans.length > 1) {
                // ★功能4：多方案对比（PlanSet）
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
            // ★ 结果出来了，显示"保存到我的行程"操作条
            document.getElementById('saveBar').style.display = 'flex';
            document.getElementById('saveMsg').textContent = '';
            // ★ 会话式增量修正入口：仅结构化结果可修订（降级文本不可）
            const canRevise = structured && ((structured.sections && structured.sections.length) || structured.title);
            document.getElementById('reviseBar').style.display = canRevise ? 'flex' : 'none';
        })
        .catch(() => { /* 网络抖动，下轮重试 */ });
}

/**
 * 会话式增量修正（PDF §十三）：对当前结果提一句修改，后端影响分析 → 只重跑受影响域 → 局部重聚合。
 */
function submitRevise() {
    const input = document.getElementById('reviseInput');
    const instruction = ((input && input.value) || '').trim();
    if (!instruction) { if (input) input.focus(); return; }
    if (polling) { clearInterval(polling); polling = null; }
    if (eventSource) { eventSource.close(); eventSource = null; }
    if (resultTimer) { clearInterval(resultTimer); resultTimer = null; }

    document.getElementById('thinkBody').innerHTML = '';
    document.getElementById('resultBody').innerHTML = genProgressHTML(8, '正在提交修订...');
    localStorage.setItem('atp_gen_running', JSON.stringify({ sid: sessionId, t: Date.now() }));
    document.getElementById('status').textContent = '⏳ 修订提交中...';
    document.getElementById('status').classList.add('show');
    document.getElementById('sendBtn').disabled = true;

    fetch('/app/revise?instruction=' + encodeURIComponent(instruction)
        + '&sessionId=' + encodeURIComponent(sessionId), { method: 'POST' })
        .then(r => r.text())
        .then(msg => {
            if (msg.includes('有任务正在执行') || msg.includes('没有可修正') || msg.includes('无权')) {
                document.getElementById('resultBody').innerHTML =
                    '<div style="color:#e17055">⚠️ ' + msg + '</div>';
                document.getElementById('status').textContent = '⚠️ 修订未提交';
                document.getElementById('sendBtn').disabled = false;
                return;
            }
            input.value = '';
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
            // ★修订提交=整单重编+复核：直接进审查带慢爬，事件到即接管
            genDayN = 0;
            updateGenProgress(70, '按修订意见重新生成并复核...');
            startGenSmooth(96, 8000);
        })
        .catch(err => {
            document.getElementById('resultBody').innerHTML =
                '<div style="color:#d63031">❌ 请求失败：' + err + '</div>';
            document.getElementById('status').textContent = '❌ 出错了，请重试';
            document.getElementById('sendBtn').disabled = false;
        });
}

/**
 * ★事件→进度映射（真里程碑驱动）：骨架主路径事件序列与后端一致（见 task_events type/content）：
 *   解析 → 候选(开始/完成/路线按簇) → 定稿(天骨架并行定稿=行程开始) → 定稿(第N天生成完成×7) →
 *   候选(代码回取,REVISE注入) → 审查(独立审查员) → 校验/评分(第N轮) → 审查(REVISE返工) → 校验(第2轮) → 完成
 *   pct 锚点（按实测时间占比铺开）：候选 14 / 路线 30 / 骨架行程 32→66(每天+4.5) / 审查 72→96 / 定稿 98
 *   force 回落仅限审查 REVISE：进度若已 >78 允许落回 78 重爬（返工可见，不会 97% 卡死）
 */
function advanceGen(ev) {
    if (!ev || !ev.type) return;
    const t = ev.type;
    const c = ev.content || '';
    if (t === '完成') { stopGenSmooth(); updateGenProgress(100, '生成完成！'); return; }
    if (t === '解析') { updateGenProgress(6, '解析旅行需求...'); return; }
    if (t === '候选') {
        if (c.indexOf('已前置入状态') >= 0 || c.indexOf('已入状态中心') >= 0) {
            updateGenProgress(14, '三域候选已就绪'); stopGenSmooth();
        } else if (c.indexOf('路线按簇') >= 0 || c.indexOf('查真实衔接') >= 0) {
            updateGenProgress(16, '路线Agent规划真实衔接...'); startGenSmooth(30, 9000);
        } else {
            updateGenProgress(10, '查询三域候选...'); startGenSmooth(14, 7000);
        }
        return;
    }
    if (t === '路线') { updateGenProgress(30, '路线Agent规划真实衔接...'); stopGenSmooth(); return; }
    if (t === '定稿') {
        const mDay = c.match(/第(\d+)天生成完成/);
        if (mDay) { // 骨架：每天/每批完成 → 行程带 32→66 推进（事件频率即真实节奏）
            genDayN = Math.max(genDayN, parseInt(mDay[1], 10));
            const p = Math.min(66, 32 + genDayN * 4.5);
            updateGenProgress(p, '行程Agent 已生成 ' + genDayN + ' 天（并行推进中）...');
            stopGenSmooth();
        } else if (c.indexOf('天骨架并行定稿') >= 0 || c.indexOf('逐日短任务') >= 0) {
            genDayN = 0;
            updateGenProgress(32, '行程Agent 并行生成各天行程...');
            stopGenSmooth();
        } else {
            updateGenProgress(98, '主管整合生成方案...'); stopGenSmooth();
        }
        return;
    }
    if (t === '校验' || t === '评分') {
        const rnd = c.match(/第(\d+)轮/);
        const r = rnd ? parseInt(rnd[1], 10) : 1;
        if (r >= 2) { stopGenSmooth(); updateGenProgress(94, '返工后复核通过，即将交付'); }
        else updateGenProgress(72, '第 1 轮校验/评分中...');
        return;
    }
    if (t === '审查') {
        if (c.indexOf('REVISE') >= 0 || c.indexOf('返工') >= 0 || c.indexOf('重编') >= 0) {
            // 审查发现问题 → 返工（回落限审查带内，可见"返工重爬"而非卡死）
            stopGenSmooth();
            updateGenProgress(78, '审查发现问题，正在返工修正...', true);
            startGenSmooth(96, 8000);
        } else if (c.indexOf('修正上限') >= 0) {
            stopGenSmooth(); updateGenProgress(96, '已达修正上限，交付当前版本');
        } else {
            updateGenProgress(70, '独立审查行程质量...');
        }
        return;
    }
    const st = GEN_STAGES[t];
    if (st) updateGenProgress(st.pct, st.label);
}

/**
 * 追加一条思考过程事件到界面（SSE 与轮询共用）
 */
function appendThinkItem(ev) {
    advanceGen(ev); // ★事件驱动进度推进（候选/路线/第N天完成/审查REVISE…按真实里程碑跳，宽段慢爬）
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
    content.textContent = (ev.content || '').slice(0, 120) +
        ((ev.content || '').length > 120 ? '...' : '');

    div.appendChild(time);
    div.appendChild(type);
    div.appendChild(content);
    body.appendChild(div);

    body.scrollTop = body.scrollHeight; // 自动滚动到底部
}

/**
 * 轮询事件接口（兜底路径，正常情况下由 SSE 取代）
 */
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

/**
 * 事件类型 → 样式类
 */
function typeClass(type) {
    if (type === '用户输入') return 't-user';
    if (type === '调用工具') return 't-tool';
    if (type === '思考') return 't-think';
    if (type === '完成') return 't-done';
    return 't-other';
}

/* ============================================================
   Markdown 渲染（增强版）
   支持：标题 h1-h4 / 表格 / 无序列表 / 有序列表 / 引用 /
        分隔线 / 代码块 / 加粗 / 行内代码 / 普通段落
   ============================================================ */

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

/**
 * ★A4 打印风格：活动类型图标（按 md 设计文档要求，用图标区分活动类型）
 * 依据单元格/列表项关键词，返回对应 emoji 图标：
 *   景点/游览 → 🎡  餐饮/美食 → 🍜  交通/地铁 → 🚇  住宿/酒店 → 🏨
 *   门票/费用 → 🎫  电话/紧急 → 📞  天气 → 🌤️
 */
/** 活动类型 → 图标（关键词匹配,兜底返回通用图标,永不空白） */
function activityIcon(text) {
    text = text || '';
    // 交通类(按优先级:先具体后通用)
    if (/地铁|高铁|火车|动车|城际/.test(text)) return '🚄';
    if (/公交|巴士|大巴/.test(text)) return '🚌';
    if (/飞机|机场|航班|航空/.test(text)) return '✈️';
    if (/打车|出租车|滴滴/.test(text)) return '🚕';
    if (/自驾|驾车|开车|高速/.test(text)) return '🚗';
    if (/步行|徒步|走路|散步|登山|爬山/.test(text)) return '🥾';
    if (/骑行|自行车|单车|骑行道/.test(text)) return '🚲';
    if (/船|码头|轮渡|游船|帆船/.test(text)) return '⛵';
    if (/缆车|索道/.test(text)) return '🚡';
    // 餐饮类
    if (/火锅|涮肉|涮锅/.test(text)) return '🍲';
    if (/烤鸭|烤肉|烧烤|烤鱼/.test(text)) return '🍖';
    if (/海鲜|生蚝|龙虾|螃蟹/.test(text)) return '🦞';
    if (/小吃|夜市|美食街|排档|路边/.test(text)) return '🍢';
    if (/咖啡|奶茶|茶饮|茶馆|下午茶/.test(text)) return '☕';
    if (/早餐|早点|包子|豆浆|豆汁|油条|煎饼/.test(text)) return '🥟';
    if (/甜点|蛋糕|冰淇淋|糖水|甜品/.test(text)) return '🍰';
    if (/面|粉|馄饨|饺子|烧卖/.test(text)) return '🍜';
    if (/餐厅|饭|餐|美食|品尝|小吃/.test(text)) return '🍽️';
    // 景点类
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
    // 住宿类
    if (/酒店|宾馆|饭店/.test(text)) return '🏨';
    if (/民宿|客栈|青旅|青年旅社/.test(text)) return '🏡';
    if (/住宿|入住|休息/.test(text)) return '🛏️';
    // 其他
    if (/门票|票价|费用|价格|消费|¥|￥|元/.test(text)) return '🎫';
    if (/电话|紧急|求助|报警|急救|联系|热线/.test(text)) return '📞';
    if (/天气|气温|温度|℃|晴|雨|雪|多云/.test(text)) return '🌤️';
    if (/免税|购物/.test(text)) return '🛒';
    // 兜底：通用定位图标，保证永不空白
    return '📍';
}

/** 给文本添加活动类型图标（无匹配则原样返回） */
function withActivityIcon(text) {
    const icon = activityIcon(text);
    return icon ? '<span class="activity-icon">' + icon + '</span>' + text : text;
}

/** 将 markdown 表格行数组渲染为 <table class="plan-table"> */
function buildTable(rows) {
    // 解析每行单元格（去掉首尾 | 再按 | 切分）
    const parsed = rows.map(r => {
        let s = r.trim();
        if (s.startsWith('|')) s = s.slice(1);
        if (s.endsWith('|')) s = s.slice(0, -1);
        return s.split('|').map(c => c.trim());
    });
    const header = parsed[0] || [];
    let body = parsed.slice(1);
    // 跳过 markdown 分隔行（如 |---|---| 或 |:--|--:|）
    if (body.length && body[0].every(c => /^:?-{2,}:?$/.test(c.replace(/\s/g, '')))) {
        body = body.slice(1);
    }

    // ★时间轴识别：首列表头是"时间"类关键词时，用时间轴样式渲染（文档"详细时间表区"要求）
    const isTimeline = /时间|时刻|时段|time/.test(header[0] || '');

    let html = '<table class="plan-table' + (isTimeline ? ' timeline-table' : '') + '">';
    if (isTimeline) {
        // 时间轴模式：第一列渲染为时间胶囊，其余列正常
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

    // 普通表格模式
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

/**
 * 简易 Markdown 渲染（标题/表格/列表/引用/代码块/加粗/行内代码）
 * 先 HTML 转义防注入，再按行解析为 HTML
 */
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

    // 收尾工具函数
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

        // 代码块围栏（``` 开头/结束）
        if (/^```/.test(t)) {
            closeList(); closeTable();
            if (inCode) { out += '</pre>'; inCode = false; }
            else { out += '<pre>'; inCode = true; }
            return;
        }
        // 代码块内：原样输出
        if (inCode) { out += t + '\n'; return; }

        // 表格行（以 | 开头，连续收集）
        if (/^\|/.test(t)) {
            closeList();
            if (!inTable) { tableRows = []; inTable = true; }
            tableRows.push(t);
            return;
        }
        // 非表格行时，若在表格中则先结束表格
        if (inTable) closeTable();

        // 标题（支持 # 后有无空格，1-4 级）
        if (/^#{1,4}\s*\S/.test(t)) {
            closeList();
            const level = Math.min(t.match(/^#+/)[0].length, 4);
            const title = applyInline(t.replace(/^#+\s*/, ''));
            out += '<h' + level + '>' + title + '</h' + level + '>';
            return;
        }
        // 无序列表项（- * •）
        if (/^[-*•]\s/.test(t)) {
            closeTable(); closeList();
            if (!inUl) { out += '<ul>'; inUl = true; }
            out += '<li>' + withActivityIcon(applyInline(t.replace(/^[-*•]\s/, ''))) + '</li>';
            return;
        }
        // 有序列表项（1. 1、）
        if (/^\d+[.、]\s/.test(t)) {
            closeTable(); closeList();
            if (!inOl) { out += '<ol>'; inOl = true; }
            out += '<li>' + withActivityIcon(applyInline(t.replace(/^\d+[.、]\s/, ''))) + '</li>';
            return;
        }
        // 引用
        if (/^>\s?/.test(t)) {
            closeList();
            out += '<blockquote>' + applyInline(t.replace(/^>\s?/, '')) + '</blockquote>';
            return;
        }
        // 分隔线
        if (/^---+$/.test(t) || /^\*\*\*+$/.test(t) || /^___+$/.test(t)) {
            closeList();
            out += '<hr>';
            return;
        }
        // 空行：关闭列表
        if (t === '') { closeList(); return; }

        // 普通段落
        closeList();
        out += '<p>' + applyInline(t) + '</p>';
    });

    // 收尾
    if (inUl) out += '</ul>';
    if (inOl) out += '</ol>';
    if (inCode) out += '</pre>';
    if (inTable) closeTable();

    return out;
}

/* ============================================================
   A4 旅行规划表（按《旅行规划表设计提示词》区块渲染）
   区块：标题区 / 概览 / 时间表(时间轴) / 交通 / 住宿餐饮 / 实用信息
   ★新增：行程概览区（自动从时间表中提取当天活动）+ 时间轴样式
   ============================================================ */

/** 区块分类规则：标题关键词 → 样式类 + 图标 */
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

/**
 * 将 markdown 最终结果渲染为 A4 行程规划表 HTML
 * 结构：.plan-sheet
 *        ├── .plan-header（行程标题区：主标题 + 天数 meta）
 *        └── .plan-section × N（概览/时间表/交通/住宿餐饮/实用信息）
 */
function renderPlanSheet(text) {
    if (!text) return '<div class="empty">（空响应）</div>';

    const mdHtml = renderMarkdown(text);
    const temp = document.createElement('div');
    temp.innerHTML = mdHtml;

    const sheet = document.createElement('div');
    sheet.className = 'plan-sheet';

    // 第一个标题：若不是区块关键词，视为行程主标题放进标题区
    const headings = temp.querySelectorAll('h1, h2, h3');
    const titleNode = headings.length ? headings[0] : null;
    const firstRule = titleNode ? classifySection(titleNode.textContent || '') : null;
    const hasRealTitle = !!titleNode && firstRule.keys.length === 0;

    // ---- 行程标题区 ----
    const header = document.createElement('div');
    header.className = 'plan-header';
    const titleText = hasRealTitle ? titleNode.textContent.trim() : '旅行规划';
    const h = document.createElement('h2');
    h.className = 'plan-title';
    h.textContent = titleText;
    header.appendChild(h);

    // meta 行：从标题/全文提取行程天数（支持阿拉伯数字与中文数字，如"3日游"/"三日游"）
    const meta = document.createElement('div');
    meta.className = 'plan-meta';
    const cnDigits = { '零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9 };
    /** 中文数字 → 整数（支持 一~九、十、X十、X十Y） */
    function cnNumToInt(s) {
        if (/^[零一二两三四五六七八九]$/.test(s)) return cnDigits[s];
        const m = s.match(/^([零一二两三四五六七八九])?十([零一二三四五六七八九])?$/);
        if (m) return (m[1] ? cnDigits[m[1]] : 1) * 10 + (m[2] ? cnDigits[m[2]] : 0);
        return NaN;
    }
    let planDays = null;
    // 优先从主标题提取（"上海一日游"→1、"北京三日游"→3）
    const titleNum = (titleText || '').match(/(\d+)\s*日/);
    const titleCn = (titleText || '').match(/([零一二两三四五六七八九十]+)\s*日/);
    if (titleNum) planDays = parseInt(titleNum[1], 10);
    else if (titleCn) { const d = cnNumToInt(titleCn[1]); if (!isNaN(d)) planDays = d; }
    // 标题没有时再从全文提取（注意避免误中日期里的"N日"）
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
    // ★行程标题区增强：提取出行日期（如 2025年3月30日）
    const dateMatch = text.match(/(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日/);
    if (dateMatch) {
        const ds = document.createElement('span');
        ds.textContent = '📅 ' + dateMatch[1] + '年' + dateMatch[2] + '月' + dateMatch[3] + '日';
        meta.appendChild(ds);
    }
    // ★行程标题区增强：提取天气摘要（如 **天气**：阴，13°C/7°C，东风1-3级）
    // ★修复：原正则"天气[\s:：]*"会误匹配"天气与注意事项"标题（天气后直接跟"与"没冒号）→ 标签栏出现孤零零的"与注意事项"
    //  改为要求"天气"后必须紧跟冒号/空白/内容分隔，排除标题误匹配
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

    // ---- 正文分区块 ----
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

    /* ★行程概览区：遍历正文，把"第X天"标题及其下的首个表格/列表提取为
       概览卡片（目的地+当天要点），符合文档"按日期分区的行程简表"要求 */
    const overview = document.createElement('div');
    overview.className = 'plan-section sec-overview';
    const ovTitle = document.createElement('div');
    ovTitle.className = 'sec-title';
    ovTitle.innerHTML = '<span class="sec-icon">🗺️</span><span>行程概览</span>';
    overview.appendChild(ovTitle);

    const dayHeadings = Array.from(temp.querySelectorAll('h1, h2, h3, h4'))
        // 匹配中文"第一天/第1天" 或 英文"Day 1"（DeepSeek 倾向输出 Day N，Qwen 倾向中文）
        .filter(h => /第\s*[一二三四五六七八九十\d]+\s*[天日]|Day\s*\d+/i.test(h.textContent || ''));

    let overviewBuilt = false;
    if (dayHeadings.length > 0) {
        const grid = document.createElement('div');
        grid.className = 'overview-grid';
        dayHeadings.forEach(h => {
            const card = document.createElement('div');
            card.className = 'overview-card';
            const dayName = document.createElement('b');
            // "Day 1：xxx" → "第1天：xxx"（保持概览卡片中文显示一致）
            dayName.textContent = h.textContent.trim()
                .replace(/^Day\s*(\d+)/i, '第$1天');
            card.appendChild(dayName);
            // ★用户要求：概览卡片只显示标题，不提取内容（内容在下面行程详情里完整展示）
            //  移除原"取标题后表格/列表/段落"逻辑——卡片保持简洁美观
            grid.appendChild(card);
        });
        overview.appendChild(grid);
        sheet.appendChild(overview);
        overviewBuilt = true;
    }

    Array.from(temp.childNodes).forEach(node => {
        // 忽略纯文本/换行节点
        if (node.nodeType !== 1) return;

        const tag = node.tagName.toLowerCase();

        // 主标题已放入标题区，正文跳过
        if (hasRealTitle && !usedTitle && node === titleNode) {
            usedTitle = true;
            return;
        }

        // ★日期/天气摘要已放入标题区 meta，正文段落中跳过，避免重复
        if (tag === 'p' && /^(日期|天气)[:：]/.test((node.textContent || '').trim())) {
            return;
        }

        // 标题 → 开启新区块（按关键词分类）
        if (tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4') {
            const rule = classifySection(node.textContent || '');
            newSection(rule, node.textContent.trim());
            return;
        }

        // 其他内容（段落/表格/列表等）放入当前区块
        ensureSection().appendChild(node);
    });

    // 空正文兜底
    if (sheet.querySelectorAll('.plan-section').length === 0) {
        const emptyDiv = document.createElement('div');
        emptyDiv.className = 'empty';
        emptyDiv.textContent = '（空响应）';
        ensureSection().appendChild(emptyDiv);
    }

    return sheet.outerHTML;
}

/* ============================================================
   ★结构化行程渲染（阶段三：直接渲染后端 JSON，不再正则猜测）
   后端 /result 返回 TripPlanData JSON（含降级 markdown 字段），
   此处直接按 sections 分区块渲染，替代 classifySection/activityIcon 等猜测逻辑。
   ============================================================ */

/** 区块类型 → 图标 / 样式类 */
const STRUCT_ICON = { overview: '🗺️', timeline: '🕒', transport: '🚄', stay: '🏨', tips: '📌' };
const STRUCT_CLASS = { overview: 'sec-overview', timeline: 'sec-timeline', transport: 'sec-transport', stay: 'sec-stay', tips: 'sec-tips' };

/** ★F-5：城市 → 地标 emoji（标题区差异化装饰） */
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
            let t = item.from && item.to ? (item.from + ' → ' + item.to) : (item.from || item.to || '');
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

// ==================== ★功能4：多方案对比（MVP） ====================

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

    // 顶部风格胶囊栏（复用 scheme 样式）
    let tabs = '<div class="schemes"><div class="schemes-label">旅行风格 · PICK A STYLE</div><div class="scheme-row">';
    plans.forEach((p, i) => {
        const label = STYLE_LABELS[set.styles?.[i]] || ('方案' + (i + 1));
        tabs += '<div class="scheme' + (i === 0 ? ' active' : '') + '" onclick="switchPlan(' + i + ')">' + label + '</div>';
    });
    tabs += '</div></div>';

    // 当前方案详情
    tabs += '<div id="plansetBody" class="planset-body"></div>';

    // 渲染当前方案（异步回填 body）
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
    // 更新胶囊高亮(胶囊在 resultBody 里)
    document.querySelectorAll('#resultBody .scheme').forEach((b, i) => {
        b.classList.toggle('active', i === planSetIndex);
    });
    // 记录当前方案 markdown(供保存当前看的方案)
    lastResultMarkdown = p.markdown || '';
}

/** 切换到第 i 套方案 */
function switchPlan(i) {
    planSetIndex = i;
    refreshPlanSetBody();
}

/** 当前渲染中的行程数据（供详情弹窗读取） */
let currentPlanData = null;

/* 行程项显示名/图标：景点餐饮用 place/activity；交通衔接项无 place 时用 from→to 或 way（修复时间轴/卡片空白节点） */
function planItemLabel(it) {
    if (!it) return '';
    if (it.place || it.activity) return it.place || it.activity;
    if (it.from && it.to) return it.from + ' → ' + it.to;
    return it.way || it.note || '';
}

/* 卡片行活动小字：剥离与地点重复的头部与常见动词，留下活动要点（区分同地多次安排，如「康定情歌广场」游览 vs 逛沿街商铺） */
function planItemAct(it) {
    const a = (it.activity || '').trim();
    if (!a || !it.place) return '';
    let s = a;
    if (s.startsWith(it.place)) s = s.slice(it.place.length).replace(/^[·:：\s]+/, '');
    s = s.replace(/^(游览|参观|前往|打卡|体验|逛|漫步|去|夜游|乘车前往|早餐|午餐|晚餐)[·:：\s]*/, '');
    s = s.replace(/\s*$/, '');
    if (!s || s === (it.place || '').trim()) return ''; // 剥离后无有效区分（活动即地点名）则不显示
    return s.length > 24 ? s.slice(0, 24) + '…' : s;
}

/** 将结构化 TripPlanData 渲染为「轨迹 + 天卡片」结构 */
function renderStructuredPlan(plan) {
    if (!plan) return '<div class="empty">（空响应）</div>';
    currentPlanData = plan;
    const sections = plan.sections || [];

    // 提取天数区块（timeline）与其余区块
    const days = sections.filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const others = sections.filter(s => s.type !== 'timeline' && !/第.+天/.test(s.title || ''));

    // 标题头
    let html = '<div class="plan-header">';
    const landmark = CITY_LANDMARKS[plan.destination] || '';
    html += '<h2 class="plan-title">' + escapeHtml(plan.title || '旅行规划') + '</h2>';
    html += '<div class="plan-meta">';
    html += '<button type="button" class="meta-btn" onclick="openInsightModal()" title="查看该城市价格预测与未来7天客流">📈 价格·客流预测</button>';
    if (plan.days) html += '<span>🗓 <b>' + plan.days + '</b> 天</span>';
    if (plan.date) html += '<span class="date-chip" id="dateChip" style="cursor:pointer" title="点击查看日历" onclick="openTripCalendar()">📅 ' + escapeHtml(plan.date) + '</span>';
    const wth = cleanWeatherText(plan.weather);
    if (wth) html += '<span>🌤 ' + escapeHtml(wth) + '</span>';
    html += '</div></div>';

    // 参考卡已收纳为上方「📈 价格·客流预测」按钮弹窗，不再自动浮出

    // 无 timeline 区块：整单走 markdown 兜底（历史行程兼容）
    if (days.length === 0) {
        html += '<div class="plan-sheet">'
            + (plan.markdown ? renderMarkdown(plan.markdown) : '<div class="empty">（空响应）</div>')
            + '</div>';
        return html;
    }

    // ===== 轨迹时间轴（第 1 天） =====
    const day0 = days[0];
    const d0Items = (day0.items || []).filter(it => it && (it.time || it.place || it.activity) && !isWeatherItem(it));
    html += '<div class="route-line">'
        + '<svg class="bg" viewBox="0 0 1050 100" preserveAspectRatio="none" fill="none">'
        + '<path d="' + routePath(d0Items.length) + '" stroke="rgba(31,174,143,.55)" stroke-width="2.4" stroke-dasharray="6 8"/></svg>'
        + '<div class="stops">'
        + d0Items.slice(0, 6).map((it, i) => {
            const nm = it.place || it.activity || '';
            const fb = !nm ? planItemLabel(it) : '';
            const hl = i === 1 || i === d0Items.length - 2; // 关键点高亮（第2个与倒数第2个）
            return '<div class="stop' + (hl ? ' hl' : '') + '">'
                + '<div class="dot"></div><div class="tm">' + escapeHtml(it.time || '') + '</div>'
                + '<div class="pl">' + (nm ? amapLink(nm) : escapeHtml(fb)) + '</div>'
                + '<div class="d">' + escapeHtml(nm ? (it.cost || '') : (it.duration || '')) + '</div></div>';
        }).join('')
        + '</div></div>';

    // ===== 天卡片(概览=每天2-4个景点/途经点：纯移动行与同地点变体已在 dayOverviewItems 过滤合并) =====
    html += '<div class="days">';
    days.forEach((day, di) => {
        const rawItems = (day.items || []).filter(it => it && (it.time || it.place || it.activity || it.from || it.way));
        // 天气行：从景点流剔除，改放卡片右上角（纯文本不可跳转；详情页铅笔仍完整展示）
        const wIt = rawItems.find(isWeatherItem);
        let wText = '';
        if (wIt) {
            wText = String(wIt.activity || wIt.place || '')
                .replace(/^\s*天气\s*[:：]?\s*/, '').trim();
        }
        const items = dayOverviewItems(rawItems.filter(it => !isWeatherItem(it)));
        html += '<div class="day-card' + (di === 0 ? ' active' : '') + '" onclick="selectDay(' + di + ')">'
            + '<div class="day-top">'
            + '<span class="n">DAY ' + String(di + 1).padStart(2, '0') + '</span>'
            + (wText ? '<span class="day-wx" title="当天天气（以出行前实时预报为准）">' + escapeHtml(wText) + '</span>' : '')
            + '<button class="expand" id="dayMapBtn_' + di + '" title="查看当天高德路线图" onclick="event.stopPropagation();openDayMap(' + di + ')"><i class="fa-solid fa-map-location-dot"></i></button>'
            + '<button class="expand" title="查看当日详细规划" onclick="event.stopPropagation();openDayModal(' + di + ')"><i class="fa-solid fa-pen"></i></button>'
            + '</div>'
            + items.map(it => {
                const nm = it.place || it.activity || '';
                const fb = !nm ? planItemLabel(it) : '';
                if (!nm && !fb) return '';
                const act = (!fb ? planItemAct(it) : '') + (it._extraAct ? '；' + it._extraAct : '');
                return '<div class="item"><span class="ic">' + (nm ? activityIcon(it.activity || it.place || '') : '🚌') + '</span>'
                    + '<span><span class="pl">' + (nm ? amapLink(nm) : escapeHtml(fb)) + '</span>'
                    + (act ? '<span class="act">' + escapeHtml(act) + '</span>' : '') + '</span></div>';
            }).join('')
            + '</div>';
    });
    html += '</div>';

    // ===== 其余区块（交通/住宿/贴士）：items 全空时整卡隐藏，防"只有标题的空壳" =====
    if (others.length > 0) {
        html += '<div class="plan-sheet">';
        others.forEach(sec => {
            const type = sec.type || 'other';
            const lis = (sec.items || []).map(it => {
                const t = formatSectionItem(type, it);
                return t ? '<li>' + escapeHtml(t) + '</li>' : '';
            }).join('');
            if (!lis) return; // 无任何有效内容：不渲染空卡
            html += '<div class="plan-section ' + (STRUCT_CLASS[type] || 'sec-other') + '">'
                + '<div class="sec-title"><span class="sec-icon">' + (STRUCT_ICON[type] || '📋') + '</span><span>' + escapeHtml(sec.title || '行程详情') + '</span></div><ul>'
                + lis
                + '</ul></div>';
        });
        html += '</div>';
    }

    return html;
}

/** 概览卡片行核心地点名：城市前缀(·前≤4字)去掉、括号注释去掉、'景区内/一带/方向'尾缀去掉——用于把
 *  同一点的不同表述（康定·木格措(雅拉乡) / 木格措景区内 / 康定·溜溜城 vs 将军桥溜溜城）合并成一行 */
function placeCore(p) {
    if (!p) return '';
    let s = String(p).replace(/（[^）]*）/g, '').replace(/\([^)]*\)/g, '').replace(/\s+/g, '');
    const i = s.indexOf('·');
    if (i > 0 && s.slice(0, i).length <= 4) s = s.slice(i + 1);
    return s.replace(/(景区内|一带|方向|附近|景区入口|停车场|主入口)$/, '');
}

/** 纯移动行（A→B 无地点字段）：概览不占行，详情(铅笔)才显示 */
function isPureMove(it) {
    return !!(it.from && it.to) && !(it.place || '').trim();
}

/** 天气条目识别：cat 标天气，或（无 place 的纯文本行以"天气"开头 / 含"月日+天气词"）——
 *  概览将其提升到卡片右上角纯文本展示，不当作可跳转景点 */
function isWeatherItem(it) {
    if (!it) return false;
    const cat = (it.category || '').trim();
    if (cat && (cat.indexOf('天气') >= 0 || cat.toLowerCase() === 'weather')) return true;
    if ((it.place || '').trim()) return false; // 有地名的景点/餐饮不误伤
    const a = (it.activity || '').trim();
    if (!a) return false;
    return /^天气\s*[:：]?/.test(a)
        || (/\d{1,2}月\d{1,2}日/.test(a) && /℃|°|晴|雨|雪|多云|阴|风/.test(a));
}

/** 概览只保留「景点轴」条目：餐饮/住宿/交通/购物等非游览类不进概览（详情页完整展示）。
 *  依据 category 关键词；category 未标时按 activity 后勤词兜底（早餐/入住/车程类） */
const NON_SIGHT_KEYWORDS = ['餐饮', '美食', '早餐', '午餐', '晚餐', '夜宵', '小吃', '住宿', '酒店', '民宿',
    '交通', '购物', '休闲', '休息', 'transfer', 'food', 'hotel', 'stay', 'shop', 'dining'];
function isSightAxis(it) {
    const cat = (it.category || '').trim();
    if (cat) {
        for (const k of NON_SIGHT_KEYWORDS) if (cat.indexOf(k) >= 0) return false;
        return true;
    }
    const act = (it.activity || '').trim();
    if (/^(早餐|午餐|晚餐|夜宵|入住|退房|夜宿|打车|乘车|车程|前往|返程|出发)/.test(act)) return false;
    return true;
}

/** 概览行：剔纯移动；同核心地点(含互相包含)合并成一行，被并行的活动描述拼进该行小字 */
function dayOverviewItems(items) {
    const out = [];
    const extra = {};
    (items || []).forEach(it => {
        if (isPureMove(it) || (it.from && !(it.place || '').trim() && !(it.activity || '').trim())) return;
        if (!isSightAxis(it)) return; // 非景点轴（餐饮/住宿/交通/购物等）不进概览
        const core = placeCore(it.place);
        if (!core) { out.push(it); return; }
        let dup = -1;
        for (let i = 0; i < out.length; i++) {
            const oc = out[i]._core || '';
            if (oc && (oc === core || oc.indexOf(core) >= 0 || core.indexOf(oc) >= 0)) { dup = i; break; }
        }
        const extraAct = planItemAct(it);
        if (dup < 0) {
            it._core = core;
            out.push(it);
        } else if ((core.length || 0) > (out[dup]._core || '').length) {
            const old = out[dup];
            extra[dup] = (extra[dup] || '') + (extra[dup] ? '；' : '') + planItemAct(old);
            it._core = core;
            out[dup] = it;
        } else {
            extra[dup] = (extra[dup] || '') + (extra[dup] ? '；' : '') + extraAct;
        }
    });
    return out.map((it, i) => { it._extraAct = extra[i] || ''; return it; });
}

/** 按当日景点数粗估徒步公里数（不用太具体：4个景点≈3km，每多2个+1km，封顶8km） */
function estWalkKm(itemCount) {
    const n = itemCount || 0;
    const km = Math.min(8, Math.max(3, 3 + Math.floor((n - 3) / 2)));
    return km;
}

/** 地名 → 高德定位链接(点击先查经纬度→直接定位;查不到降级搜索页) */
function amapLink(place) {
    const name = (place || '').trim();
    if (!name) return '';
    // 带上当前行程目的地城市,避免同名地点(如"人民公园")定位到别的城市
    const city = (currentPlanData && currentPlanData.destination) ? currentPlanData.destination.trim() : '';
    return '<a class="place-link" href="https://www.mafengwo.cn/search/q.php?q='
        + encodeURIComponent(name)
        + '" target="_blank" rel="noopener noreferrer" onclick="event.stopPropagation();gotoAmap(this.href, \'' + name.replace(/\\/g, '\\\\').replace(/'/g, "\\'") + '\', \'' + city.replace(/\\/g, '\\\\').replace(/'/g, "\\'") + '\');return false" title="在马蜂窝查看「' + name + '」介绍">'
        + escapeHtml(name) + '</a>';
}

/** 点击景点：跳马蜂窝搜索页 */
function gotoAmap(fallbackUrl, name, city) {
    window.open('https://www.mafengwo.cn/search/q.php?q=' + encodeURIComponent(name), '_blank');
}

/** 按点数生成轨迹曲线路径 */
/** 提取第 dayIdx 天（0基）的地点序列；返回 null 表示无该天 */
function dayPlaces(dayIdx) {
    if (!currentPlanData || !currentPlanData.sections) return null;
    const days = (currentPlanData.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const day = days[dayIdx];
    if (!day) return null;
    const places = [];
    for (const it of (day.items || [])) {
        const pl = (it.place || it.name || '').trim();
        if (pl && !places.includes(pl)) places.push(pl);
    }
    return { title: day.title || ('第' + (dayIdx + 1) + '天'), places: places };
}

// ★按天路线缓存：dayMapUrls[i] = 第i天(0基)的 ditu 路线链接（含全部当天景点）
// 缓存绑定行程指纹(标题+天数的hash)：切换行程后即使天数索引相同也会强制重新生成，杜绝跨行程串图
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

/**
 * ★生成第 dayIdx 天的路线图并打开（若已生成直接打开；生成中则排队等待）
 * 只传当天地点 → 每天一个链接，避开高德网页版"1起点+6途经+1终点"上限。
 */
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
    // ★点击即时反馈：按钮转圈+状态提示，避免"没反应"的几秒空隙
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
            // ★恢复时重新按id查节点(闭包里的btn可能因行程重渲染已成死节点,导致按钮卡在转圈)
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
    // 重绘轨迹
    const routeBox = document.querySelector('.route-line');
    if (routeBox) {
        routeBox.innerHTML = ''
            + '<svg class="bg" viewBox="0 0 1050 100" preserveAspectRatio="none" fill="none">'
            + '<path d="' + routePath(items.length) + '" stroke="rgba(31,174,143,.55)" stroke-width="2.4" stroke-dasharray="6 8"/></svg>'
            + '<div class="stops">'
            + items.slice(0, 6).map((it, i) => {
                const hl = i === 1 || i === items.length - 2;
                return '<div class="stop' + (hl ? ' hl' : '') + '">'
                    + '<div class="dot"></div><div class="tm">' + escapeHtml(it.time || '') + '</div>'
                    + '<div class="pl">' + amapLink(it.place || it.activity || '') + '</div>'
                    + '<div class="d">' + escapeHtml(it.cost || '') + '</div></div>';
            }).join('')
            + '</div>';
    }
    // 卡片高亮
    document.querySelectorAll('.day-card').forEach((c, i) => c.classList.toggle('active', i === di));
    // 状态副标题
    const st = document.getElementById('journeyDay');
    if (st) st.textContent = (day.title || ('第 ' + (di + 1) + ' 天')) + ' 的轨迹';
}

/** 打开某天详情弹窗 */
function openDayModal(di) {
    if (!currentPlanData) return;
    const days = (currentPlanData.sections || []).filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const day = days[di];
    if (!day) return;
    const items = (day.items || []).filter(it => it && (it.time || it.place || it.activity || it.from || it.way));
    document.getElementById('modalEm').textContent = activityIcon(day.title || '');
    document.getElementById('modalTitle').textContent = 'DAY ' + String(di + 1).padStart(2, '0') + ' · ' + (day.title || ('第 ' + (di + 1) + ' 天'));
    document.getElementById('modalSub').textContent = items.length + ' 个安排 · ' + currentPlanData.destination || '';
    document.getElementById('modalBody').innerHTML =
        items.map(it => {
            const hl = it.time && /^1[2-8]:/.test(it.time); // 午餐/下午时段高亮（近似）
            const nm = it.place || it.activity || '';
            const fb = !nm ? planItemLabel(it) : '';
            if (!nm && !fb) return '';
            const dParts = [it.activity, it.cost, it.duration, it.note].filter(Boolean).join(' · ');
            return '<div class="detail-item' + (hl ? ' hl' : '') + '">'
                + '<span class="detail-time">' + escapeHtml(it.time || '') + '</span>'
                + '<div class="detail-body"><div class="p"><span class="em">' + (nm ? activityIcon(it.activity || it.place || '') : '🚌') + '</span>'
                + (nm ? amapLink(nm) : escapeHtml(fb)) + '</div>'
                + (dParts ? '<div class="d">' + escapeHtml(dParts) + '</div>' : '') + '</div></div>';
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
    let tipsLis = '';
    sections.forEach(sec => {
        const type = sec.type || 'other';
        let lis = '';
        (sec.items || []).forEach(it => {
            const t = formatSectionItem(type, it);
            if (t) lis += '<li>' + escapeHtml(t) + '</li>';
        });
        if (!lis) return; // 无任何有效内容：整卡隐藏，防"只有标题的空壳"
        if (type === 'tips') tipsLis = lis;
        html += '<div class="plan-section ' + (STRUCT_CLASS[type] || 'sec-other') + '">'
            + '<div class="sec-title"><span class="sec-icon">' + (STRUCT_ICON[type] || '📋') + '</span><span>' + escapeHtml(sec.title || '行程详情') + '</span></div><ul>'
            + lis
            + '</ul></div>';
    });
    // F-6 实用信息兜底（tips 无任何有效内容时给通用提示，避免空卡/缺失）
    if (!tipsLis) {
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

// 弹窗遮罩点击 / Esc 关闭
document.addEventListener('click', function (e) {
    const mask = document.getElementById('modalMask');
    if (mask && e.target === mask) mask.classList.remove('show');
});
document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') document.getElementById('modalMask').classList.remove('show');
});

/* ============================================================
   打印 / 导出（最终旅行计划面板右上角按钮）
   PDF：window.print()，由 print.css 控制 A4 版式
   Word：Blob 生成 HTML 格式 .doc 文件（Word 可直接打开）
   ============================================================ */

/** 展开 / 收起导出菜单 */
function toggleExportMenu(ev) {
    if (ev && ev.stopPropagation) ev.stopPropagation();
    const menu = document.getElementById('exportMenu');
    const isOpen = menu.style.display === 'block';
    menu.style.display = isOpen ? 'none' : 'block';
    if (!isOpen) {
        // 点击页面其他位置时关闭菜单
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
    buildPrintView();   // ★构造打印视图：顶部每天概览 + 下面每天详情
    window.print();
}

/** 构造打印视图内容：顶部=每天概览(与生成一致)，下面=每天详情(弹窗内容) */
function openTripCalendar() {
    const plan = currentPlanData || {};
    const days = Math.min(30, Math.max(1, Number(plan.days) || 1));
    const start = parsePlanDate(plan.date) ? new Date(parsePlanDate(plan.date)) : new Date();
    start.setHours(0, 0, 0, 0);
    calRange = { start: start, days: days };
    // 视图钳制：行程起始日早于本月 → 从本月开始看（范围外日期已置灰不可点）
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
    // ★修复：仅当用户点了新出发日才算改期；纯打开再关闭（或点的还是原出发日）→ 不重算格式、不写库
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

    // 视图越界（早于本月1日 / 晚于两年后）禁止翻到
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
                // 点任意日期 → 从该日起按行程天数重新高亮
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
    // "8月27日-29日"区间格式：无年份，取区间首日；跨年风险场景（12月→次���1月）按出发年=当前年推断
    const range = String(value).match(/(\d{1,2})月(\d{1,2})日-(\d{1,2})月(\d{1,2})日/);
    if (range) {
        const now = new Date();
        return localDateString(new Date(now.getFullYear(), Number(range[1]) - 1, Number(range[2])));
    }
    // "8月27日"单日格式
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
    // "27日-29日"：结束月缺省=开始月；"27日-1月2日"：结束月显式给出（跨月/跨年）
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
    if (/^\|.+\|$/.test(s)) return ''; // 整行管道符表格结构 → 无有效天气内容
    return s;
}

function applyTripDateChange(newDate) {
    // 1) 立即刷新主界面日期徽章 + 打印视图（同一数据源）
    const chip = document.querySelector('.plan-meta .date-chip'); // ★修复：定位可见徽章(#printView 里也有同名副本，getElementById 会拿错)
    if (chip) chip.textContent = '📅 ' + newDate;
    if (typeof buildPrintView === 'function') buildPrintView(); // 打印/PDF 导出用的隐藏视图
    lastResultMarkdown = (stripPlanJson(lastResultMarkdown) + '\n\n出行的日期是 ' + newDate + '。').trim(); // markdown 摘要同步
    // 2) 持久化（历史行程有ID走后端保存；新生成未保存的行程只更新本地，保存时自然带上）
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

/* ============ ★预测弹窗：行程头部「📈 价格·客流预测」按钮 ============ */

/** 打开预测弹窗：展示该城市价格预测 + 未来客流趋势（原顶部自动浮出的参考卡收纳为此弹窗） */
function openInsightModal() {
    const plan = currentPlanData;
    if (!plan || !plan.destination) return;
    const city = String(plan.destination).trim();
    document.getElementById('modalEm').textContent = '📈';
    document.getElementById('modalTitle').textContent = city + ' · 价格与客流预测';
    document.getElementById('modalSub').textContent = '城市级预测 · 仅供参考';
    document.getElementById('modalBody').innerHTML = '<div style="padding:28px;text-align:center;color:var(--muted)">预测数据加载中…</div>';
    document.getElementById('modalMask').classList.add('show');

    // ★客流预测固定查「未来 7 天」（数据管道窗口=今天+1 起 7 天），与行程日期无关；定价不依赖日期
    const startDate = localDateString(new Date(Date.now() + 86400e3));
    const params = new URLSearchParams({ city: city, startDate: startDate, days: '7' });
    fetch('/api/insights?' + params.toString(), { headers: { 'Accept': 'application/json' } })
        .then(r => r.json())
        .then(data => {
            const body = document.getElementById('modalBody');
            if (!data || !data.available) {
                body.innerHTML = '<div class="state-box" style="padding:34px"><div class="big">📭</div>暂无「' + escapeHtml(city) + '」的预测数据<br><span style="font-size:12px;color:var(--muted)">该城市近期未纳入数据统计（预测窗口已过或未重算），可稍后重试</span></div>';
                return;
            }
            renderInsightModalBody(body, data);
        })
        .catch(() => {
            const body = document.getElementById('modalBody');
            body.innerHTML = '<div class="state-box" style="padding:34px">预测数据加载失败，请稍后重试</div>';
        });
}

/** 渲染预测弹窗正文：价格预测表（基准→预测价+浮动）+ 未来客流柱状 */
function renderInsightModalBody(bodyEl, data) {
    const pricing = data.pricing || {};
    const forecast = Array.isArray(data.forecast) ? data.forecast : [];
    let html = '';

    // —— 价格预测 ——
    html += '<div style="font-weight:700;font-size:14px;margin:2px 0 8px;color:var(--ink)">💴 价格预测 <span style="font-weight:400;font-size:11.5px;color:var(--muted)">动态定价 = 基准价 × 城市热力系数</span></div>';
    const pRows = [['hotel', '酒店（晚）'], ['food', '餐饮（餐）'], ['ticket', '门票（人）']];
    const rows = pRows.map(pair => {
        const p = pricing[pair[0]] || {};
        const base = Number(p.base), price = Number(p.price);
        if (!Number.isFinite(base) || !Number.isFinite(price) || base <= 0) return '';
        const diff = price / base;
        const up = diff >= 1.001, down = diff <= 0.999;
        const cls = up ? 'up' : down ? 'down' : 'flat';
        const arrow = up ? '▲' : down ? '▼' : '＝';
        return '<tr><td>' + pair[1] + '</td><td>' + formatCny(base) + '</td><td><b>' + formatCny(price) + '</b></td>' +
            '<td><span class="pf-badge ' + cls + '">' + arrow + ' ' + Math.abs(diff * 100 - 100).toFixed(0) + '%</span></td></tr>';
    }).join('');
    if (rows) {
        html += '<table class="pf-tbl"><thead><tr><th>项目</th><th>基准价</th><th>预测价</th><th>浮动</th></tr></thead><tbody>' + rows + '</tbody></table>';
    } else {
        html += '<div class="state-box" style="padding:20px">暂无定价数据</div>';
    }

    // —— 未来客流 ——
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

function newSession() {
    // 重置为全新会话：清掉旧 sessionId 关联的事件/结果，防上次任务残留
    sessionId = 's' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    localStorage.setItem('atp_session_id', sessionId); // ★同步持久化：切页/刷新后 sessionId 与提交标记一致，恢复逻辑才能命中
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
    const sections = plan.sections || [];
    const days = sections.filter(s => s.type === 'timeline' || /第.+天/.test(s.title || ''));
    const others = sections.filter(s => s.type !== 'timeline' && !/第.+天/.test(s.title || ''));

    let html = '';
    // 标题头
    html += '<div class="pv-header">';
    html += '<h1>' + escapeHtml(plan.title || '旅行规划') + '</h1>';
    html += '<div class="pv-meta">';
    if (plan.days) html += '<span>🗓 行程天数：<b>' + plan.days + '</b> 天</span>';
    if (plan.date) html += '<span class="date-chip" style="cursor:pointer" title="点击查看日历" onclick="openTripCalendar()">📅 ' + escapeHtml(plan.date) + '</span>';
    const wth = cleanWeatherText(plan.weather);
    if (wth) html += '<span>🌤 ' + escapeHtml(wth) + '</span>';
    html += '</div></div>';

    // ===== 第一部分：每天概览（轨迹点 + 天卡片内容，与生成时一致） =====
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

    // ===== 第二部分：每天详情（弹窗完整内容） =====
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

    // 交通/住宿/贴士等其余区块
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

    // ★新结构：用 currentPlanData 生成一份干净的旧式 plan-sheet 供导出（避免轨迹/卡片结构进 Word）
    let sheet = document.querySelector('#resultBody .plan-sheet');
    if ((!sheet || !sheet.querySelector('.sec-title')) && currentPlanData) {
        const tmp = document.createElement('div');
        tmp.innerHTML = renderLegacySheet(currentPlanData); // 生成纯 plan-sheet 结构
        sheet = tmp.querySelector('.plan-sheet') || tmp;
    }
    const titleNode = sheet ? sheet.querySelector('.plan-title') : null;
    const title = (titleNode ? titleNode.textContent : '旅行计划').trim() || '旅行计划';

    // Word 内嵌样式：保证用 Word 打开时排版正确
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

    // 组装 Word 可打开的 HTML 文档（加 \ufeff BOM 保证中文编码正确）
    const doc = '<html xmlns:o="urn:schemas-microsoft-com:office:office" ' +
        'xmlns:w="urn:schemas-microsoft-com:office:word" ' +
        'xmlns="http://www.w3.org/TR/REC-html40">' +
        '<head><meta charset="UTF-8"><title>' + escapeHtml(title) + '</title>' +
        '<style>' + wordCss + '</style></head>' +
        '<body>' + sheet.outerHTML + '</body></html>';

    const blob = new Blob(['\ufeff', doc], { type: 'application/msword' });
    // 文件名：旅行计划_标题（去除非法字符）
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

/* ============================================================
   ★打印/导出按钮自动显隐
   结果区存在可导出的计划内容时显示按钮，否则隐藏
   （监听 resultBody 内容变化：fetchResult 渲染 / viewPlan 加载历史行程都会触发）
   ============================================================ */
function initPrintButton() {
    const body = document.getElementById('resultBody');
    if (!body) return;

    const update = function () {
        // 操作条(打印/导出 + 保存):结果区有内容才显示
        const saveBar = document.getElementById('saveBar');
        const text = (body.textContent || '').trim();
        const has = text && !text.includes('正在生成') && !text.includes('等待生成') && !body.querySelector('.gen-progress') && !text.includes('（空响应）');
        if (saveBar) saveBar.style.display = has ? 'flex' : 'none';
    };

    // 监听结果区内容变化，实时控制按钮显隐
    const observer = new MutationObserver(update);
    observer.observe(body, { childList: true, subtree: true, characterData: true });

    // 页面加载时初始化一次
    update();
}
initPrintButton();
