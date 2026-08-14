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
let lastResultJson = '';      // 最近一次最终结果的结构化 JSON 原文（保存行程用，供分享/二次编辑）

// ★会话隔离：每个窗口独立 sessionId（localStorage 持久），请求带上它避免多窗口互扰
const sessionId = (function () {
    let sid = localStorage.getItem('atp_session_id');
    if (!sid) {
        sid = 's' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
        localStorage.setItem('atp_session_id', sid);
    }
    return sid;
})();

// ===== ★ 登录状态检查（页面加载时执行） =====
function checkLogin() {
    fetch('/api/check-login')
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                document.getElementById('userName').textContent = '👤 ' + data.username;
                document.getElementById('myPlansBtn').style.display = 'inline-block';
                document.getElementById('forumBtn').style.display = 'inline-block';
                document.getElementById('profileBtn').style.display = 'inline-block';
                document.getElementById('logoutBtn').style.display = 'inline-block';
                document.getElementById('skipPrefsWrap').style.display = 'inline-block';
                // ★管理员额外显示「管理端」入口
                if (data.role === 'admin') {
                    document.getElementById('adminBtn').style.display = 'inline-block';
                }
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
function savePlan() {
    const text = lastResultMarkdown.trim();
    if (!text || text.includes('正在生成') || text.includes('等待生成')) {
        document.getElementById('saveMsg').textContent = '暂无结果可保存';
        return;
    }
    // 从结果里提取城市和天数（简单取第一个关键词前的文本，容错处理）
    const prompt = document.getElementById('prompt').value.trim();
    const city = prompt.replace(/帮我制定|制定|计划|规划|3日游|2日游|1日游|自驾游|游/g, '').trim() || '未知城市';
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
        body: new URLSearchParams({ city: city, days: days, planContent: text, planJson: lastResultJson })
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
                    (p.plan_content || '').replace(/<[^>]+>/g, ' ').slice(0, 200) + '...' +
                    '</div>' +
                    '<div class="plan-card-actions">' +
                    '<button class="mini-btn" onclick="viewPlan(' + p.id + ')">查看</button> ' +
                    '<button class="mini-btn" onclick="location.href=\'edit.html?id=' + p.id + '\'">编辑</button> ' +
                    '<button class="mini-btn" onclick="sharePlan(' + p.id + ')">分享</button> ' +
                    '<button class="mini-btn" onclick="deletePlan(' + p.id + ')">删除</button>' +
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
                // ★第0步：优先用结构化 JSON 渲染（供分享/二次编辑），缺失时退回 markdown/HTML
                let structured = null;
                if (p.plan_json) {
                    try {
                        structured = typeof p.plan_json === 'string' ? JSON.parse(p.plan_json) : p.plan_json;
                    } catch (e) { structured = null; }
                }
                if (structured && (structured.sections || structured.title)) {
                    lastResultJson = typeof p.plan_json === 'string' ? p.plan_json : JSON.stringify(structured);
                    document.getElementById('resultBody').innerHTML = renderStructuredPlan(structured);
                    document.getElementById('saveBar').style.display = 'none';
                    closeMyPlans();
                    document.getElementById('resultBody').scrollIntoView({ behavior: 'smooth' });
                    return;
                }
                let content = p.plan_content || '';
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
                document.getElementById('saveBar').style.display = 'none';
                closeMyPlans();
                document.getElementById('resultBody').scrollIntoView({ behavior: 'smooth' });
            }
        })
        .catch(() => { });
}

// 删除某条行程
function deletePlan(id) {
    if (!confirm('确定删除这条行程吗？')) return;
    fetch('/api/delete-plan?id=' + id, { method: 'POST' })
        .then(r => r.json())
        .then(() => { showMyPlans(); })
        .catch(() => { });
}

