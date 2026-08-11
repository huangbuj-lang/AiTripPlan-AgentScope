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
let polling = null;        // 轮询定时器（/events）
let resultTimer = null;    // 结果轮询定时器（/result）
let knownCount = 0;        // 已显示的事件数（保留，向后兼容）
let lastEventCount = 0;    // 发送前的事件总数（用于增量显示）

// ===== ★ 登录状态检查（页面加载时执行） =====
function checkLogin() {
    fetch('/api/check-login')
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                document.getElementById('userName').textContent = '👤 ' + data.username;
                document.getElementById('myPlansBtn').style.display = 'inline-block';
                document.getElementById('logoutBtn').style.display = 'inline-block';
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
    const text = document.getElementById('resultBody').textContent.trim();
    if (!text || text.includes('正在生成') || text.includes('等待生成')) {
        document.getElementById('saveMsg').textContent = '暂无结果可保存';
        return;
    }
    // 从结果里提取城市和天数（简单取第一个关键词前的文本，容错处理）
    const prompt = document.getElementById('prompt').value.trim();
    const city = prompt.replace(/帮我制定|制定|计划|规划|3日游|2日游|1日游|自驾游|游/g, '').trim() || '未知城市';
    const daysMatch = prompt.match(/(\d+)日/);
    const days = daysMatch ? parseInt(daysMatch[1]) : 3;
    // 保存的是完整的markdown渲染HTML（保留格式）
    const html = document.getElementById('resultBody').innerHTML;

    // ★修复：用POST body传内容（原URL方式：行程全文塞进URL会超Tomcat 8KB限制→400保存失败）
    fetch('/api/save-plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ city: city, days: days, planContent: html })
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
                    '<b>🏙️ ' + (p.city || '未知城市') + ' · ' + (p.days || 3) + '日游</b>' +
                    '<span class="plan-card-time">' + (p.created_at || '') + '</span>' +
                    '</div>' +
                    '<div class="plan-card-preview">' +
                    (p.plan_content || '').replace(/<[^>]+>/g, ' ').slice(0, 200) + '...' +
                    '</div>' +
                    '<div class="plan-card-actions">' +
                    '<button class="mini-btn" onclick="viewPlan(' + p.id + ')">查看</button> ' +
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
                let content = p.plan_content || '';
                // 兼容旧数据：没有 plan-sheet 包裹的纯 HTML，套一层行程单容器
                if (!/class="[^"]*\bplan-sheet\b/.test(content)) {
                    content = '<div class="plan-sheet"><div class="plan-body">' + content + '</div></div>';
                }
                document.getElementById('resultBody').innerHTML = content;
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
    if (resultTimer) { clearInterval(resultTimer); resultTimer = null; }

    // 重置界面
    document.getElementById('thinkBody').innerHTML = '';
    document.getElementById('resultBody').innerHTML = '<div class="empty">正在生成...</div>';
    document.getElementById('status').textContent = '⏳ 任务提交中...';
    document.getElementById('status').classList.add('show');
    document.getElementById('sendBtn').disabled = true;

    // ① 提交任务（后端立即返回，不占连接）
    fetch('/app?prompt=' + encodeURIComponent(prompt), { method: 'POST' })
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
            // 服务端已清空事件，从0开始增量显示
            lastEventCount = 0;
            // ② 轮询 /events（唯一连接，实时思考过程）
            if (polling) clearInterval(polling);
            polling = setInterval(pollEvents, 1500);
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
 * 拉取最终结果（/result 返回非"执行中" = 完成）
 */
function fetchResult() {
    fetch('/result')
        .then(r => r.text())
        .then(text => {
            if (text.includes('还在执行中')) return;  // 没完成，继续等

            // 完成！渲染为 A4 行程规划表
            clearInterval(resultTimer); resultTimer = null;
            if (polling) { clearInterval(polling); polling = null; }
            document.getElementById('resultBody').innerHTML = renderPlanSheet(text);
            document.getElementById('status').textContent = '✅ 计划生成完成！';
            document.getElementById('sendBtn').disabled = false;
            // ★ 结果出来了，显示"保存到我的行程"按钮
            document.getElementById('saveBar').style.display = 'block';
            document.getElementById('saveMsg').textContent = '';
        })
        .catch(() => { /* 网络抖动，下轮重试 */ });
}

/**
 * 轮询事件接口，增量显示思考过程
 */
function pollEvents() {
    fetch('/events')
        .then(r => r.json())
        .then(evts => {
            // 只取本次运行的新事件
            const fresh = evts.slice(lastEventCount);
            if (fresh.length === 0) return;

            const body = document.getElementById('thinkBody');
            // 移除空状态提示
            const empty = body.querySelector('.empty');
            if (empty) empty.remove();

            fresh.forEach(ev => {
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
            });

            // 自动滚动到底部
            body.scrollTop = body.scrollHeight;
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
    let html = '<table class="plan-table"><thead><tr>';
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
   A4 打印风格行程规划表（按 md 文档区块渲染）
   区块分类：概览 / 时间表 / 交通 / 住宿餐饮 / 实用信息
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
    const weatherMatch = text.match(/天气[\s:：]*([^\n]{2,24})/);
    if (weatherMatch) {
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
