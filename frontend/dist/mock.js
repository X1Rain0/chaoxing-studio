/* 浏览器预览用的 mock 层：真实运行时 window.go 存在，这里直接退出。 */
(function () {
  if (window.go && window.go.main && window.go.main.App) {
    window.__MOCK__ = false;
    return;
  }
  window.__MOCK__ = true;

  const now = Date.now();
  const iso = (ms) => new Date(ms == null ? Date.now() : ms).toISOString();

  const demoAccounts = [
    { id: 'acc_lxz9k2', phone: '13800000001', name: '林小舟', fid: '3113', uid: '88213', enabled: true, createdAt: iso(now - 86400000 * 12), lastLogin: iso(now - 3600000) },
    { id: 'acc_cm4f7t', phone: '15900000002', name: '陈默', fid: '3113', uid: '90231', enabled: true, createdAt: iso(now - 86400000 * 6), lastLogin: iso(now - 7200000) }
  ];

  const demoCourses = [
    ['8012', '4021', '计算机网络', '计科 2101'],
    ['8013', '4022', '高等数学（下）', '计科 2101'],
    ['8014', '4023', '大学英语Ⅲ', '计科 2101'],
    ['8015', '4024', '数据结构与算法', '计科 2101'],
    ['8016', '4025', '马克思主义基本原理', '计科 2101'],
    ['8017', '4026', '概率论与数理统计', '计科 2102'],
    ['8018', '4027', '操作系统', '计科 2101'],
    ['8019', '4028', '体育（羽毛球）', '公体 06 班']
  ].map(([courseId, classId, courseName, className]) => ({
    courseId, classId, chatId: '', courseName, className
  }));

  const kindLabel = ['普通签到', '拍照签到', '二维码签到', '手势签到', '位置签到', '签到码'];

  const demoActivity = {
    activeId: '9214503',
    name: '第 12 次课 签到',
    otherId: 5,
    kind: 5,
    kindName: '签到码',
    course: demoCourses[0],
    startAt: iso(now - 180000),
    isPhoto: false
  };

  const demoActivity2 = {
    activeId: '9214488',
    name: '随堂位置考勤',
    otherId: 4,
    kind: 4,
    kindName: '位置签到',
    course: demoCourses[6],
    startAt: iso(now - 540000),
    isPhoto: false
  };

  let demoRecords = [
    ['计算机网络', '计科 2101', '签到码', true, 'success', now - 1000 * 60 * 4],
    ['操作系统', '计科 2101', '位置签到', true, 'success', now - 1000 * 60 * 47],
    ['大学英语Ⅲ', '计科 2101', '普通签到', true, 'success', now - 1000 * 60 * 96],
    ['数据结构与算法', '计科 2101', '二维码签到', false, '活动已结束', now - 1000 * 60 * 128],
    ['马克思主义基本原理', '计科 2101', '手势签到', true, 'success', now - 1000 * 60 * 210],
    ['概率论与数理统计', '计科 2102', '普通签到', true, 'success', now - 1000 * 60 * 285],
    ['计算机网络', '计科 2101', '普通签到', true, 'success', now - 1000 * 60 * 320],
    ['体育（羽毛球）', '公体 06 班', '位置签到', false, '缺少签到位置', now - 1000 * 60 * 400]
  ].map(([course, className, kind, ok, message, at], i) => ({
    id: 'rec_' + i, accountId: demoAccounts[0].id, course, className, kind, ok, message, at: iso(at)
  }));

  let demoOptions = { interval: 60, auto: true, signCode: '', latitude: '39.904200', longitude: '116.407400', address: '主校区 图书馆 4 层' };

  let demoStatuses = {
    [demoAccounts[0].id]: { accountId: demoAccounts[0].id, running: true, interval: 60, lastTick: iso(now - 12000), lastMessage: '扫描 8 门课，发现 2 个活动', signs: 3, checks: 27, failures: 0, startedAt: iso(now - 1000 * 60 * 28) },
    [demoAccounts[1].id]: { accountId: demoAccounts[1].id, running: false, interval: 60, lastTick: iso(now - 1000 * 60 * 20), lastMessage: '监听已停止', signs: 0, checks: 4, failures: 0, startedAt: iso(0) }
  };

  const listeners = {};
  function emit(name, payload) {
    (listeners[name] || []).forEach((fn) => { try { fn(payload); } catch (e) { console.error(e); } });
  }

  window.runtime = {
    EventsOn(name, cb) {
      (listeners[name] = listeners[name] || []).push(cb);
    },
    EventsOff(name) { delete listeners[name]; },
    EventsEmit(name, payload) { emit(name, payload); },
    WindowSetTitle(t) { document.title = t; }
  };

  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const clone = (v) => JSON.parse(JSON.stringify(v));

  const App = {
    async Bootstrap() {
      return {
        version: '1.1.0',
        dataPath: 'C:\\Users\\you\\AppData\\Roaming\\ChaoxingStudio\\data.json',
        accounts: clone(demoAccounts),
        records: clone(demoRecords),
        options: clone(demoOptions),
        statuses: clone(demoStatuses),
        now: iso()
      };
    },
    async Login(phone, password) {
      await wait(160);
      if (!phone || !password) throw new Error('手机号和密码不能为空');
      const acc = { id: 'acc_' + Math.random().toString(16).slice(2, 8), phone, name: '预览账号', fid: '3113', uid: '00000', enabled: true, createdAt: iso(), lastLogin: iso() };
      demoAccounts.push(acc);
      return { account: clone(acc), courses: clone(demoCourses) };
    },
    async RemoveAccount(id) {
      const i = demoAccounts.findIndex((a) => a.id === id);
      if (i >= 0) demoAccounts.splice(i, 1);
      return null;
    },
    async SetAccountEnabled() { return null; },
    async Courses() { return clone(demoCourses); },
    async RefreshCourses() { await wait(220); return clone(demoCourses); },
    async StartMonitor(id, opts) {
      demoStatuses[id] = { accountId: id, running: true, interval: (opts && opts.interval) || 60, lastTick: iso(), lastMessage: '监听已启动', signs: 0, checks: 0, failures: 0, startedAt: iso() };
      return clone(demoStatuses[id]);
    },
    async StopMonitor(id) { demoStatuses[id] = Object.assign({}, demoStatuses[id], { running: false, lastMessage: '监听已停止' }); return null; },
    async UpdateOptions() { return null; },
    async Status(id) { return clone(demoStatuses[id] || { accountId: id, running: false }); },
    async CheckNow() { await wait(420); return [clone(demoActivity), clone(demoActivity2)]; },
    async SignNow(id, act) {
      await wait(520);
      const ok = act.otherId !== 4;
      demoRecords.unshift({ id: 'rec_' + Math.random().toString(16).slice(2, 8), accountId: id, course: act.course.courseName, className: act.course.className, kind: kindLabel[act.kind], ok, message: ok ? 'success' : '缺少签到位置', at: iso() });
      return { ok, message: ok ? 'success' : '缺少签到位置', raw: ok ? 'success' : '{"status":false}', kind: act.kind, kindName: kindLabel[act.kind], activeId: act.activeId, at: iso(), duration: 820 };
    },
    async Records() { return clone(demoRecords); },
    async ClearRecords() { demoRecords = []; return null; },
    async Options() { return clone(demoOptions); },
    async SaveOptions(o) { demoOptions = Object.assign({}, demoOptions, o); return clone(demoOptions); },
    async PlaceSearch(keyword) {
      await wait(220);
      const k = String(keyword || '');
      return [
        { name: k || '图书馆', address: '主校区 ' + (k || '图书馆') + ' 东门', region: '示例省 示例市 示例区', latitude: 39.905210, longitude: 116.408930 },
        { name: (k || '图书馆') + '（西门）', address: '主校区 求是大道 ' + (k || '') , region: '示例省 示例市 示例区', latitude: 39.903870, longitude: 116.405120 }
      ];
    },
    async PlaceReverse(latitude, longitude) {
      await wait(180);
      return { name: '', address: '主校区 图书馆 4 层（' + Number(latitude).toFixed(4) + ', ' + Number(longitude).toFixed(4) + '）', region: '示例省 示例市 示例区', latitude, longitude };
    },
    async DataPath() { return 'C:\\Users\\you\\AppData\\Roaming\\ChaoxingStudio\\data.json'; },
    async OpenDataDir() { return null; },
    async Version() { return '1.1.0'; }
  };

  window.go = { main: { App } };

  /* ---- 演示用事件流 ---- */
  const started = Date.now();
  const seedLogs = [
    ['info', '加载本地配置：8 门课程待巡检'],
    ['info', '会话已从本地凭据恢复'],
    ['info', '监听已启动：每 60 秒扫描一次，命中即自动签到'],
    ['info', '扫描 8 门课，暂无签到'],
    ['info', '扫描 8 门课，暂无签到'],
    ['activity', '发现签到：计算机网络 · 计科 2101 · 位置签到（需要设置签到位置）'],
    ['error', '✘ 签到失败：马克思主义基本原理 · 手势签到 — 需要手势轨迹编码'],
    ['info', '扫描 8 门课，发现 1 个活动'],
    ['sign', '✔ 签到成功：操作系统 · 位置签到'],
    ['info', '扫描 8 门课，暂无签到'],
    ['info', '扫描 8 门课，暂无签到'],
    ['activity', '发现签到：计算机网络 · 签到码'],
    ['sign', '✔ 签到成功：计算机网络 · 签到码'],
    ['info', '扫描 8 门课，发现 2 个活动']
  ];

  setTimeout(() => {
    seedLogs.forEach(([level, message], i) => {
      emit('log', { accountId: demoAccounts[0].id, level, message, at: iso(now - (seedLogs.length - i) * 42000) });
    });
    emit('status', demoStatuses[demoAccounts[0].id]);
  }, 60);

  const liveLines = [
    ['info', '扫描 8 门课，发现 2 个活动'],
    ['info', '扫描 8 门课，暂无签到'],
    ['info', '会话心跳正常，cookie 有效'],
    ['info', '扫描 8 门课，暂无签到']
  ];
  let n = 0;
  setInterval(() => {
    const [level, message] = liveLines[n % liveLines.length];
    n++;
    emit('log', { accountId: demoAccounts[0].id, level, message, at: iso() });
    const st = demoStatuses[demoAccounts[0].id];
    if (st) {
      st.lastTick = iso();
      st.checks += 1;
      st.lastMessage = message;
      emit('status', clone(st));
    }
  }, 9000);

  setTimeout(() => {
    emit('activity', { accountId: demoAccounts[0].id, activity: clone(demoActivity), need: '', auto: true });
  }, 1600);

  window.__MOCK_START__ = started;
})();