// ★P0：分享行程（生成/刷新只读分享链接）
function sharePlan(id) {
    fetch('/api/trips/' + id + '/share', { method: 'POST' })
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                const url = location.origin + data.url;
                const msg = '分享链接（免登录可看）：\n' + url;
                const copy = confirm(msg + '\n\n点击「确定」复制链接到剪贴板？');
                if (copy) {
                    navigator.clipboard.writeText(url).then(() => {
                        document.getElementById('saveMsg').textContent = '✅ 分享链接已复制';
                    }).catch(() => { });
                }
            } else {
                alert('分享失败：' + data.message);
            }
        })
        .catch(() => { alert('分享失败，请重试'); });
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
    if (polling) { clearInterval(polling); polling = null; }
    if (eventSource) { eventSource.close(); eventSource = null; }
    if (resultTimer) { clearInterval(resultTimer); resultTimer = null; }

    // 重置界面
    document.getElementById('thinkBody').innerHTML = '';
    document.getElementById('resultBody').innerHTML = '<div class="empty">正在生成...</div>';
    document.getElementById('status').textContent = '⏳ 任务提交中...';
    document.getElementById('status').classList.add('show');
    document.getElementById('sendBtn').disabled = true;

    // ① 提交任务（后端立即返回，不占连接）★带sessionId会话隔离 + 本次不使用偏好开关
    const skipPrefs = document.getElementById('skipPrefs') ? document.getElementById('skipPrefs').checked : false;
    fetch('/app?prompt=' + encodeURIComponent(prompt) + '&sessionId=' + encodeURIComponent(sessionId)
        + '&skipPrefs=' + skipPrefs, { method: 'POST' })
        .then(r => r.text())
        .then(msg => {
            // 并发防护提示
            if (msg.includes('有任务正在执行')) {
                document.getElementById('resultBody').innerHTML =
                    '<div style="color:#e17055">⚠️ ' + msg + '</div>';
                document.getElementById('status').textContent = '⚠️ 任务未提交';
                document.getElementById('sendBtn').disabled = false;
                if (polling) { clearInterval(polling); polling = null; }
                if (resultTimer) { clearInterval(resultTimer); resultTimer = null; }
                return;
            }
            document.getElementById('status').textContent = '🧠 主管Agent正在规划中，请稍候...';
            // 显示"停止"按钮（任务执行中可中断）
            document.getElementById('cancelBtn').style.display = 'inline-block';
            // 服务端已清空事件，从0开始增量显示
            lastEventCount = 0;
            // ② SSE 实时事件流（阶段四 F-3，替代 /events 轮询）
            if (eventSource) eventSource.close();
            lastEventCount = 0;
            eventSource = new EventSource('/events/stream?sessionId=' + encodeURIComponent(sessionId));
            eventSource.addEventListener('event', function (e) {
                try { appendThinkItem(JSON.parse(e.data)); } catch (err) { /* 忽略非法数据 */ }
            });
            // ③ 轮询 /result（拿最终结果）
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

/**
 * ★中断任务：调用 /cancel 设置取消标志，主管Agent在下一个事件点停止
 * 不再等任务排队——中断后 RUNNING 释放，可以马上提交新任务
 */
function cancelTask() {
    if (!confirm('确定要停止当前任务吗？')) return;
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

/**
 * 拉取最终结果（/result 返回非"执行中" = 完成）
 */
function fetchResult() {
    fetch('/result?sessionId=' + encodeURIComponent(sessionId))
        .then(r => r.text())
        .then(text => {
            if (text.includes('还在执行中')) return;  // 没完成，继续等

            // 完成！渲染为 A4 行程规划表
            clearInterval(resultTimer); resultTimer = null;
            if (eventSource) { eventSource.close(); eventSource = null; }
            if (polling) { clearInterval(polling); polling = null; }
            document.getElementById('cancelBtn').style.display = 'none'; // 隐藏停止按钮

            // ★阶段三：优先按结构化 JSON 渲染；旧 markdown / 错误文本走降级路径
            let structured = null;
            try { structured = JSON.parse(text); } catch (e) { /* 非 JSON：按原文处理 */ }
            if (structured && (structured.sections || structured.title)) {
                lastResultMarkdown = structured.markdown || ''; // 记录降级 markdown，供保存
                lastResultJson = text; // 记录结构化 JSON 原文，供保存行程（分享/二次编辑）
                document.getElementById('resultBody').innerHTML = renderStructuredPlan(structured);
            } else {
                lastResultMarkdown = text; // 记录原文，供保存行程使用
                lastResultJson = ''; // 非结构化结果，不保存 JSON
                document.getElementById('resultBody').innerHTML = renderPlanSheet(text);
            }
            document.getElementById('status').textContent = '✅ 计划生成完成！';
            document.getElementById('sendBtn').disabled = false;
            // ★ 结果出来了，显示"保存到我的行程"按钮
            document.getElementById('saveBar').style.display = 'block';
            document.getElementById('saveMsg').textContent = '';
        })
        .catch(() => { /* 网络抖动，下轮重试 */ });
}

/**
 * 追加一条思考过程事件到界面（SSE 与轮询共用）
 */
function appendThinkItem(ev) {
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
function activityIcon(text) {
    if (/景点|游览|参观|游玩|观光|乐园|公园|博物馆|古镇|风景区|爬山|海滩/.test(text)) return '🎡';
    if (/早餐|午餐|晚餐|夜宵|餐饮|美食|餐厅|小吃|品尝/.test(text)) return '🍜';
    if (/地铁|公交|交通|驾车|步行|打车|高铁|火车|飞机|机场|码头|船|换乘|线路/.test(text)) return '🚇';
    if (/酒店|住宿|入住|民宿|旅馆|客栈/.test(text)) return '🏨';
    if (/门票|票价|费用|价格|消费|¥|￥|元/.test(text)) return '🎫';
    if (/电话|紧急|求助|报警|急救|联系/.test(text)) return '📞';
    if (/天气|气温|温度|℃/.test(text)) return '🌤️';
    return '';
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
            return pick(item.name, item.kind, item.price);
        case 'tips':
            return (item.kind ? item.kind + '：' : '') + (item.text || '');
        default:
            return pick(item.activity, item.place, item.text, item.name);
    }
}

/** 将结构化 TripPlanData 渲染为 A4 行程规划表 HTML */
function renderStructuredPlan(plan) {
    if (!plan) return '<div class="empty">（空响应）</div>';
    const sheet = document.createElement('div');
    sheet.className = 'plan-sheet';

    // 标题区
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

    // 分区块
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
            li.textContent = formatSectionItem(type, item);
            list.appendChild(li);
        });
        secDiv.appendChild(list);
        sheet.appendChild(secDiv);
    });

    // ★F-6：实用信息区兜底，永不为空（紧急电话 + 通用行李清单）
    if (!sections.some(s => s.type === 'tips' && (s.items || []).length > 0)) {
        const tipsSec = document.createElement('div');
        tipsSec.className = 'plan-section sec-tips';
        const st = document.createElement('div');
        st.className = 'sec-title';
        st.textContent = '📌 实用信息';
        tipsSec.appendChild(st);
        const list = document.createElement('ul');
        const tips = [
            '电话：报警 110 / 急救 120 / 火警 119',
            '电话：政务服务热线 12345',
            '提示：出行前确认天气与景区开放时间',
            '行李：身份证、手机充电宝、常用药品、防晒用品'
        ];
        tips.forEach(t => { const li = document.createElement('li'); li.textContent = t; list.appendChild(li); });
        tipsSec.appendChild(list);
        sheet.appendChild(tipsSec);
    }

    // 结构化为空时退回 markdown 兜底
    if (sections.length === 0) {
        const fallback = document.createElement('div');
        fallback.className = 'plan-section sec-other';
        fallback.innerHTML = plan.markdown ? renderMarkdown(plan.markdown) : '<div class="empty">（空响应）</div>';
        sheet.appendChild(fallback);
    }

    return sheet.outerHTML;
}

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

/** 导出 PDF：调用浏览器打印对话框（print.css 控制 A4 版式） */
function exportPDF(ev) {
    if (ev && ev.stopPropagation) ev.stopPropagation();
    closeExportMenu();
    if (!hasExportablePlan()) return;
    window.print();
}

/** 导出 Word：Blob 生成 HTML 格式 .doc 文件 */
function exportWord(ev) {
    if (ev && ev.stopPropagation) ev.stopPropagation();
    closeExportMenu();
    if (!hasExportablePlan()) return;

    const sheet = document.querySelector('#resultBody .plan-sheet');
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
    const actions = document.querySelector('.panel-result .panel-actions');
    if (!body || !actions) return;

    const update = function () {
        const text = (body.textContent || '').trim();
        const has = text && !text.includes('正在生成') && !text.includes('等待生成') && !text.includes('（空响应）');
        actions.style.display = has ? 'flex' : 'none';
    };

    // 监听结果区内容变化，实时控制按钮显隐
    const observer = new MutationObserver(update);
    observer.observe(body, { childList: true, subtree: true, characterData: true });

    // 页面加载时初始化一次
    update();
}
initPrintButton();
