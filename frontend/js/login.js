/* ============================================================
   login.js — 登录页（login.html）全部前端逻辑
   由原 login.html 内联 <script> 拆分而来，功能完全不变：
   登录 / 注册 Tab 切换、回车提交、调用 /api/login 与 /api/register
   ============================================================ */

// 当前模式：login / register
let mode = 'login';

function switchTab(t) {
    mode = t;
    document.getElementById('tabLogin').className = 'tab' + (t === 'login' ? ' active' : '');
    document.getElementById('tabRegister').className = 'tab' + (t === 'register' ? ' active' : '');
    document.getElementById('btnSubmit').textContent = t === 'login' ? '登 录' : '注 册';
    document.getElementById('msg').textContent = '';
    document.getElementById('msg').className = 'msg';
}

// 回车提交
document.getElementById('password').addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });

function submit() {
    const username = document.getElementById('username').value.trim();
    const password = document.getElementById('password').value;
    const msg = document.getElementById('msg');
    const btn = document.getElementById('btnSubmit');

    if (!username || !password) {
        msg.textContent = '请输入用户名和密码';
        msg.className = 'msg err';
        return;
    }
    if (mode === 'register' && password.length < 6) {
        msg.textContent = '密码至少6位';
        msg.className = 'msg err';
        return;
    }

    btn.disabled = true;
    const url = mode === 'login' ? '/api/login' : '/api/register';
    fetch(url + '?username=' + encodeURIComponent(username) + '&password=' + encodeURIComponent(password),
          { method: 'POST' })
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                if (mode === 'login') {
                    msg.textContent = '✅ 登录成功，正在进入...';
                    msg.className = 'msg ok';
                    // ★管理员登录后进入后台管理页，普通用户进入前台
                    const target = (data.role === 'admin') ? 'admin.html' : 'index.html';
                    setTimeout(() => { location.href = target; }, 600);
                } else {
                    msg.textContent = '✅ 注册成功，请登录';
                    msg.className = 'msg ok';
                    switchTab('login');
                    document.getElementById('password').value = '';
                }
            } else {
                msg.textContent = '❌ ' + data.message;
                msg.className = 'msg err';
            }
            btn.disabled = false;
        })
        .catch(() => {
            msg.textContent = '❌ 网络错误，请重试';
            msg.className = 'msg err';
            btn.disabled = false;
        });
}
