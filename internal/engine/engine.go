package engine

import (
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"chaoxing-studio/internal/chaoxing"
	"chaoxing-studio/internal/store"
)

type Emitter func(event string, payload any)

type emitterHolder struct{ fn Emitter }

type LogEvent struct {
	AccountID string    `json:"accountId"`
	Level     string    `json:"level"`
	Message   string    `json:"message"`
	At        time.Time `json:"at"`
}

type ActivityEvent struct {
	AccountID string            `json:"accountId"`
	Activity  chaoxing.Activity `json:"activity"`
	Need      string            `json:"need"`
	Auto      bool              `json:"auto"`
}

type SignEvent struct {
	AccountID string              `json:"accountId"`
	Activity  chaoxing.Activity   `json:"activity"`
	Result    chaoxing.SignResult `json:"result"`
	Error     string              `json:"error"`
}

type Status struct {
	AccountID   string    `json:"accountId"`
	Running     bool      `json:"running"`
	Interval    int       `json:"interval"`
	LastTick    time.Time `json:"lastTick"`
	LastMessage string    `json:"lastMessage"`
	Signs       int       `json:"signs"`
	Checks      int       `json:"checks"`
	Failures    int       `json:"failures"`
	StartedAt   time.Time `json:"startedAt"`
}

type Options struct {
	Interval  int      `json:"interval"`
	Auto      bool     `json:"auto"`
	SignCode  string   `json:"signCode"`
	Latitude  string   `json:"latitude"`
	Longitude string   `json:"longitude"`
	Address   string   `json:"address"`
	Enc       string   `json:"enc"`
	CourseIDs []string `json:"courseIds"`
}

type Session struct {
	acc    store.Account
	client *chaoxing.Client

	mu        sync.Mutex
	courses   []chaoxing.Course
	coursesAt time.Time
	mon       *Monitor
	signed    map[string]time.Time
	attempts  map[string]int
	notified  map[string]bool
}

type Monitor struct {
	stop      chan struct{}
	done      chan struct{}
	closeOnce sync.Once

	mu        sync.RWMutex
	opts      Options
	lastTick  time.Time
	lastMsg   string
	signs     int
	checks    int
	failures  int
	startedAt time.Time
}

func (m *Monitor) close() {
	m.closeOnce.Do(func() { close(m.stop) })
}

func (m *Monitor) fail() {
	m.mu.Lock()
	m.failures++
	m.mu.Unlock()
}

func (m *Monitor) ok() {
	m.mu.Lock()
	if m.failures > 0 {
		m.failures = 0
	}
	m.mu.Unlock()
}

type Engine struct {
	st *store.Store
	em atomic.Value

	mu       sync.RWMutex
	sessions map[string]*Session
}

func New(st *store.Store) *Engine {
	e := &Engine{st: st, sessions: make(map[string]*Session)}
	e.em.Store(emitterHolder{})
	return e
}

func (e *Engine) SetEmitter(fn Emitter) { e.em.Store(emitterHolder{fn: fn}) }

func (e *Engine) fire(event string, payload any) {
	if v, ok := e.em.Load().(emitterHolder); ok && v.fn != nil {
		v.fn(event, payload)
	}
}

func (e *Engine) log(accountID, level, msg string) {
	e.fire("log", LogEvent{AccountID: accountID, Level: level, Message: msg, At: time.Now()})
}

