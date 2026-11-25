package chaoxing

import (
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"sync"
	"time"
)

var nameRe = regexp.MustCompile(`class="fr colorBlue"[^>]*>\s*([^<\s]+)\s*</p>`)

// limiter 是极简节流器：保证两次请求之间至少间隔 minGap。
type limiter struct {
	mu     sync.Mutex
	last   time.Time
	minGap time.Duration
}

func newLimiter(gap time.Duration) *limiter { return &limiter{minGap: gap} }

func (l *limiter) wait() {
	l.mu.Lock()
	gap := l.minGap - time.Since(l.last)
	l.mu.Unlock()
	if gap > 0 {
		time.Sleep(gap)
	}
	l.mu.Lock()
	l.last = time.Now()
	l.mu.Unlock()
}

// Login 用手机号 + 密码登录，成功后写入 uid/fid/name。
func (c *Client) Login() error {
	c.loginMu.Lock()
	defer c.loginMu.Unlock()
	return c.loginLocked()
}

func (c *Client) loginLocked() error {
	if c.cred.Phone == "" || c.cred.Password == "" {
		return fmt.Errorf("缺少账号或密码")
	}
	enc, err := desEncrypt(c.cred.Password)
	if err != nil {
		return fmt.Errorf("密码加密失败: %w", err)
	}

	form := url.Values{}
	form.Set("uname", c.cred.Phone)
	form.Set("password", enc)
	form.Set("fid", "-1")
	form.Set("t", "true")
	form.Set("refer", "https%3A%2F%2Fi.chaoxing.com")
	form.Set("forbidotherlogin", "0")
	form.Set("validate", "")

	data, err := c.send(requestSpec{
		method:    http.MethodPost,
		url:       basePassport + "/fanyalogin",
		form:      form,
		noRelogin: true,
		extra: map[string]string{
			"X-Requested-With": "XMLHttpRequest",
			"Referer":          "https://passport2.chaoxing.com/login?refer=https%3A%2F%2Fi.chaoxing.com",
		},
	})
	if err != nil {
		return fmt.Errorf("登录请求失败: %w", err)
	}

	res, err := decodeJSONMap(data)
	if err != nil {
		return fmt.Errorf("登录响应异常: %s", snippet(data))
	}
	if !truthy(res["status"]) {
		msg := firstString(res, "msg2", "message", "msg", "errorMsg")
		if msg == "" {
			msg = snippet(data)
		}
		return fmt.Errorf("登录失败: %s", msg)
	}

	uid := c.Cookie("_uid")
	fid := c.Cookie("fid")
	name := firstString(res, "name", "uname", "userName")
	if name == "" {
		name = c.fetchName()
	}
	if uid == "" {
		return fmt.Errorf("登录成功但未拿到 _uid，接口可能已变更")
	}
	c.setIdentity(uid, fid, name)
	c.logf("info", "登录成功：%s（学校 ID %s）", displayName(name), dash(fid))
	return nil
}

// fetchName 从账号管理页兜底提取真实姓名。
func (c *Client) fetchName() string {
	data, err := c.getRef(basePassport+"/mooc/accountManage", basePassport+"/")
	if err != nil {
		return ""
	}
	if m := nameRe.FindSubmatch(data); m != nil {
		return string(m[1])
	}
	return ""
}

func displayName(s string) string {
	if s == "" {
		return "未知用户"
	}
	return s
}

func dash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}
