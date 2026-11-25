package store

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	version      = 1
	maxRecords   = 800
	appDataDir   = "ChaoxingStudio"
	defaultEvery = 60
)

var errDataDirUnavailable = errors.New("找不到可写入的数据目录（%APPDATA% 与程序目录都不可写）")

type Account struct {
	ID        string    `json:"id"`
	Phone     string    `json:"phone"`
	Name      string    `json:"name"`
	FID       string    `json:"fid"`
	UID       string    `json:"uid"`
	Enabled   bool      `json:"enabled"`
	CreatedAt time.Time `json:"createdAt"`
	LastLogin time.Time `json:"lastLogin"`
	Secret    string    `json:"secret"`
}

type Record struct {
	ID        string    `json:"id"`
	AccountID string    `json:"accountId"`
	Course    string    `json:"course"`
	ClassName string    `json:"className"`
	Kind      string    `json:"kind"`
	OK        bool      `json:"ok"`
	Message   string    `json:"message"`
	At        time.Time `json:"at"`
}

type Options struct {
	Interval  int    `json:"interval"`
	Auto      bool   `json:"auto"`
	SignCode  string `json:"signCode"`
	Latitude  string `json:"latitude"`
	Longitude string `json:"longitude"`
	Address   string `json:"address"`
}

func DefaultOptions() Options {
	return Options{Interval: defaultEvery, Auto: true}
}

type Data struct {
	Version  int       `json:"version"`
	Accounts []Account `json:"accounts"`
	Records  []Record  `json:"records"`
	Options  Options   `json:"options"`
}

type Store struct {
	mu   sync.RWMutex
	path string
	data Data
}

func Open() (*Store, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return nil, err
	}
	candidates := []string{filepath.Join(base, appDataDir)}
	if exe, err := os.Executable(); err == nil {
		candidates = append(candidates, filepath.Join(filepath.Dir(exe), "data"))
	}
	candidates = append(candidates, filepath.Join(os.TempDir(), appDataDir))

	var dir string
	for _, cand := range candidates {
		if err := os.MkdirAll(cand, 0o755); err != nil {
			continue
		}
		probe := filepath.Join(cand, ".writetest")
		if err := os.WriteFile(probe, []byte("ok"), 0o644); err != nil {
			continue
		}
		_ = os.Remove(probe)
		dir = cand
		break
	}
	if dir == "" {
		return nil, errDataDirUnavailable
	}
	s := &Store{path: filepath.Join(dir, "data.json")}
	if raw, err := os.ReadFile(s.path); err == nil && len(raw) > 0 {
		_ = json.Unmarshal(raw, &s.data)
	}
	if s.data.Version == 0 {
		s.data.Version = version
	}
	if s.data.Options.Interval <= 0 {
		s.data.Options = DefaultOptions()
	}
	return s, nil
}

func (s *Store) Path() string { return s.path }

func (s *Store) saveLocked() error {
	raw, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *Store) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.saveLocked()
}

func (s *Store) Accounts() []Account {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Account, len(s.data.Accounts))
	copy(out, s.data.Accounts)
	return out
}

func (s *Store) FindAccount(id string) (Account, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, a := range s.data.Accounts {
		if a.ID == id {
			return a, true
		}
	}
	return Account{}, false
}

func (s *Store) UpsertAccount(a Account) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.data.Accounts {
		if s.data.Accounts[i].ID == a.ID {
			s.data.Accounts[i] = a
			return s.saveLocked()
		}
	}
	s.data.Accounts = append(s.data.Accounts, a)
	return s.saveLocked()
}

func (s *Store) RemoveAccount(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := s.data.Accounts[:0]
	for _, a := range s.data.Accounts {
		if a.ID != id {
			out = append(out, a)
		}
	}
	s.data.Accounts = out
	recs := s.data.Records[:0]
	for _, r := range s.data.Records {
		if r.AccountID != id {
			recs = append(recs, r)
		}
	}
	s.data.Records = recs
	return s.saveLocked()
}

func (s *Store) Records(accountID string, limit int) []Record {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if limit <= 0 || limit > maxRecords {
		limit = 120
	}
	out := make([]Record, 0, limit)
	for i := len(s.data.Records) - 1; i >= 0 && len(out) < limit; i-- {
		r := s.data.Records[i]
		if accountID != "" && r.AccountID != accountID {
			continue
		}
		out = append(out, r)
	}
	return out
}

func (s *Store) AddRecord(r Record) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if r.ID == "" {
		r.ID = NewID("rec")
	}
	if r.At.IsZero() {
		r.At = time.Now()
	}
	s.data.Records = append(s.data.Records, r)
	if len(s.data.Records) > maxRecords {
		s.data.Records = s.data.Records[len(s.data.Records)-maxRecords:]
	}
	return s.saveLocked()
}

func (s *Store) ClearRecords(accountID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if accountID == "" {
		s.data.Records = nil
		return s.saveLocked()
	}
	out := s.data.Records[:0]
	for _, r := range s.data.Records {
		if r.AccountID != accountID {
			out = append(out, r)
		}
	}
	s.data.Records = out
	return s.saveLocked()
}

func (s *Store) Options() Options {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.data.Options
}

func (s *Store) SaveOptions(o Options) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if o.Interval <= 0 {
		o.Interval = defaultEvery
	}
	s.data.Options = o
	return s.saveLocked()
}

func (s *Store) Password(a Account) (string, error) {
	if a.Secret == "" {
		return "", nil
	}
	return DecryptSecret(a.Secret)
}

func (s *Store) SetPassword(a *Account, plain string) error {
	enc, err := EncryptSecret(plain)
	if err != nil {
		return err
	}
	a.Secret = enc
	return nil
}

func NewID(prefix string) string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("%s_%d", prefix, time.Now().UnixNano())
	}
	return prefix + "_" + hex.EncodeToString(b[:])
}