// Login 登录新账号（同一手机号复用已有账号 ID）。
func (e *Engine) Login(phone, password string) (store.Account, []chaoxing.Course, error) {
	phone = trim(phone)
	if phone == "" || password == "" {
		return store.Account{}, nil, fmt.Errorf("手机号和密码不能为空")
	}
	client := chaoxing.NewClient(chaoxing.Credential{Phone: phone, Password: password},
		func(level, msg string) { e.log("", level, msg) })
	if err := client.Login(); err != nil {
		return store.Account{}, nil, err
	}
	courses, err := client.Courses()
	if err != nil {
		client.Close()
		return store.Account{}, nil, err
	}

	acc := store.Account{
		ID:        store.NewID("acc"),
		Phone:     phone,
		Name:      client.Name(),
		FID:       client.FID(),
		UID:       client.UID(),
		Enabled:   true,
		CreatedAt: time.Now(),
		LastLogin: time.Now(),
	}
	if old, ok := e.findByPhone(phone); ok {
		acc.ID = old.ID
		acc.CreatedAt = old.CreatedAt
	}
	if err := e.st.SetPassword(&acc, password); err != nil {
		client.Close()
		return store.Account{}, nil, fmt.Errorf("保存凭据失败: %w", err)
	}
	if err := e.st.UpsertAccount(acc); err != nil {
		client.Close()
		return store.Account{}, nil, err
	}

	s := &Session{
		acc: acc, client: client,
		courses: courses, coursesAt: time.Now(),
		signed: make(map[string]time.Time), attempts: make(map[string]int),
		notified: make(map[string]bool),
	}
	e.mu.Lock()
	if prev, ok := e.sessions[acc.ID]; ok {
		prev.client.Close()
		s.signed = prev.signed
		s.attempts = prev.attempts
	}
	e.sessions[acc.ID] = s
	e.mu.Unlock()

	e.fire("courses", map[string]any{"accountId": acc.ID, "courses": courses})
	e.log(acc.ID, "info", fmt.Sprintf("已登录 %s，共 %d 门课程", acc.Name, len(courses)))
	return acc, courses, nil
}

// Restore 在启动时按已保存的凭据恢复会话（后台进行，不阻塞 UI）。
func (e *Engine) Restore() {
	for _, acc := range e.st.Accounts() {
		if !acc.Enabled {
			continue
		}
		acc := acc
		go func() {
			pwd, err := e.st.Password(acc)
			if err != nil || pwd == "" {
				e.log(acc.ID, "warn", "已保存的密码无法解密，请重新登录")
				return
			}
			if _, err := e.ensureSession(acc, pwd); err != nil {
				e.log(acc.ID, "warn", "自动恢复登录失败: "+err.Error())
				return
			}
			e.log(acc.ID, "info", "会话已从本地凭据恢复")
		}()
	}
}

func (e *Engine) findByPhone(phone string) (store.Account, bool) {
	for _, a := range e.st.Accounts() {
		if a.Phone == phone {
			return a, true
		}
	}
	return store.Account{}, false
}

func (e *Engine) session(accountID string) (*Session, error) {
	e.mu.RLock()
	s, ok := e.sessions[accountID]
	e.mu.RUnlock()
	if ok && s != nil {
		return s, nil
	}
	acc, found := e.st.FindAccount(accountID)
	if !found {
		return nil, fmt.Errorf("账号不存在")
	}
	pwd, err := e.st.Password(acc)
	if err != nil || pwd == "" {
		return nil, fmt.Errorf("本地凭据不可用，请重新登录")
	}
	return e.ensureSession(acc, pwd)
}

func (e *Engine) ensureSession(acc store.Account, pwd string) (*Session, error) {
	e.mu.RLock()
	existing := e.sessions[acc.ID]
	e.mu.RUnlock()
	if existing != nil {
		return existing, nil
	}

	client := chaoxing.NewClient(chaoxing.Credential{Phone: acc.Phone, Password: pwd},
		func(level, msg string) { e.log(acc.ID, level, msg) })
	if err := client.Login(); err != nil {
		client.Close()
		return nil, err
	}
	s := &Session{
		acc: acc, client: client,
		signed: make(map[string]time.Time), attempts: make(map[string]int),
		notified: make(map[string]bool),
	}
	e.mu.Lock()
	if cur, ok := e.sessions[acc.ID]; ok {
		e.mu.Unlock()
		client.Close()
		return cur, nil
	}
	e.sessions[acc.ID] = s
	e.mu.Unlock()
	return s, nil
}

func (e *Engine) RemoveAccount(accountID string) error {
	_ = e.StopMonitor(accountID)
	e.mu.Lock()
	if s, ok := e.sessions[accountID]; ok {
		s.client.Close()
		delete(e.sessions, accountID)
	}
	e.mu.Unlock()
	return e.st.RemoveAccount(accountID)
}

func (e *Engine) Courses(accountID string) ([]chaoxing.Course, error) {
	s, err := e.session(accountID)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	courses, at := s.courses, s.coursesAt
	s.mu.Unlock()
	if len(courses) > 0 && time.Since(at) < 5*time.Minute {
		return courses, nil
	}
	return e.RefreshCourses(accountID)
}

