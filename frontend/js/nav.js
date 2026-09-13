/**
 * ★地点跳转与「去携程/飞猪搜索」按钮（新增文件，不改任何既有逻辑）
 * ── 用户口径：
 *   ① 点地点名（概览/轨迹/每日卡片）→ 跳【高德】，实现一键导航（不再跳马蜂窝）
 *   ② 酒店 / 餐饮 卡片里要有「去携程搜索」「去飞猪搜索」两个按钮，不带任何价格
 * ── 做法：坐标由后端新接口 /api/amap/poi-locations 提供（复用现有高德检索），
 *   拿不到坐标就退回高德搜索页，绝不编造坐标。
 */
(function () {
  'use strict';

  var coordCache = {};          // key: 名称|城市 → "lng,lat"
  var poiIdCache = {};          // key: 名称|城市 → 高德 POI 编号（拼地点详情页用）
  var pending = {};             // 正在查的 key
  var lastFp = '';              // 上次预取过的行程指纹，避免重复请求
  var CTX = { city: '' };

  function key(name, city) { return String(name || '').replace(/\s+/g, '') + '|' + String(city || '').replace(/\s+/g, ''); }

  /** 高德导航链接（有坐标 = 真一键导航；没坐标 = 高德搜索页，用户点一下也能导航） */
  /**
   * ★用户口径（最终）：所有跳转统一到高德的【地点详情页】——
   *   https://amap.com/ssr/place/<POI编号>?id=<编号>&name=<店名>&source=sug
   *   编号由后端 /api/amap/poi-locations 从高德真实返回里带回来（绝不编造）。
   *   编号还没取到时先返回搜索页占位，点击路径（openNav）会先把编号查回来再打开。
   */
  function url(name, city) {
    var n = String(name || '').trim();
    if (!n) return '';
    //★用户口径（2026-09-13 最终）：统一跳【高德搜索页】——用他能正常显示的那个形式
    //  https://ditu.amap.com/search?query=<地名>
    //  注意不能用 www.amap.com/search?...（会被高德重定向成 &src=uriapi 的形式 → 搜索框是空的 ✗ 实测）
    //★用户口径：搜索框里要有【城市前缀】（否则同名地点会跑到别的城市 ✗）
    //  用"城市 地点"的形式；city 为空时退化为纯地名
    var c = String(city || '').trim();
    var q = (c && n.indexOf(c) < 0) ? (c + ' ' + n) : n;
    return 'https://ditu.amap.com/search?query=' + encodeURIComponent(q);
  }


  /** 批量取坐标（幂等、去重、失败静默） */
  function load(places, cities) {
    try {
      var want = [], seen = {};
      (places || []).forEach(function (p) {
        var n = String((p && p.name) || p || '').trim();
        if (!n || n.length > 30) return;
        var c = (p && p.city) || CTX.city || '';
        var k = key(n, c);
        if (coordCache[k] || pending[k] || seen[k]) return;
        seen[k] = 1;
        want.push({ name: n, city: c });
      });
      if (!want.length) return;
      want.slice(0, 48).forEach(function (w) { pending[key(w.name, w.city)] = 1; });
      fetch('/api/amap/poi-locations', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ places: want.slice(0, 48), cities: cities || (CTX.city ? [CTX.city] : []) })
      }).then(function (r) { return r.json(); }).then(function (d) {
        ((d && d.items) || []).forEach(function (it) {
          var k = key(it.name, it.city);
          delete pending[k];
          if (it.location) coordCache[k] = it.location;
          if (it.poiId) poiIdCache[k] = it.poiId;
        });
      }).catch(function () {
        want.forEach(function (w) { delete pending[key(w.name, w.city)]; });
      });
    } catch (e) { /* 失败不影响页面 */ }
  }

  /** 从结果区抓地点名（只读 DOM，不改任何渲染逻辑） */
  function collectFromDom() {
    var out = [], seen = {};
    try {
      Array.prototype.slice.call(document.querySelectorAll('#resultBody .place-link, #resultBody .poi-name')).forEach(function (el) {
        var t = (el.textContent || '').replace(/[★\s].*$/, '').trim();
        if (!t || t.length > 30 || seen[t]) return;
        seen[t] = 1;
        out.push({ name: t, city: CTX.city });
      });
    } catch (e) { }
    return out;
  }

  function fpOf() {
    var rb = document.getElementById('resultBody');
    return rb ? String(rb.innerHTML.length) + '|' + (rb.textContent || '').slice(0, 120) : '';
  }

  /** 结果区出现新行程 → 预取一次坐标（每个行程只预取一次） */
  function watchPlan() {
    var fp = fpOf();
    if (!fp || fp === lastFp) return;
    lastFp = fp;
    var ps = collectFromDom();
    if (ps.length) load(ps, CTX.city ? [CTX.city] : []);
  }

  /** 给酒店/餐饮卡片补两个搜索按钮（只加按钮，不动卡片既有内容） */
  function decorateCard() {
    try {
      var body = document.getElementById('modalBody');
      var mask = document.getElementById('modalMask');
      if (!body || !mask || !mask.classList.contains('show')) return;
      //★只有【点酒店/餐厅弹出来的单点卡片】才加这两个按钮：
      //  当日详细规划弹窗用的是同一个容器（#modalBody），必须先排除掉（它有 .detail-item 清单）
      if (body.querySelector && body.querySelector('.detail-item')) return;
      if (!/酒店|民宿|客栈|宾馆|餐厅|餐饮|美食|小吃|饭店|面|火锅|食府|咖啡|烤肉|烧烤/.test(body.textContent || '')) return;
      //★只判断【当前这张卡片】里有没有，不能用全文档查（关掉上一张后旧节点还留着 → 后面每张都会被挡掉，按钮时有时无）
      if (body.querySelector('#navSearchBox')) return;
      var title = (document.getElementById('modalTitle') || {}).textContent || '';
      if (!title) return;
      var city = CTX.city || '';
      //★用户口径：酒店卡片 → 携程/飞猪；餐饮卡片 → 换成【高德】（携程/飞猪没有餐厅搜索）
      var sub = ((document.getElementById('modalSub') || {}).textContent || '');
      var isFood = /餐饮|美食|小吃|餐厅|饭店|食堂/.test(sub) || /餐厅|饭店|食府|小吃|面馆|火锅|烧烤|咖啡|奶茶/.test(title);
      //★用户口径：卡片不再使用（酒店/餐饮点击直接跳高德搜索页）→ 卡片里不再出现按钮

    } catch (e) { }
  }
  var BTN = 'display:inline-flex;align-items:center;gap:6px;padding:7px 14px;border-radius:9999px;'
    + 'background:var(--green-600,#3A6149);color:#fff;font-size:12.5px;text-decoration:none;font-weight:600';

  /** 启动：监听结果区与弹窗（MutationObserver，不改任何既有函数） */
  function boot() {
    try {
      var t = document.getElementById('prompt');
      if (t) CTX.city = '';
      var rb = document.getElementById('resultBody');
      if (rb) new MutationObserver(function () { watchPlan(); decorateCard(); }).observe(rb, { childList: true, subtree: true });
      var mask = document.getElementById('modalMask');
      if (mask) new MutationObserver(decorateCard).observe(mask, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
      watchPlan();
    } catch (e) { }
  }


  /**
   * ★兜底点击：任何地点名（.place-link）被点击 → 一律打开【高德】。
   *   用捕获阶段拦在最前面，不依赖既有 onclick 是否被改过/是否被其他脚本动过，
   *   也不碰 .poi-name（那是酒店/餐饮弹卡片的，不能抢）。
   *   被浏览器拦弹窗时退化为本页跳转，绝不"点了没反应"。
   */
  function bootClickFallback() {
    //★补样式：每天卡片里每一行 + 轨迹里每个点的地点名，都给虚线（看起来就能点）
    try {
      var st = document.createElement('style');
      st.textContent = ''
        + 'a.place-link { border-bottom:1px dashed rgba(58,97,73,.55); }'
        //★用户口径：只修【概览卡片】——卡片的每一行都要有虚线、都能点；轨迹与其他一律不动
        + '.day-card .item .pl { border-bottom:1px dashed rgba(58,97,73,.55); cursor:pointer; }'
        + '.day-card .item .pl .poi-name { border-bottom:0; }';
      document.head.appendChild(st);
    } catch (e) { }

    document.addEventListener('click', function (ev) {
      try {
        var el = ev.target;
        // 从点击处往上找「一行地点」：轨迹的 .stop 或 日卡片的 .item
        var row = null, t = el;
        while (t && t !== document.body) {
          if (t.classList && (t.classList.contains('stop') || t.classList.contains('item'))) { row = t; break; }
          t = t.parentElement;
        }
        if (!row || !row.closest || !row.closest('#resultBody')) return;
        // 酒店/餐饮那行有自己的弹卡片逻辑（.poi-name）→ 不抢
        //★用户口径：只修【概览卡片】里点不了的行 ——
        //  点了店名(.poi-name) → 不插手（照旧弹卡片）；
        //  点其他文字 → 跳高德（本来就是链接的行也不用管，它自己会走）
        var inCard = row.classList.contains('item');
        if (!inCard) return;                                  // 轨迹(.stop) 等一律不插手
        var t0 = ev.target;
        while (t0 && t0 !== row) { if (t0.classList && t0.classList.contains('poi-name')) return; t0 = t0.parentElement; }
        //★原来这里遇到"已经是链接"的行就放手 ✗ → 可它的 onclick 在部分行上不生效 ✗ → 那几处就没人跳 ✓
        //  改成：【卡片里的行一律由我接管】，防止这种"踢皮球"（只对 .day-card .item 生效）
        var holder = row.querySelector('.pl');
        var name = holder ? (holder.textContent || '').trim() : '';
        if (!name) return;
        // 交通条目文案形如「自驾 A→B」→ 取终点 B
        if (/→/.test(name)) name = name.split('→').pop().trim();
        if (!name || name.length > 30) return;
        ev.preventDefault();                              // 阻止链接默认跳转（避免开两次）；不 stopPropagation（不能挡掉 selectDay 等既有交互）
        openNav(name, CTX.city);                          // ★走 openNav：没坐标先查一次，保证开的是【路线规划页】
      } catch (e) { /* 兜底失败不影响原有点击 */ }
    }, true);
  }

  /** 打开高德搜索页（所有跳转统一走这里：概览/轨迹地点名 + 酒店餐饮卡片的按钮）
   *  用户口径：统一到高德搜索页，且【搜索框里一定要带上地名】。
   *  不再先查坐标（那个分支会退化成空搜索框 ✗）。 */
  function openNav(name, city) {
    var n = String(name || '').trim();
    if (!n) return;
    var u = url(n, city);
    if (!u) return;
    var w = window.open(u, '_blank');
    if (!w) window.location.href = u;   // 弹窗被拦也一定有反应
  }

  window.AmapNav = {
    url: url, openNav: openNav, ctripUrl: ctripUrl, fliggyUrl: fliggyUrl, load: load,
    setCity: function (c) { CTX.city = c || ''; }, coords: coordCache
  };

  bootClickFallback();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
