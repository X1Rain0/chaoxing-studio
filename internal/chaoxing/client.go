package chaoxing

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	baseMobile   = "https://mobilelearn.chaoxing.com"
	basePassport = "https://passport2.chaoxing.com"
	baseMooc     = "https://mooc1-api.chaoxing.com"
	basePan      = "https://pan-yz.chaoxing.com"

	userAgent = "Mozilla/5.0 (Linux; Android 13; 22081212C Build/TKQ1.220829.002; wv) " +
		"AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/107.0.0.0 Mobile Safari/537.36"
)

var cookieHosts = []string{
	"https://chaoxing.com",
	"https://www.chaoxing.com",
	"https://i.chaoxing.com",
	"https://passport2.chaoxing.com",
	"https://mobilelearn.chaoxing.com",
	"https://mooc1-api.chaoxing.com",
	"https://pan-yz.chaoxing.com",
}

type Credential struct {
	Phone    string
	Password string
}

// LogFunc 接收 level（info/warn/error/sign）与已格式化消息。
type LogFunc func(level, msg string)

type Client struct {
	hc   *http.Client
	cred Credential
	log  LogFunc
	lim  *limiter

	mu   sync.RWMutex
	uid  string
	fid  string
	name string

	loginMu   sync.Mutex
	lastLogin time.Time

	onSession func()
}

// NewClient 创建一个带 cookie jar、全局超时与请求节流的客户端。
func NewClient(cred Credential, log LogFunc) *Client {
	jar, _ := cookiejar.New(nil)
	return &Client{
		hc: &http.Client{
			Timeout: 20 * time.Second,
			Jar:     jar,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) >= 6 {
					return http.ErrUseLastResponse
				}
				return nil
			},
		},
		cred: cred,
		log:  log,
		lim:  newLimiter(350 * time.Millisecond),
	}
}

func (c *Client) SetLogger(log LogFunc) { c.log = log }

func (c *Client) Close() {
	c.hc.CloseIdleConnections()
}

func (c *Client) logf(level, format string, args ...any) {
	if c.log == nil {
		return
	}
	c.log(level, fmt.Sprintf(format, args...))
}

func (c *Client) UID() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.uid
}

func (c *Client) FID() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.fid
}

func (c *Client) Name() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.name
}

func (c *Client) Phone() string { return c.cred.Phone }

func (c *Client) LastLogin() time.Time {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.lastLogin
}

func (c *Client) setIdentity(uid, fid, name string) {
	c.mu.Lock()
	c.uid, c.fid, c.name = uid, fid, name
	c.lastLogin = time.Now()
	c.mu.Unlock()
}

// Cookie 在所有可能的域上查找指定 cookie。
func (c *Client) Cookie(name string) string {
	for _, h := range cookieHosts {
		u, err := url.Parse(h)
		if err != nil {
			continue
		}
		for _, ck := range c.hc.Jar.Cookies(u) {
			if ck.Name == name && ck.Value != "" {
				return ck.Value
			}
		}
	}
	return ""
}

type requestSpec struct {
	method    string
	url       string
	form      url.Values
	rawBody   string
	referer   string
	extra     map[string]string
	noRelogin bool
}

func (c *Client) send(spec requestSpec) ([]byte, error) {
	build := func() (*http.Request, error) {
		var body io.Reader
		switch {
		case spec.rawBody != "":
			body = strings.NewReader(spec.rawBody)
		case spec.form != nil:
			body = strings.NewReader(spec.form.Encode())
		}
		req, err := http.NewRequest(spec.method, spec.url, body)
		if err != nil {
			return nil, err
		}
		req.Header.Set("User-Agent", userAgent)
		req.Header.Set("Accept", "*/*")
		req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9")
		if spec.form != nil || spec.rawBody != "" {
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		}
		if spec.referer != "" {
			req.Header.Set("Referer", spec.referer)
		}
		for k, v := range spec.extra {
			req.Header.Set(k, v)
		}
		return req, nil
	}

	reloginTried := false
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		req, err := build()
		if err != nil {
			return nil, err
		}
		c.lim.wait()
		resp, err := c.hc.Do(req)
		if err != nil {
			lastErr = err
			time.Sleep(time.Duration(400*(attempt+1)) * time.Millisecond)
			continue
		}
		data, readErr := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
		resp.Body.Close()
		if readErr != nil {
			return nil, readErr
		}
		if resp.StatusCode >= 500 {
			lastErr = fmt.Errorf("服务端返回 HTTP %d", resp.StatusCode)
			time.Sleep(time.Duration(500*(attempt+1)) * time.Millisecond)
			continue
		}
		if !spec.noRelogin && !reloginTried && c.isLoggedOut(data) {
			reloginTried = true
			c.logf("warn", "登录态已失效，正在自动重新登录…")
			if err := c.Login(); err != nil {
				return nil, fmt.Errorf("自动重登失败: %w", err)
			}
			continue
		}
		return data, nil
	}
	if lastErr == nil {
		lastErr = errors.New("请求失败")
	}
	return nil, lastErr
}

func (c *Client) isLoggedOut(body []byte) bool {
	if c.Cookie("_uid") == "" {
		return true
	}
	s := string(body)
	if strings.Contains(s, "passport2.chaoxing.com/login") || strings.Contains(s, "请先登录") {
		return true
	}
	return false
}

func (c *Client) get(u string) ([]byte, error) {
	return c.send(requestSpec{method: http.MethodGet, url: u})
}

func (c *Client) getRef(u, referer string) ([]byte, error) {
	return c.send(requestSpec{method: http.MethodGet, url: u, referer: referer})
}

func (c *Client) postForm(u string, form url.Values, extra map[string]string) ([]byte, error) {
	return c.send(requestSpec{method: http.MethodPost, url: u, form: form, extra: extra})
}

func truthy(v any) bool {
	switch t := v.(type) {
	case bool:
		return t
	case float64:
		return t != 0
	case string:
		s := strings.TrimSpace(strings.ToLower(t))
		return s == "true" || s == "1"
	}
	return false
}

func firstString(m map[string]any, keys ...string) string {
	for _, k := range keys {
		if v, ok := m[k]; ok {
			s := strings.TrimSpace(fmt.Sprint(v))
			if s != "" && s != "<nil>" {
				return s
			}
		}
	}
	return ""
}

func decodeJSONMap(data []byte) (map[string]any, error) {
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, err
	}
	return m, nil
}