func (e *Engine) RefreshCourses(accountID string) ([]chaoxing.Course, error) {
	s, err := e.session(accountID)
	if err != nil {
		return nil, err
	}
	courses, err := s.client.Courses()
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.courses, s.coursesAt = courses, time.Now()
	s.mu.Unlock()
	e.fire("courses", map[string]any{"accountId": accountID, "courses": courses})
	return courses, nil
}

func (e *Engine) ensureCourses(s *Session) ([]chaoxing.Course, error) {
	s.mu.Lock()
	courses, at := s.courses, s.coursesAt
	s.mu.Unlock()
	if len(courses) > 0 && time.Since(at) < 10*time.Minute {
		return courses, nil
	}
	courses, err := s.client.Courses()
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.courses, s.coursesAt = courses, time.Now()
	s.mu.Unlock()
	e.fire("courses", map[string]any{"accountId": s.acc.ID, "courses": courses})
	return courses, nil
}

func (e *Engine) StartMonitor(accountID string, opts Options) (Status, error) {
	s, err := e.session(accountID)
	if err != nil {
		return Status{}, err
	}
	if opts.Interval < 10 {
		opts.Interval = 10
	}
	m := &Monitor{
		stop: make(chan struct{}), done: make(chan struct{}),
		opts: opts, startedAt: time.Now(),
	}
	s.mu.Lock()
	prev := s.mon
	s.mon = m
	s.mu.Unlock()
	if prev != nil {
		prev.close()
	}
	go e.run(s, m)
	e.log(accountID, "info", fmt.Sprintf("监听已启动：每 %d 秒扫描一次%s",
		opts.Interval, map[bool]string{true: "，命中即自动签到", false: "，仅提醒不自动签"}[opts.Auto]))
	e.fire("status", e.Status(accountID))
	return e.Status(accountID), nil
}

func (e *Engine) StopMonitor(accountID string) error {
	e.mu.RLock()
	s := e.sessions[accountID]
	e.mu.RUnlock()
	if s == nil {
		return nil
	}
	s.mu.Lock()
	m := s.mon
	s.mon = nil
	s.mu.Unlock()
	if m != nil {
		m.close()
	}
	e.log(accountID, "info", "监听已停止")
	e.fire("status", Status{AccountID: accountID})
	return nil
}

func (e *Engine) UpdateOptions(accountID string, opts Options) error {
	s, err := e.session(accountID)
	if err != nil {
		return err
	}
	if opts.Interval < 10 {
		opts.Interval = 10
	}
	s.mu.Lock()
	m := s.mon
	s.mu.Unlock()
	if m != nil {
		m.mu.Lock()
		m.opts = opts
		m.mu.Unlock()
	}
	e.fire("status", e.Status(accountID))
	return nil
}

// ApplyGlobalOptions 把全局设置热更新到所有正在监听的会话。
// 只覆盖与全局共享的字段（轮询间隔、自动签到、签到码、位置），
// 每个会话各自的课程选择（CourseIDs）与二维码 enc 保持不变。
func (e *Engine) ApplyGlobalOptions(g store.Options) {
	if g.Interval < 10 {
		g.Interval = 10
	}
	e.mu.RLock()
	ids := make([]string, 0, len(e.sessions))
	for id := range e.sessions {
		ids = append(ids, id)
	}
	e.mu.RUnlock()
	for _, id := range ids {
		s, err := e.session(id)
		if err != nil {
			continue
		}
		s.mu.Lock()
		m := s.mon
		s.mu.Unlock()
		if m == nil {
			continue
		}
		m.mu.Lock()
		m.opts.Interval = g.Interval
		m.opts.Auto = g.Auto
		m.opts.SignCode = g.SignCode
		m.opts.Latitude = g.Latitude
		m.opts.Longitude = g.Longitude
		m.opts.Address = g.Address
		m.mu.Unlock()
		e.fire("status", e.Status(id))
	}
}

