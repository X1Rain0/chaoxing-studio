package main

import (
	"context"
	"errors"
	"os/exec"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"

	"chaoxing-studio/internal/chaoxing"
	"chaoxing-studio/internal/engine"
	"chaoxing-studio/internal/store"
)

const appVersion = "1.0.0"

// App 是 Wails 绑定层：只做参数校验与事件转发，业务逻辑全在 internal/*。
type App struct {
	ctx context.Context
	st  *store.Store
	eng *engine.Engine
}

func NewApp() *App {
	st, err := store.Open()
	if err != nil {
		fatalDialog("无法初始化本地数据目录：\n" + err.Error())
	}
	return &App{st: st, eng: engine.New(st)}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	a.eng.SetEmitter(func(event string, payload any) {
		runtime.EventsEmit(ctx, event, payload)
	})
	a.eng.Restore()
}

func (a *App) shutdown(ctx context.Context) {
	a.eng.Shutdown()
	_ = a.st.Save()
}

// ---------- 启动数据 ----------

type BootstrapData struct {
	Version  string                   `json:"version"`
	DataPath string                   `json:"dataPath"`
	Accounts []store.Account          `json:"accounts"`
	Records  []store.Record           `json:"records"`
	Options  store.Options            `json:"options"`
	Statuses map[string]engine.Status `json:"statuses"`
	Now      time.Time                `json:"now"`
}

func publicAccount(a store.Account) store.Account {
	a.Secret = ""
	return a
}

func (a *App) Bootstrap() (BootstrapData, error) {
	out := BootstrapData{
		Version:  appVersion,
		DataPath: a.st.Path(),
		Options:  a.st.Options(),
		Accounts: []store.Account{},
		Records:  a.st.Records("", 80),
		Statuses: map[string]engine.Status{},
		Now:      time.Now(),
	}
	for _, acc := range a.st.Accounts() {
		out.Accounts = append(out.Accounts, publicAccount(acc))
		out.Statuses[acc.ID] = a.eng.Status(acc.ID)
	}
	return out, nil
}

// ---------- 账号 ----------

type LoginResult struct {
	Account store.Account     `json:"account"`
	Courses []chaoxing.Course `json:"courses"`
}

func (a *App) Login(phone, password string) (LoginResult, error) {
	acc, courses, err := a.eng.Login(phone, password)
	if err != nil {
		return LoginResult{}, err
	}
	if courses == nil {
		courses = []chaoxing.Course{}
	}
	return LoginResult{Account: publicAccount(acc), Courses: courses}, nil
}

func (a *App) RemoveAccount(accountID string) error {
	if accountID == "" {
		return errors.New("账号 ID 为空")
	}
	return a.eng.RemoveAccount(accountID)
}

func (a *App) SetAccountEnabled(accountID string, enabled bool) error {
	acc, ok := a.st.FindAccount(accountID)
	if !ok {
		return errors.New("账号不存在")
	}
	acc.Enabled = enabled
	return a.st.UpsertAccount(acc)
}

// ---------- 课程 ----------

func (a *App) Courses(accountID string) ([]chaoxing.Course, error) {
	list, err := a.eng.Courses(accountID)
	if list == nil {
		list = []chaoxing.Course{}
	}
	return list, err
}

func (a *App) RefreshCourses(accountID string) ([]chaoxing.Course, error) {
	list, err := a.eng.RefreshCourses(accountID)
	if list == nil {
		list = []chaoxing.Course{}
	}
	return list, err
}

// ---------- 监听 ----------

func (a *App) StartMonitor(accountID string, opts engine.Options) (engine.Status, error) {
	base := a.st.Options()
	if opts.Interval <= 0 {
		opts.Interval = base.Interval
	}
	if opts.SignCode == "" {
		opts.SignCode = base.SignCode
	}
	if opts.Latitude == "" {
		opts.Latitude = base.Latitude
	}
	if opts.Longitude == "" {
		opts.Longitude = base.Longitude
	}
	if opts.Address == "" {
		opts.Address = base.Address
	}
	return a.eng.StartMonitor(accountID, opts)
}

func (a *App) StopMonitor(accountID string) error {
	return a.eng.StopMonitor(accountID)
}

func (a *App) UpdateOptions(accountID string, opts engine.Options) error {
	return a.eng.UpdateOptions(accountID, opts)
}

func (a *App) Status(accountID string) engine.Status {
	return a.eng.Status(accountID)
}

func (a *App) CheckNow(accountID string) ([]chaoxing.Activity, error) {
	list, err := a.eng.CheckNow(accountID)
	if list == nil {
		list = []chaoxing.Activity{}
	}
	return list, err
}

func (a *App) SignNow(accountID string, act chaoxing.Activity, opts engine.Options) (chaoxing.SignResult, error) {
	return a.eng.SignNow(accountID, act, opts)
}

// ---------- 记录 / 设置 ----------

func (a *App) Records(accountID string, limit int) []store.Record {
	if limit <= 0 {
		limit = 80
	}
	out := a.st.Records(accountID, limit)
	if out == nil {
		out = []store.Record{}
	}
	return out
}

func (a *App) ClearRecords(accountID string) error {
	return a.st.ClearRecords(accountID)
}

func (a *App) Options() store.Options {
	return a.st.Options()
}

func (a *App) SaveOptions(opts store.Options) (store.Options, error) {
	if opts.Interval < 10 {
		opts.Interval = 10
	}
	if opts.Interval > 3600 {
		opts.Interval = 3600
	}
	if err := a.st.SaveOptions(opts); err != nil {
		return opts, err
	}
	saved := a.st.Options()
	a.eng.ApplyGlobalOptions(saved)
	return saved, nil
}

// ---------- 杂项 ----------

func (a *App) DataPath() string { return a.st.Path() }

func (a *App) OpenDataDir() error {
	return exec.Command("explorer", "/select,", a.st.Path()).Start()
}

func (a *App) Version() string { return appVersion }
