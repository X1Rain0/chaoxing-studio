/* 学习通签到工坊 · 前端逻辑（无构建步骤） */
(function () {
  'use strict';

  const API = (window.go && window.go.main && window.go.main.App) || null;
  const RT = window.runtime || { EventsOn() {} };
  const call = (name, ...args) => {
    if (!API) return Promise.reject(new Error('后端未就绪'));
    return API[name](...args);
  };

  const KIND = ['普通签到', '拍照签到', '二维码签到', '手势签到', '位置签到', '签到码'];
  const KIND_CLASS = ['k-general', 'k-photo', 'k-qrcode', 'k-gesture', 'k-location', 'k-code'];
  const WINDOW_MS = 2 * 60 * 60 * 1000;

  const S = {
    version: '1.0.0',
    dataPath: '',
    accounts: [],
    records: [],
    options: { interval: 60, auto: true, signCode: '', latitude: '', longitude: '', address: '' },
    statuses: {},
    currentId: null,
    courses: [],
    activities: [],
    logs: [],
    view: 'overview',
    signedIds: new Set(),
    signing: new Set(),
  };

  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

  /* ---------------- 工具 ---------------- */
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function validDate(v) {
    if (!v) return null;
    const d = new Date(v);
    if (isNaN(d.getTime()) || d.getFullYear() < 1971) return null;
    return d;
  }
  function hhmmss(v) {
    const d = validDate(v);
    if (!d) return '—';
    return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }
  function stamp(v) {
    const d = validDate(v);
    if (!d) return '—';
    return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function ago(v) {
    const d = validDate(v);
    if (!d) return '从未';
    const s = Math.floor((Date.now() - d.getTime()) / 1000);
    if (s < 10) return '刚刚';
    if (s < 60) return s + ' 秒前';
    if (s < 3600) return Math.floor(s / 60) + ' 分钟前';
    if (s < 86400) return Math.floor(s / 3600) + ' 小时前';
    return Math.floor(s / 86400) + ' 天前';
  }
  function cur() { return S.accounts.find((a) => a.id === S.currentId) || null; }
  function statusOf(id) { return S.statuses[id] || { accountId: id, running: false, checks: 0, signs: 0, failures: 0 }; }
  function maskPhone(p) {
    if (!p) return '';
    return p.length >= 11 ? p.slice(0, 3) + ' **** ' + p.slice(-4) : p;
  }

  /* ---------------- Toast / 提示音 ---------------- */
  function toast(kind, title, text) {
    const box = $('#toasts');
    const el = document.createElement('div');
    el.className = 'toast ' + (kind || '');
    el.innerHTML = '<i></i><div><strong>' + esc(title) + '</strong>' + (text ? '<span>' + esc(text) + '</span>' : '') + '</div>';
    box.appendChild(el);
    setTimeout(() => {
      el.classList.add('out');
      setTimeout(() => el.remove(), 260);
    }, 4400);
  }

  let audioCtx = null;
  function beep(times) {
    try {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return;
      if (!audioCtx) audioCtx = new Ctx();
      if (audioCtx.state === 'suspended') audioCtx.resume();
      for (let i = 0; i < (times || 2); i++) {
        const t0 = audioCtx.currentTime + i * 0.24;
        const osc = audioCtx.createOscillator();
        const gain = audioCtx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(880, t0);
        osc.frequency.exponentialRampToValueAtTime(1318, t0 + 0.08);
        gain.gain.setValueAtTime(0.0001, t0);
        gain.gain.exponentialRampToValueAtTime(0.13, t0 + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.19);
        osc.connect(gain);
        gain.connect(audioCtx.destination);
        osc.start(t0);
        osc.stop(t0 + 0.22);
      }
    } catch (e) { /* 静默 */ }
  }

  const baseTitle = document.title;
  let titleTimer = null;
  function flashTitle(text) {
    document.title = '● ' + text;
    clearTimeout(titleTimer);
    titleTimer = setTimeout(() => { document.title = baseTitle; }, 8000);
  }

  /* ---------------- 事件订阅 ---------------- */
  function bindRuntime() {
    RT.EventsOn('log', (e) => {
      if (!e) return;
      if (e.accountId && S.currentId && e.accountId !== S.currentId) return;
      pushLog(e);
    });
    RT.EventsOn('status', (e) => {
      if (!e || !e.accountId) return;
      S.statuses[e.accountId] = e;
      if (e.accountId === S.currentId) { renderStats(); renderPills(); renderAccounts(); }
    });
    RT.EventsOn('courses', (e) => {
      if (!e) return;
      if (e.accountId && e.accountId !== S.currentId) return;
      S.courses = e.courses || [];
      renderCourses();
    });
    RT.EventsOn('activity', (e) => {
      if (!e || !e.activity) return;
      if (e.accountId && S.currentId && e.accountId !== S.currentId) return;
      const a = e.activity;
      a.__need = e.need || '';
      a.__auto = !!e.auto;
      const i = S.activities.findIndex((x) => x.activeId === a.activeId);
      if (i >= 0) S.activities[i] = a; else S.activities.unshift(a);
      renderActivities();
      beep(2);
      flashTitle('新的签到 · ' + ((a.course && a.course.courseName) || a.name || ''));
      toast('warn', '发现签到', ((a.course && a.course.courseName) || '') + ' · ' + (a.kindName || KIND[a.kind] || '') + (e.need ? '（' + e.need + '）' : ''));
    });
    RT.EventsOn('sign', (e) => {
      if (!e) return;
      if (e.accountId && S.currentId && e.accountId !== S.currentId) return;
      const res = e.result || {};
      if (res.ok) {
        S.signedIds.add((e.activity || {}).activeId);
        S.signing.delete((e.activity || {}).activeId);
        renderActivities();
        beep(1);
        toast('ok', '签到成功', ((e.activity && e.activity.course && e.activity.course.courseName) || '') + ' · ' + (res.kindName || ''));
      } else if (e.error || res.message) {
        S.signing.delete((e.activity || {}).activeId);
        renderActivities();
        toast('err', '签到失败', e.error || res.message);
      }
      refreshRecords();
    });
  }

  /* ---------------- 渲染：账号 ---------------- */
  function renderAccounts() {
    const box = $('#accounts');
    $('#acc-count').textContent = S.accounts.length;
    if (!S.accounts.length) {
      box.innerHTML = '<div class="acc-empty"><b>还没有账号</b><span>用手机号登录后即可开始巡检</span></div>';
      return;
    }
    box.innerHTML = S.accounts.map((a) => {
      const st = statusOf(a.id);
      const on = a.id === S.currentId;
      const state = st.running ? '监听中' : (a.enabled === false ? '已暂停' : '就绪');
      return '<button class="acc' + (on ? ' on' : '') + '" data-id="' + esc(a.id) + '">' +
        '<span class="acc-name">' + esc(a.name || '未命名账号') + '</span>' +
        '<span class="acc-state' + (st.running ? ' live' : '') + '">' + esc(state) + '</span>' +
        '<span class="acc-phone">' + esc(maskPhone(a.phone)) + '</span>' +
        '</button>';
    }).join('');
  }

  /* ---------------- 渲染：顶部胶囊 / 统计 ---------------- */
  function renderPills() {
    const a = cur();
    const st = a ? statusOf(a.id) : null;
    const pill = $('#session-pill');
    const iv = $('#interval-pill');
    const btn = $('#btn-toggle');

    const interval = (st && st.interval) || S.options.interval || 60;
    $('#side-auto').checked = S.options.auto !== false;
    $('#side-interval').textContent = '每 ' + interval + ' 秒';
    $('#side-last').textContent = st && st.lastTick ? hhmmss(st.lastTick) : '—';

    const chipSession = $('#chip-session');
    const chipAuto = $('#chip-auto');
    const chipInterval = $('#chip-interval');
    if (chipSession) {
      chipSession.textContent = a ? (st && st.running ? '会话有效 · 监听中' : '会话有效 · 未监听') : '未登录';
      chipSession.style.color = a ? '' : 'var(--warn)';
      chipAuto.textContent = S.options.auto !== false ? '命中自动签到' : '仅提醒不签到';
      chipInterval.textContent = '每 ' + interval + ' 秒巡检';
    }

    if (S.view === 'overview') {
      $('#view-sub').textContent = a
        ? (a.name || maskPhone(a.phone)) + ' · ' + (S.courses.length ? S.courses.length + ' 门课程' : '课程加载中') + ' · ' + (S.options.auto !== false ? '命中自动签到' : '仅提醒不签到')
        : '添加账号后开始自动巡检';
    }

    if (!a) {
      pill.className = 'state';
      pill.innerHTML = '<i></i><span>未登录</span>';
      iv.textContent = '每 ' + interval + ' 秒';
      btn.textContent = '开始监听';
      btn.disabled = true;
      return;
    }
    btn.disabled = false;
    if (st && st.running) {
      pill.className = 'state on';
      pill.innerHTML = '<i></i><span>监听中 · ' + (st.checks || 0) + ' 轮</span>';
      btn.textContent = '停止监听';
      btn.className = 'btn danger';
    } else {
      pill.className = 'state warn';
      pill.innerHTML = '<i></i><span>会话就绪 · 未监听</span>';
      btn.textContent = '开始监听';
      btn.className = 'btn primary';
    }
    iv.textContent = '每 ' + interval + ' 秒';
  }

  function renderStats() {
    const a = cur();
    const st = a ? statusOf(a.id) : { checks: 0, signs: 0, failures: 0 };
    $('#stat-last').textContent = st.lastTick ? hhmmss(st.lastTick) : '—';
    $('#stat-last-sub').textContent = st.lastMessage ? String(st.lastMessage).slice(0, 22) : '等待启动';
    $('#stat-checks').textContent = st.checks || 0;
    $('#stat-checks-sub').textContent = st.running ? '监听运行中' : '保持会话心跳';
    $('#stat-signed').textContent = st.signs || 0;
    $('#stat-signed-sub').textContent = '本次监听';
    $('#stat-fail').textContent = st.failures || 0;
    $('#stat-fail-sub').textContent = (st.failures || 0) >= 3 ? '已自动降频' : '自动降频重试';
    $('#side-last').textContent = st.lastTick ? hhmmss(st.lastTick) : '—';
    $('#side-interval').textContent = '每 ' + ((st && st.interval) || S.options.interval || 60) + ' 秒';
  }

  // 手动检测时前端也做一次参数判定，避免盲目提交。
  function needOf(a) {
    const k = typeof a.kind === 'number' ? a.kind : 0;
    const code = (S.options.signCode || '').trim();
    if ((k === 3 || k === 5) && !code) return k === 5 ? '需要老师公布的签到码' : '需要手势轨迹编码';
    if (k === 4 && (!(S.options.latitude || '').trim() || !(S.options.longitude || '').trim())) return '需要设置签到位置';
    if (k === 2) return '需要二维码 enc 参数';
    return '';
  }

  /* ---------------- 渲染：活动 ---------------- */
  function renderActivities() {
    const box = $('#act-list');
    if (!S.activities.length) {
      box.innerHTML = '<div class="empty"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8.2"/><path d="M12 7.6V12l3.1 1.9"/></svg><span>巡检到签到会立刻出现在这里<br/>先点右上角「开始监听」</span></div><div class="rule-fill" aria-hidden="true"></div>';
      $('#act-hint').textContent = '暂无进行中的签到';
      return;
    }
    $('#act-hint').textContent = S.activities.length + ' 个待处理';
    box.innerHTML = S.activities.map((a) => {
      const k = typeof a.kind === 'number' ? a.kind : 0;
      const signed = S.signedIds.has(a.activeId);
      const busy = S.signing.has(a.activeId);
      const need = a.__need || needOf(a);
      let action;
      const needCode = (k === 3 || k === 5) && !(S.options.signCode || '').trim();
      if (signed) action = '<span class="seal sm" title="已签到" aria-label="已签到">签</span>';
      else if (busy) action = '<button class="btn sm" disabled>提交中</button>';
      else if (needCode) {
        action = '<div class="act-code"><input class="mini-input" data-code-for="' + esc(a.activeId) + '" placeholder="' + (k === 5 ? '签到码' : '手势码') + '" maxlength="24" />' +
          '<button class="btn sm primary" data-act="sign" data-id="' + esc(a.activeId) + '">提交</button></div>';
      } else if (need) action = '<button class="btn sm" data-act="goto-settings">补参数</button>';
      else action = '<button class="btn sm primary" data-act="sign" data-id="' + esc(a.activeId) + '">立即签到</button>';
      const meta = [(a.course && a.course.className) || '', a.name || ''].filter(Boolean).map(esc).join(' · ');
      return '<div class="act">' +
        '<div class="act-main">' +
        '<div class="act-line"><span class="act-title">' + esc((a.course && a.course.courseName) || a.name || '未知课程') + '</span>' +
        '<span class="kind">' + esc(a.kindName || KIND[k] || '签到') + '</span></div>' +
        '<div class="act-meta">' + (meta || '—') + '</div>' +
        (need ? '<div class="act-need">' + esc(need) + '</div>' : '') +
        '</div>' +
        '<div class="act-side"><span class="countdown" data-since="' + esc(a.startAt || '') + '">--:--</span>' + action + '</div>' +
        '</div>';
    }).join('') + '<div class="rule-fill" aria-hidden="true"></div>';
    tickCountdown();
  }

  function tickCountdown() {
    $$('.countdown').forEach((el) => {
      const d = validDate(el.getAttribute('data-since'));
      if (!d) { el.textContent = '--:--'; return; }
      let left = d.getTime() + WINDOW_MS - Date.now();
      if (left <= 0) {
        el.textContent = '已结束';
        el.classList.add('urgent');
        return;
      }
      const s = Math.floor(left / 1000);
      const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), ss = s % 60;
      el.textContent = (h > 0 ? h + ':' + pad(m) : pad(m) + ':' + pad(ss));
      el.classList.toggle('urgent', left < 5 * 60 * 1000);
    });
  }

  /* ---------------- 渲染：日志 ---------------- */
  function pushLog(e) {
    const entry = { level: e.level || 'info', message: e.message || '', at: e.at || new Date().toISOString(), accountId: e.accountId || '' };
    S.logs.push(entry);
    if (S.logs.length > 400) S.logs.splice(0, S.logs.length - 400);
    if (entry.accountId && S.currentId && entry.accountId !== S.currentId) return;
    const box = $('#log-list');
    if (box.querySelector('.log-empty')) box.innerHTML = '';
    const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    const row = document.createElement('div');
    row.className = 'log-row ' + esc(entry.level);
    row.innerHTML = '<span class="log-time">' + hhmmss(entry.at) + '</span><span class="log-msg">' + esc(entry.message) + '</span>';
    box.appendChild(row);
    while (box.children.length > 400) box.removeChild(box.firstChild);
    if (nearBottom) box.scrollTop = box.scrollHeight;
  }

  function renderLogs() {
    const box = $('#log-list');
    box.innerHTML = '';
    const rows = S.logs.filter((e) => !e.accountId || !S.currentId || e.accountId === S.currentId);
    box.classList.toggle('is-empty', !rows.length);
    if (!rows.length) {
      box.innerHTML = '<div class="log-empty">暂无日志 — 添加账号并开始监听后，这里会实时输出登录、扫描课程、发现签到与提交结果。</div>' +
        '<div class="rule-fill" aria-hidden="true"></div>';
      return;
    }
    rows.forEach((e) => {
      const row = document.createElement('div');
      row.className = 'log-row ' + esc(e.level);
      row.innerHTML = '<span class="log-time">' + hhmmss(e.at) + '</span><span class="log-msg">' + esc(e.message) + '</span>';
      box.appendChild(row);
    });
    box.scrollTop = box.scrollHeight;
  }

  /* ---------------- 渲染：课程 ---------------- */
  function filterKey(id) { return 'cx.courses.' + id; }
  function selectedCourses(id) {
    const all = S.courses.map((c) => c.courseId);
    try {
      const raw = localStorage.getItem(filterKey(id));
      if (!raw) return all;
      const list = JSON.parse(raw);
      return Array.isArray(list) ? list.filter((x) => all.indexOf(x) >= 0) : all;
    } catch (e) { return all; }
  }
  function saveSelected(id, list) {
    try { localStorage.setItem(filterKey(id), JSON.stringify(list)); } catch (e) { /* ignore */ }
  }

  function renderCourses() {
    const box = $('#course-grid');
    if (!S.currentId) {
      box.innerHTML = '<div class="empty"><span>先选择一个账号</span></div>';
      $('#course-hint').textContent = '—';
      return;
    }
    if (!S.courses.length) {
      box.innerHTML = '<div class="empty"><span>课程列表为空，试试刷新</span></div>';
      $('#course-hint').textContent = '0 门';
      return;
    }
    const sel = selectedCourses(S.currentId);
    $('#course-hint').textContent = '已选 ' + sel.length + ' / ' + S.courses.length + ' 门参与巡检';
    box.innerHTML = S.courses.map((c) => {
      const on = sel.indexOf(c.courseId) >= 0;
      return '<div class="course' + (on ? ' on' : '') + '" data-course="' + esc(c.courseId) + '">' +
        '<span class="tick"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4.5 12.5 9.5 17.5 19.5 6.5"/></svg></span>' +
        '<div class="course-body"><div class="course-name">' + esc(c.courseName || c.className) + '</div>' +
        (c.className ? '<div class="course-class">' + esc(c.className) + '</div>' : '') + '</div>' +
        '</div>';
    }).join('');
  }

  /* ---------------- 渲染：记录 ---------------- */
  function renderRecords() {
    const body = $('#record-body');
    renderRecent();
    if (!S.records.length) {
      body.innerHTML = '<tr><td class="empty-row" colspan="5">还没有签到记录。命中签到并提交后，这里会留下时间、课程与结果。</td></tr>';
      return;
    }
    body.innerHTML = S.records.map((r) => {
      return '<tr>' +
        '<td class="t">' + stamp(r.at) + '</td>' +
        '<td>' + esc(r.course || '—') + (r.className ? ' <span class="muted">' + esc(r.className) + '</span>' : '') + '</td>' +
        '<td>' + esc(r.kind || '—') + '</td>' +
        '<td>' + (r.ok ? '<span class="seal sm" title="已签到">签</span>' : '<span class="res-bad">失败</span>') + '</td>' +
        '<td class="muted">' + esc(r.message || '') + '</td>' +
        '</tr>';
    }).join('');
  }

  async function refreshRecords() {
    try {
      S.records = await call('Records', '', 200);
      renderRecords();
    } catch (e) { /* ignore */ }
  }

  function renderRecent() {
    const box = $('#side-mini-list');
    if (!box) return;
    const list = (S.records || []).filter((r) => r.ok).slice(0, 4);
    if (!list.length) {
      box.innerHTML = '<div class="mini-row"><span class="mini-name muted-ish">暂无成功记录</span></div>';
      return;
    }
    box.innerHTML = list.map((r) =>
      '<div class="mini-row"><span class="mini-name">' + esc(r.course || '—') + '</span><span class="mini-time">' + stamp(r.at) + '</span></div>'
    ).join('');
  }

  /* ---------------- 渲染：设置 ---------------- */
  function renderSettings() {
    const o = S.options || {};
    $('#set-interval').value = o.interval || 60;
    $('#set-interval-val').textContent = (o.interval || 60) + ' 秒';
    $('#set-auto').checked = o.auto !== false;
    $('#set-code').value = o.signCode || '';
    $('#set-lat').value = o.latitude || '';
    $('#set-lng').value = o.longitude || '';
    $('#set-addr').value = o.address || '';
    const p = $('#set-path');
    if (p) p.textContent = S.dataPath || '—';
    const v = $('#set-version');
    if (v) v.textContent = 'v' + (S.version || '1.0.0');
  }

  function collectOptions() {
    return {
      interval: parseInt($('#set-interval').value, 10) || 60,
      auto: $('#set-auto').checked,
      signCode: $('#set-code').value.trim(),
      latitude: $('#set-lat').value.trim(),
      longitude: $('#set-lng').value.trim(),
      address: $('#set-addr').value.trim(),
      /* 地图选点弹层里填的高德 Key，表单本身没有输入框，沿用已保存的值 */
      amapKey: (S.options && S.options.amapKey) || ''
    };
  }

  function engineOptions() {
    const a = cur();
    const sel = a ? selectedCourses(a.id) : [];
    const all = S.courses.map((c) => c.courseId);
    return {
      interval: S.options.interval || 60,
      auto: S.options.auto !== false,
      signCode: S.options.signCode || '',
      latitude: S.options.latitude || '',
      longitude: S.options.longitude || '',
      address: S.options.address || '',
      enc: '',
      courseIds: a && sel.length && sel.length < all.length ? sel : []
    };
  }

  /* ---------------- 视图切换 ---------------- */
  const VIEW_SUB = {
    overview: '实时巡检你的课程签到',
    courses: '勾选需要参与巡检的课程',
    records: '每一次提交的完整记录',
    settings: '轮询节奏与签到参数'
  };
  const VIEW_TITLE = { overview: '总览', courses: '课程', records: '签到记录', settings: '设置' };

  function applyTheme(name) {
    const t = name === 'ink' ? 'ink' : 'paper';
    document.documentElement.setAttribute('data-theme', t);
    const btn = $('#btn-theme');
    if (btn) btn.textContent = t === 'ink' ? '日间' : '夜间';
    try { localStorage.setItem('cx.theme', t); } catch (e) { /* ignore */ }
  }

  function switchView(view) {
    S.view = view;
    $$('.view').forEach((v) => v.classList.toggle('hidden', v.id !== 'view-' + view));
    $$('#nav button').forEach((b) => b.classList.toggle('on', b.dataset.view === view));
    $('#view-title').textContent = VIEW_TITLE[view] || '总览';
    $('#view-sub').textContent = VIEW_SUB[view] || '';
    if (view === 'records') refreshRecords();
    if (view === 'courses') loadCourses();
    if (view === 'settings') renderSettings();
  }

  /* ---------------- 数据加载 ---------------- */
  async function loadCourses(force) {
    if (!S.currentId) { renderCourses(); return; }
    try {
      S.courses = force ? await call('RefreshCourses', S.currentId) : await call('Courses', S.currentId);
      renderCourses();
    } catch (e) {
      toast('err', '课程加载失败', e && e.message ? e.message : String(e));
    }
  }

  async function selectAccount(id) {
    S.currentId = id;
    S.activities = [];
    S.courses = [];
    renderAccounts();
    renderActivities();
    renderLogs();
    renderPills();
    renderStats();
    try {
      const st = await call('Status', id);
      if (st && st.accountId) S.statuses[id] = st;
    } catch (e) { /* ignore */ }
    renderPills();
    renderStats();
    await loadCourses(false);
    if (S.view !== 'overview') switchView(S.view);
  }

  /* ---------------- 弹窗：登录 ---------------- */
  function openLogin() {
    const root = $('#modal-root');
    root.innerHTML = '<div class="overlay" id="ov-login"><div class="modal">' +
      '<h3>添加学习通账号</h3><p class="sub">密码使用 Windows DPAPI 加密后保存在本机，只用于登录。</p>' +
      '<div id="login-err" style="display:none" class="modal-err"></div>' +
      '<label class="field"><span>手机号</span><input id="in-phone" placeholder="11 位手机号" autocomplete="off" /></label>' +
      '<label class="field"><span>密码</span><input id="in-pass" type="password" placeholder="学习通登录密码" /></label>' +
      '<div class="modal-foot"><button class="btn ghost" id="btn-cancel">取消</button><button class="btn primary" id="btn-do-login">登录</button></div>' +
      '</div></div>';
    const phone = $('#in-phone');
    phone.focus();
    $('#btn-cancel').onclick = closeModal;
    $('#ov-login').onclick = (ev) => { if (ev.target.id === 'ov-login') closeModal(); };
    const submit = async () => {
      const err = $('#login-err');
      const p = phone.value.trim();
      const pw = $('#in-pass').value;
      err.style.display = 'none';
      if (!p || !pw) { err.textContent = '手机号和密码都要填'; err.style.display = 'block'; return; }
      const btn = $('#btn-do-login');
      btn.disabled = true;
      btn.textContent = '登录中…';
      try {
        const res = await call('Login', p, pw);
        closeModal();
        const exist = S.accounts.findIndex((a) => a.id === res.account.id);
        if (exist >= 0) S.accounts[exist] = res.account; else S.accounts.push(res.account);
        S.courses = res.courses || [];
        toast('ok', '登录成功', (res.account.name || '') + ' · ' + S.courses.length + ' 门课程');
        await selectAccount(res.account.id);
      } catch (e) {
        err.textContent = (e && e.message) ? e.message : String(e);
        err.style.display = 'block';
        btn.disabled = false;
        btn.textContent = '登录';
      }
    };
    $('#btn-do-login').onclick = submit;
    $('#in-pass').onkeydown = (ev) => { if (ev.key === 'Enter') submit(); };
    phone.onkeydown = (ev) => { if (ev.key === 'Enter') $('#in-pass').focus(); };
  }

  function closeModal() { $('#modal-root').innerHTML = ''; }

  /* ---------------- 弹窗：地图选点 ----------------
     手写 slippy map：256px 瓦片 + Web 墨卡托，底图用高德矢量/卫星瓦片（无需 Key）。
     坐标系 GCJ-02，与学习通、高德一致，取到的就是能直接提交的数字。 */
  const MAP_MIN_Z = 3;
  const MAP_MAX_Z = 18;
  const TILE_PX = 256;

  const TILES = {
    vec: (z, x, y) => 'https://webrd0' + (1 + ((x + y) % 4)) + '.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=' + x + '&y=' + y + '&z=' + z,
    sat: (z, x, y) => 'https://webst0' + (1 + ((x + y) % 4)) + '.is.autonavi.com/appmaptile?style=6&x=' + x + '&y=' + y + '&z=' + z
  };

  const M = { z: 16, lat: 39.9042, lng: 116.4074, pin: null, base: 'vec', tiles: new Map(), drag: null };

  const mercX = (lng, z) => (lng + 180) / 360 * Math.pow(2, z);
  const mercY = (lat, z) => {
    const s = Math.sin(Math.max(-85.05, Math.min(85.05, lat)) * Math.PI / 180);
    return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * Math.pow(2, z);
  };
  const mercLng = (x, z) => x / Math.pow(2, z) * 360 - 180;
  const mercLat = (y, z) => {
    const n = Math.PI - 2 * Math.PI * y / Math.pow(2, z);
    return 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
  };
  const mapErr = (e) => (e && e.message) ? e.message : String(e);

  function mapHint(msg) {
    const el = $('#map-hint');
    if (el) el.textContent = msg || '';
  }

  function mapDraw() {
    const canvas = $('#map-canvas');
    if (!canvas) return;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    const z = M.z;
    const n = Math.pow(2, z);
    const cx = mercX(M.lng, z) * TILE_PX;
    const cy = mercY(M.lat, z) * TILE_PX;
    const left = cx - w / 2;
    const top = cy - h / 2;

    const x0 = Math.floor(left / TILE_PX);
    const x1 = Math.floor((left + w - 1) / TILE_PX);
    const y0 = Math.max(0, Math.floor(top / TILE_PX));
    const y1 = Math.min(n - 1, Math.floor((top + h - 1) / TILE_PX));

    const seen = new Set();
    for (let tx = x0; tx <= x1; tx++) {
      for (let ty = y0; ty <= y1; ty++) {
        const wx = ((tx % n) + n) % n;
        const key = M.base + '/' + z + '/' + wx + '/' + ty;
        seen.add(key);
        let img = M.tiles.get(key);
        if (!img) {
          img = document.createElement('img');
          img.className = 'map-tile';
          img.alt = '';
          img.decoding = 'async';
          img.draggable = false;
          img.src = TILES[M.base](z, wx, ty);
          M.tiles.set(key, img);
          canvas.insertBefore(img, canvas.firstChild);
        }
        img.style.transform = 'translate3d(' + (tx * TILE_PX - left) + 'px,' + (ty * TILE_PX - top) + 'px,0)';
      }
    }
    M.tiles.forEach((img, key) => {
      if (!seen.has(key)) { img.remove(); M.tiles.delete(key); }
    });

    const pin = $('#map-pin');
    if (pin) {
      if (M.pin) {
        pin.hidden = false;
        pin.style.transform = 'translate3d(' + (mercX(M.pin.lng, z) * TILE_PX - left) + 'px,' + (mercY(M.pin.lat, z) * TILE_PX - top) + 'px,0)';
      } else {
        pin.hidden = true;
      }
    }
    const coord = $('#map-coord');
    if (coord) {
      coord.textContent = M.pin
        ? M.pin.lat.toFixed(6) + ', ' + M.pin.lng.toFixed(6)
        : '未选点 · 视野中心 ' + M.lat.toFixed(4) + ', ' + M.lng.toFixed(4);
    }
    const mode = $('#map-mode');
    if (mode) mode.textContent = M.base === 'sat' ? '卫星影像' : '矢量底图';
  }

  function mapPointAt(clientX, clientY) {
    const canvas = $('#map-canvas');
    const rect = canvas.getBoundingClientRect();
    const left = mercX(M.lng, M.z) * TILE_PX - rect.width / 2;
    const top = mercY(M.lat, M.z) * TILE_PX - rect.height / 2;
    const wx = left + (clientX - rect.left);
    const wy = top + (clientY - rect.top);
    return { lat: mercLat(wy / TILE_PX, M.z), lng: mercLng(wx / TILE_PX, M.z) };
  }

  function mapZoomAt(delta, clientX, clientY) {
    const z = Math.max(MAP_MIN_Z, Math.min(MAP_MAX_Z, M.z + delta));
    if (z === M.z) return;
    const canvas = $('#map-canvas');
    const rect = canvas.getBoundingClientRect();
    const anchor = mapPointAt(clientX, clientY);
    M.z = z;
    const px = mercX(anchor.lng, z) * TILE_PX;
    const py = mercY(anchor.lat, z) * TILE_PX;
    M.lng = mercLng((px - (clientX - rect.left) + rect.width / 2) / TILE_PX, z);
    M.lat = mercLat((py - (clientY - rect.top) + rect.height / 2) / TILE_PX, z);
    mapDraw();
  }

  async function mapReverse() {
    if (!M.pin) return;
    if (!(S.options && S.options.amapKey)) return;
    try {
      const p = await call('PlaceReverse', M.pin.lat, M.pin.lng);
      if (p && (p.address || p.region)) {
        const input = $('#map-addr');
        if (input && !input.value.trim()) input.value = p.address || p.region;
        mapHint('');
      }
    } catch (e) {
      mapHint(mapErr(e));
    }
  }

  async function mapSearch() {
    const input = $('#map-q');
    const box = $('#map-results');
    const kw = input.value.trim();
    if (!kw) { input.focus(); return; }
    box.innerHTML = '<div class="map-note">检索中…</div>';
    try {
      const list = await call('PlaceSearch', kw, '');
      if (!list || !list.length) {
        box.innerHTML = '<div class="map-note">没有找到「' + esc(kw) + '」，换个说法，或者直接在地图上点。</div>';
        return;
      }
      box.innerHTML = list.map((p, i) => '<button type="button" class="map-hit" data-i="' + i + '">' +
        '<b>' + esc(p.name || '未命名地点') + '</b>' +
        '<span>' + esc(p.address || p.region || '') + '</span>' +
        '</button>').join('');
      $$('.map-hit', box).forEach((el) => {
        el.onclick = () => {
          const p = list[parseInt(el.dataset.i, 10)];
          M.pin = { lat: p.latitude, lng: p.longitude };
          M.lat = p.latitude;
          M.lng = p.longitude;
          M.z = Math.max(M.z, 16);
          const addr = $('#map-addr');
          if (addr && !addr.value.trim()) addr.value = p.address || p.name || '';
          mapHint('');
          mapDraw();
        };
      });
    } catch (e) {
      box.innerHTML = '<div class="map-note">' + esc(mapErr(e)) + '</div>';
    }
  }

  function mapBindCanvas() {
    const canvas = $('#map-canvas');
    if (!canvas) return;

    canvas.addEventListener('pointerdown', (ev) => {
      if (ev.button !== 0) return;
      canvas.setPointerCapture(ev.pointerId);
      M.drag = {
        id: ev.pointerId,
        x: ev.clientX,
        y: ev.clientY,
        wx: mercX(M.lng, M.z) * TILE_PX,
        wy: mercY(M.lat, M.z) * TILE_PX,
        moved: 0
      };
      canvas.classList.add('dragging');
    });

    canvas.addEventListener('pointermove', (ev) => {
      if (!M.drag || ev.pointerId !== M.drag.id) return;
      const dx = ev.clientX - M.drag.x;
      const dy = ev.clientY - M.drag.y;
      M.drag.moved = Math.max(M.drag.moved, Math.abs(dx), Math.abs(dy));
      M.lng = mercLng((M.drag.wx - dx) / TILE_PX, M.z);
      M.lat = mercLat((M.drag.wy - dy) / TILE_PX, M.z);
      mapDraw();
    });

    const endDrag = (ev) => {
      if (!M.drag || ev.pointerId !== M.drag.id) return;
      const moved = M.drag.moved;
      M.drag = null;
      canvas.classList.remove('dragging');
      if (moved < 5) {
        const p = mapPointAt(ev.clientX, ev.clientY);
        M.pin = { lat: p.lat, lng: p.lng };
        mapHint('');
        mapDraw();
        mapReverse();
      }
    };
    canvas.addEventListener('pointerup', endDrag);
    canvas.addEventListener('pointercancel', () => {
      M.drag = null;
      canvas.classList.remove('dragging');
    });

    canvas.addEventListener('wheel', (ev) => {
      ev.preventDefault();
      mapZoomAt(ev.deltaY < 0 ? 1 : -1, ev.clientX, ev.clientY);
    }, { passive: false });

    canvas.addEventListener('dblclick', (ev) => {
      ev.preventDefault();
      mapZoomAt(1, ev.clientX, ev.clientY);
    });
  }

  function openMapPicker() {
    const o = S.options || {};
    const lat0 = parseFloat(o.latitude);
    const lng0 = parseFloat(o.longitude);
    const has = isFinite(lat0) && isFinite(lng0) && Math.abs(lat0) <= 90 && Math.abs(lng0) <= 180;

    M.z = has ? 17 : 16;
    M.lat = has ? lat0 : 39.9042;
    M.lng = has ? lng0 : 116.4074;
    M.pin = has ? { lat: lat0, lng: lng0 } : null;
    M.base = 'vec';
    M.tiles = new Map();
    M.drag = null;

    $('#modal-root').innerHTML =
      '<div class="overlay" id="ov-map"><div class="modal map-modal">' +
        '<div class="map-head"><h3>在地图上选点</h3><span class="map-mode" id="map-mode">矢量底图</span></div>' +
        '<p class="sub">拖动平移，滚轮缩放，单击落点。取到的坐标是 GCJ-02，可以直接用于位置签到。</p>' +
        '<div class="map-search">' +
          '<input id="map-q" placeholder="搜地名，例如「图书馆」" autocomplete="off" />' +
          '<button class="btn ghost sm" id="btn-map-search" type="button">搜索</button>' +
        '</div>' +
        '<div class="map-frame"><div class="map-canvas" id="map-canvas">' +
          '<div class="map-pin" id="map-pin" hidden><i></i></div>' +
          '<div class="map-zoom">' +
            '<button type="button" id="map-zin" aria-label="放大">+</button>' +
            '<button type="button" id="map-zout" aria-label="缩小">−</button>' +
            '<button type="button" id="map-base" aria-label="切换底图">图</button>' +
          '</div>' +
          '<span class="map-sign">© 高德地图</span>' +
        '</div></div>' +
        '<div class="map-read"><span id="map-coord">—</span><span class="map-crs">GCJ-02</span></div>' +
        '<div class="map-results" id="map-results"></div>' +
        '<label class="field"><span>地址描述</span><input id="map-addr" placeholder="图书馆东门" /></label>' +
        '<label class="field map-key-row"><span>高德 Key（可选，用于地名搜索与地址回填）</span>' +
          '<input id="map-key" placeholder="留空也能点选，只是没有搜索" autocomplete="off" /></label>' +
        '<div class="map-hint" id="map-hint"></div>' +
        '<div class="modal-foot">' +
          '<button class="btn ghost" id="btn-map-cancel" type="button">取消</button>' +
          '<button class="btn primary" id="btn-map-use" type="button">使用此位置</button>' +
        '</div>' +
      '</div></div>';

    $('#map-key').value = o.amapKey || '';
    $('#map-addr').value = o.address || '';
    mapBindCanvas();
    mapDraw();

    $('#btn-map-search').onclick = mapSearch;
    $('#map-q').onkeydown = (ev) => { if (ev.key === 'Enter') mapSearch(); };
    $('#map-zin').onclick = () => {
      const c = $('#map-canvas');
      const r = c.getBoundingClientRect();
      mapZoomAt(1, r.left + r.width / 2, r.top + r.height / 2);
    };
    $('#map-zout').onclick = () => {
      const c = $('#map-canvas');
      const r = c.getBoundingClientRect();
      mapZoomAt(-1, r.left + r.width / 2, r.top + r.height / 2);
    };
    $('#map-base').onclick = () => {
      M.base = M.base === 'sat' ? 'vec' : 'sat';
      M.tiles.forEach((img, key) => {
        const parts = key.split('/');
        img.src = TILES[M.base](parts[1] | 0, parts[2] | 0, parts[3] | 0);
      });
      mapDraw();
    };
    $('#btn-map-cancel').onclick = closeModal;
    $('#ov-map').onclick = (ev) => { if (ev.target.id === 'ov-map') closeModal(); };
    $('#btn-map-use').onclick = async () => {
      if (!M.pin) { mapHint('先在地图上点一下要签到的大致位置。'); return; }
      const lat = M.pin.lat.toFixed(6);
      const lng = M.pin.lng.toFixed(6);
      const addr = $('#map-addr').value.trim();
      const key = $('#map-key').value.trim();

      $('#set-lat').value = lat;
      $('#set-lng').value = lng;
      $('#set-addr').value = addr;
      S.options = Object.assign({}, S.options, { latitude: lat, longitude: lng, address: addr, amapKey: key });
      try {
        S.options = await call('SaveOptions', collectOptions());
        toast('ok', '位置已保存', lat + ', ' + lng);
        closeModal();
      } catch (e) {
        toast('err', '保存失败', mapErr(e));
      }
    };

    const q = $('#map-q');
    if (q) q.focus();
  }

  /* ---------------- 动作 ---------------- */
  async function toggleMonitor() {
    const a = cur();
    if (!a) return;
    const st = statusOf(a.id);
    const btn = $('#btn-toggle');
    btn.disabled = true;
    try {
      if (st.running) {
        await call('StopMonitor', a.id);
        S.statuses[a.id] = Object.assign({}, st, { running: false, lastMessage: '监听已停止' });
        toast('warn', '已停止监听', a.name || a.phone);
      } else {
        const s = await call('StartMonitor', a.id, engineOptions());
        if (s && s.accountId) S.statuses[a.id] = s;
        toast('ok', '监听已启动', '每 ' + ((s && s.interval) || S.options.interval) + ' 秒扫描一次');
      }
      S.signedIds.clear();
      renderPills();
      renderStats();
      renderAccounts();
    } catch (e) {
      toast('err', '操作失败', (e && e.message) ? e.message : String(e));
    } finally {
      btn.disabled = false;
    }
  }

  async function signActivity(id) {
    const a = S.activities.find((x) => x.activeId === id);
    if (!a || !S.currentId) return;
    const inp = document.querySelector('[data-code-for="' + id + '"]');
    const typed = inp && inp.value ? inp.value.trim() : '';
    if (typed) {
      S.options = Object.assign({}, S.options, { signCode: typed });
      try {
        const saved = await call('SaveOptions', S.options);
        if (saved) S.options = saved;
      } catch (e) { /* 保存失败也继续，SignNow 会带上本次签到码 */ }
      renderSettings();
    }
    S.signing.add(id);
    renderActivities();
    try {
      const res = await call('SignNow', S.currentId, a, engineOptions());
      if (res && res.ok) {
        S.signedIds.add(id);
        toast('ok', '签到成功', ((a.course && a.course.courseName) || '') + ' · ' + (res.kindName || ''));
        beep(1);
      } else {
        toast('err', '签到失败', (res && res.message) || '接口未返回 success');
      }
      refreshRecords();
    } catch (e) {
      toast('err', '签到失败', (e && e.message) ? e.message : String(e));
    } finally {
      S.signing.delete(id);
      renderActivities();
    }
  }

  /* ---------------- 事件绑定 ---------------- */
  function bindUI() {
    let saved = null;
    try { saved = localStorage.getItem('cx.theme'); } catch (e) { saved = null; }
    const qs = new URLSearchParams(location.search).get('theme');
    applyTheme(saved || qs || 'paper');
    $('#btn-add').onclick = openLogin;
    $('#btn-datadir').onclick = () => call('OpenDataDir').catch(() => {});
    $('#btn-theme').onclick = () => {
      const now = document.documentElement.getAttribute('data-theme') === 'ink' ? 'paper' : 'ink';
      applyTheme(now);
    };
    $('#btn-clearlog').onclick = () => { S.logs = []; renderLogs(); };

    $('#nav').addEventListener('click', (ev) => {
      const b = ev.target.closest('button[data-view]');
      if (b) switchView(b.dataset.view);
    });

    $('#accounts').addEventListener('click', (ev) => {
      const b = ev.target.closest('.acc');
      if (b) selectAccount(b.dataset.id);
    });

    $('#btn-toggle').onclick = toggleMonitor;

    $('#btn-check-now').onclick = async () => {
      if (!S.currentId) { toast('warn', '先选择账号', ''); return; }
      const btn = $('#btn-check-now');
      btn.disabled = true;
      btn.textContent = '检测中…';
      try {
        const list = await call('CheckNow', S.currentId);
        if (!list || !list.length) {
          toast('warn', '暂无签到', '当前没有进行中的活动');
        } else {
          list.forEach((a) => {
            a.__need = needOf(a);
            a.__auto = S.options.auto !== false;
            const i = S.activities.findIndex((x) => x.activeId === a.activeId);
            if (i >= 0) S.activities[i] = a; else S.activities.push(a);
          });
          renderActivities();
          beep(1);
          toast('ok', '检测完成', '发现 ' + list.length + ' 个进行中的签到');
        }
      } catch (e) {
        toast('err', '检测失败', (e && e.message) ? e.message : String(e));
      } finally {
        btn.disabled = false;
        btn.textContent = '立即检测';
      }
    };

    $('#side-auto').onchange = async () => {
      S.options.auto = $('#side-auto').checked;
      $('#set-auto').checked = S.options.auto;
      try {
        S.options = await call('SaveOptions', S.options);
        if (S.currentId) await call('UpdateOptions', S.currentId, engineOptions());
        renderPills();
        renderActivities();
        toast(S.options.auto ? 'ok' : 'warn', S.options.auto ? '自动签到已开启' : '切换为仅提醒', S.options.auto ? '命中签到后自动提交' : '只弹提醒，不代替你签到');
      } catch (e) {
        toast('err', '保存失败', (e && e.message) ? e.message : String(e));
      }
    };

    $('#act-list').addEventListener('click', (ev) => {
      const b = ev.target.closest('button[data-act]');
      if (!b) return;
      if (b.dataset.act === 'sign') signActivity(b.dataset.id);
      if (b.dataset.act === 'goto-settings') switchView('settings');
    });

    $('#act-list').addEventListener('keydown', (ev) => {
      const inp = ev.target.closest('[data-code-for]');
      if (!inp || ev.key !== 'Enter') return;
      ev.preventDefault();
      const wrap = inp.parentElement;
      const btn = wrap && wrap.querySelector('button[data-act="sign"]');
      if (btn) btn.click();
    });

    $('#btn-refresh-courses').onclick = () => loadCourses(true);

    const openDir = $('#btn-open-dir');
    if (openDir) openDir.onclick = () => call('OpenDataDir').catch(() => {});

    $('#course-grid').addEventListener('click', (ev) => {
      const c = ev.target.closest('.course');
      if (!c || !S.currentId) return;
      const all = S.courses.map((x) => x.courseId);
      let sel = selectedCourses(S.currentId);
      const id = c.dataset.course;
      if (sel.indexOf(id) >= 0) sel = sel.filter((x) => x !== id);
      else sel = all.filter((x) => sel.indexOf(x) >= 0 || x === id);
      if (!sel.length) sel = all;
      saveSelected(S.currentId, sel);
      renderCourses();
    });

    $('#btn-clear-records').onclick = async () => {
      try {
        await call('ClearRecords', '');
        S.records = [];
        renderRecords();
        toast('warn', '记录已清空', '');
      } catch (e) { toast('err', '清空失败', String(e)); }
    };

    $('#set-interval').oninput = () => { $('#set-interval-val').textContent = $('#set-interval').value + ' 秒'; };

    const pick = $('#btn-pick-map');
    if (pick) pick.onclick = openMapPicker;

    $('#btn-save-options').onclick = async () => {
      const opts = collectOptions();
      const hint = $('#settings-hint');
      try {
        S.options = await call('SaveOptions', opts);
        if (S.currentId) await call('UpdateOptions', S.currentId, engineOptions());
        renderSettings();
        renderPills();
        hint.textContent = '已保存 · ' + hhmmss(new Date().toISOString());
        toast('ok', '设置已保存', '轮询间隔 ' + S.options.interval + ' 秒');
      } catch (e) {
        hint.textContent = '保存失败';
        toast('err', '保存失败', (e && e.message) ? e.message : String(e));
      }
    };

    document.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape') closeModal();
    });

    setInterval(tickCountdown, 1000);
    setInterval(() => { renderStats(); }, 15000);
  }

  /* ---------------- 启动 ---------------- */
  async function boot() {
    bindUI();
    bindRuntime();
    try {
      const data = await call('Bootstrap');
      S.version = data.version || '1.0.0';
      S.dataPath = data.dataPath || '';
      S.accounts = data.accounts || [];
      S.records = data.records || [];
      S.options = data.options || S.options;
      S.statuses = data.statuses || {};
      $('#version').textContent = 'v' + S.version;
      renderAccounts();
      renderRecords();
      renderSettings();
      renderActivities();
      renderLogs();
      if (S.accounts.length) await selectAccount(S.accounts[0].id);
      else { renderPills(); renderStats(); renderCourses(); }
      const hash = (location.hash || '').replace('#', '');
      if (VIEW_TITLE[hash]) switchView(hash);

      /* 便于截图与调试：?picker=1 直接打开地图选点弹层 */
      if (new URLSearchParams(location.search).get('picker') === '1') {
        switchView('settings');
        openMapPicker();
      }
    } catch (e) {
      toast('err', '初始化失败', (e && e.message) ? e.message : String(e));
      renderPills();
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