func (e *Engine) Status(accountID string) Status {
	e.mu.RLock()
	s := e.sessions[accountID]
	e.mu.RUnlock()
	st := Status{AccountID: accountID}
	if s == nil {
		return st
	}
	s.mu.Lock()
	m := s.mon
	s.mu.Unlock()
	if m == nil {
		return st
	}
	m.mu.RLock()
	st.Running = true
	st.Interval = m.opts.Interval
	st.LastTick = m.lastTick
	st.LastMessage = m.lastMsg
	st.Signs = m.signs
	st.Checks = m.checks
	st.Failures = m.failures
	st.StartedAt = m.startedAt
	m.mu.RUnlock()
	return st
}

func (e *Engine) run(s *Session, m *Monitor) {
	defer close(m.done)
	for {
		e.tick(s, m)
		m.mu.RLock()
		interval := m.opts.Interval
		failures := m.failures
		m.mu.RUnlock()
		wait := interval
		if failures >= 3 {
			mult := failures - 2
			if mult > 4 {
				mult = 4
			}
			wait = interval * mult
			if wait > 300 {
				wait = 300
			}
		}
		select {
		case <-m.stop:
			return
		case <-time.After(time.Duration(wait) * time.Second):
		}
	}
}

func (e *Engine) tick(s *Session, m *Monitor) {
	start := time.Now()
	m.mu.Lock()
	m.lastTick = start
	m.checks++
	opts := m.opts
	m.mu.Unlock()

	courses, err := e.ensureCourses(s)
	if err != nil {
		m.fail()
		e.log(s.acc.ID, "error", "拉取课程失败: "+err.Error())
		e.fire("status", e.Status(s.acc.ID))
		return
	}

	scanned, found := 0, 0
	for _, c := range courses {
		if len(opts.CourseIDs) > 0 && !contains(opts.CourseIDs, c.CourseID) {
			continue
		}
		acts, err := s.client.Activities(c.CourseID, c.ClassID)
		if err != nil {
			m.fail()
			e.log(s.acc.ID, "error", fmt.Sprintf("检测「%s」失败: %v", c.Title(), err))
			continue
		}
		m.ok()
		scanned++
		for _, a := range acts {
			a.Course = c
			found++
			e.handleActivity(s, m, a, opts)
		}
	}

	msg := fmt.Sprintf("扫描 %d 门课，发现 %d 个活动", scanned, found)
	if found == 0 {
		msg = fmt.Sprintf("扫描 %d 门课，暂无签到", scanned)
	}
	m.mu.Lock()
	m.lastMsg = msg
	m.mu.Unlock()
	e.log(s.acc.ID, "info", msg)
	e.fire("status", e.Status(s.acc.ID))
}

func (e *Engine) handleActivity(s *Session, m *Monitor, a chaoxing.Activity, opts Options) {
	s.mu.Lock()
	if t, ok := s.signed[a.ActiveID]; ok && time.Since(t) < 3*time.Hour {
		s.mu.Unlock()
		return
	}
	if s.attempts[a.ActiveID] >= 6 {
		s.mu.Unlock()
		return
	}
	first := !s.notified[a.ActiveID]
	s.notified[a.ActiveID] = true
	s.mu.Unlock()

	need := missing(a, opts)
	if first {
		e.fire("activity", ActivityEvent{AccountID: s.acc.ID, Activity: a, Need: need, Auto: opts.Auto})
		extra := ""
		if need != "" {
			extra = "（" + need + "）"
		}
		e.log(s.acc.ID, "activity", fmt.Sprintf("发现签到：%s · %s%s", a.Course.Title(), a.KindName, extra))
	}
	if !opts.Auto || need != "" {
		return
	}

	res, signErr := s.client.Sign(a, chaoxing.SignInput{
		SignCode: opts.SignCode,
		Location: chaoxing.Location{Latitude: opts.Latitude, Longitude: opts.Longitude, Address: opts.Address},
		Enc:      opts.Enc,
	})

	s.mu.Lock()
	if signErr == nil && res.OK {
		s.signed[a.ActiveID] = time.Now()
	}
	s.attempts[a.ActiveID]++
	s.mu.Unlock()

	msg := res.Message
	if signErr != nil && msg == "" {
		msg = signErr.Error()
	}
	_ = e.st.AddRecord(store.Record{
		AccountID: s.acc.ID,
		Course:    a.Course.CourseName,
		ClassName: a.Course.ClassName,
		Kind:      a.KindName,
		OK:        res.OK && signErr == nil,
		Message:   msg,
		At:        time.Now(),
	})
	if res.OK && signErr == nil {
		m.mu.Lock()
		m.signs++
		m.mu.Unlock()
		e.log(s.acc.ID, "sign", fmt.Sprintf("✔ 签到成功：%s · %s", a.Course.Title(), a.KindName))
	} else {
		e.log(s.acc.ID, "error", fmt.Sprintf("✘ 签到失败：%s · %s — %s", a.Course.Title(), a.KindName, msg))
	}
	errStr := ""
	if signErr != nil {
		errStr = signErr.Error()
	}
	e.fire("sign", SignEvent{AccountID: s.acc.ID, Activity: a, Result: res, Error: errStr})
}

func (e *Engine) CheckNow(accountID string) ([]chaoxing.Activity, error) {
	s, err := e.session(accountID)
	if err != nil {
		return nil, err
	}
	courses, err := e.RefreshCourses(accountID)
	if err != nil {
		return nil, err
	}
	out := make([]chaoxing.Activity, 0, 4)
	for _, c := range courses {
		acts, err := s.client.Activities(c.CourseID, c.ClassID)
		if err != nil {
			continue
		}
		for _, a := range acts {
			a.Course = c
			out = append(out, a)
		}
	}
	return out, nil
}

func (e *Engine) SignNow(accountID string, a chaoxing.Activity, opts Options) (chaoxing.SignResult, error) {
	s, err := e.session(accountID)
	if err != nil {
		return chaoxing.SignResult{}, err
	}
	res, signErr := s.client.Sign(a, chaoxing.SignInput{
		SignCode: opts.SignCode,
		Location: chaoxing.Location{Latitude: opts.Latitude, Longitude: opts.Longitude, Address: opts.Address},
		Enc:      opts.Enc,
	})
	msg := res.Message
	if signErr != nil && msg == "" {
		msg = signErr.Error()
	}
	_ = e.st.AddRecord(store.Record{
		AccountID: accountID,
		Course:    a.Course.CourseName,
		ClassName: a.Course.ClassName,
		Kind:      a.KindName,
		OK:        res.OK && signErr == nil,
		Message:   msg,
		At:        time.Now(),
	})
	if res.OK && signErr == nil {
		s.mu.Lock()
		s.signed[a.ActiveID] = time.Now()
		s.mu.Unlock()
		e.log(accountID, "sign", fmt.Sprintf("✔ 手动签到成功：%s · %s", a.Course.Title(), a.KindName))
	}
	e.fire("sign", SignEvent{AccountID: accountID, Activity: a, Result: res, Error: errString(signErr)})
	return res, signErr
}

func (e *Engine) Shutdown() {
	e.mu.Lock()
	sessions := make([]*Session, 0, len(e.sessions))
	for _, s := range e.sessions {
		sessions = append(sessions, s)
	}
	e.mu.Unlock()
	for _, s := range sessions {
		s.mu.Lock()
		m := s.mon
		s.mon = nil
		s.mu.Unlock()
		if m != nil {
			m.close()
		}
		s.client.Close()
	}
}

func missing(a chaoxing.Activity, opts Options) string {
	switch a.Kind {
	case chaoxing.KindGesture:
		if trim(opts.SignCode) == "" {
			return "需要手势轨迹编码"
		}
	case chaoxing.KindCode:
		if trim(opts.SignCode) == "" {
			return "需要老师公布的签到码"
		}
	case chaoxing.KindLocation:
		if trim(opts.Latitude) == "" || trim(opts.Longitude) == "" {
			return "需要设置签到位置"
		}
	case chaoxing.KindQR:
		if trim(opts.Enc) == "" {
			return "需要二维码 enc 参数"
		}
	}
	return ""
}

func contains(list []string, v string) bool {
	for _, s := range list {
		if s == v {
			return true
		}
	}
	return false
}

func trim(s string) string {
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if r == ' ' || r == '\t' || r == '\n' || r == '\r' {
			continue
		}
		out = append(out, r)
	}
	return string(out)
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
