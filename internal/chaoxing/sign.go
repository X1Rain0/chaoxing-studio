package chaoxing

import (
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"strings"
	"time"
)

var (
	analysisCodeRe = regexp.MustCompile(`code\s*=\s*'\+'\s*'([^']+)'`)
	analysisAltRe  = regexp.MustCompile(`code=([A-Za-z0-9]{16,})`)
)

type SignInput struct {
	SignCode string   `json:"signCode"`
	Location Location `json:"location"`
	Enc      string   `json:"enc"`
}

// PreSign 复现 App 打开签到页的三步：preSign → analysis → analysis2。
func (c *Client) PreSign(act Activity) error {
	q := url.Values{}
	q.Set("courseId", act.Course.CourseID)
	q.Set("classId", act.Course.ClassID)
	q.Set("activePrimaryId", act.ActiveID)
	q.Set("general", "1")
	q.Set("sys", "1")
	q.Set("ls", "1")
	q.Set("appType", "15")
	q.Set("tid", "")
	q.Set("uid", c.UID())
	q.Set("ut", "s")
	if _, err := c.getRef(baseMobile+"/newsign/preSign?"+q.Encode(), baseMobile+"/"); err != nil {
		return err
	}

	body, err := c.getRef(fmt.Sprintf("%s/pptSign/analysis?vs=1&DB_STRATEGY=RANDOM&aid=%s",
		baseMobile, url.QueryEscape(act.ActiveID)), baseMobile+"/")
	if err != nil {
		return err
	}
	if code := extractAnalysisCode(string(body)); code != "" {
		c.getRef(fmt.Sprintf("%s/pptSign/analysis2?DB_STRATEGY=RANDOM&code=%s",
			baseMobile, url.QueryEscape(code)), baseMobile+"/")
	}
	time.Sleep(400 * time.Millisecond)
	return nil
}

func extractAnalysisCode(html string) string {
	if m := analysisCodeRe.FindStringSubmatch(html); m != nil {
		return m[1]
	}
	if m := analysisAltRe.FindStringSubmatch(html); m != nil {
		return m[1]
	}
	return ""
}

func (c *Client) baseSignParams(act Activity) url.Values {
	q := url.Values{}
	q.Set("activeId", act.ActiveID)
	q.Set("uid", c.UID())
	q.Set("clientip", "")
	q.Set("appType", "15")
	q.Set("fid", c.FID())
	q.Set("name", c.Name())
	return q
}

func (c *Client) signAjax(q url.Values) ([]byte, error) {
	return c.getRef(baseMobile+"/pptSign/stuSignajax?"+q.Encode(), baseMobile+"/")
}

// checkSignCode 是手势/签到码的强制前置校验，跳过会返回 90002。
func (c *Client) checkSignCode(activeID, code string) error {
	q := url.Values{}
	q.Set("activeId", activeID)
	q.Set("signCode", code)
	body, err := c.getRef(baseMobile+"/widget/sign/pcStuSignController/checkSignCode?"+q.Encode(), baseMobile+"/")
	if err != nil {
		return err
	}
	res, err := decodeJSONMap(body)
	if err != nil {
		return fmt.Errorf("校验接口返回异常: %s", snippet(body))
	}
	if !truthy(res["result"]) && int64Safe(res["result"]) != 1 {
		msg := firstString(res, "errorMsg", "msg", "message")
		if msg == "" {
			msg = snippet(body)
		}
		return fmt.Errorf("码校验失败: %s", msg)
	}
	return nil
}

// Sign 按类型提交签到，返回结果与失败原因。
func (c *Client) Sign(act Activity, in SignInput) (SignResult, error) {
	started := time.Now()
	kind := act.Kind
	if kind == KindGeneral && c.IsPhotoActivity(act.ActiveID) {
		kind = KindPhoto
	}
	act.Kind = kind
	act.KindName = kind.Label()

	if err := c.PreSign(act); err != nil {
		c.logf("warn", "预签到未完成（继续提交）: %v", err)
	}

	var body []byte
	var err error
	fail := func(msg string) (SignResult, error) {
		return SignResult{
			Kind: kind, KindName: kind.Label(), ActiveID: act.ActiveID,
			Message: msg, At: started, Duration: time.Since(started).Milliseconds(),
		}, fmt.Errorf("%s", msg)
	}

	switch kind {
	case KindPhoto:
		oid, oerr := c.PanObjectID()
		if oerr != nil || oid == "" {
			if oerr != nil {
				return fail("云盘取图失败: " + oerr.Error())
			}
			return fail("云盘根目录没有 0.jpg / 0.png")
		}
		q := c.baseSignParams(act)
		q.Set("useragent", "")
		q.Set("latitude", "-1")
		q.Set("longitude", "-1")
		q.Set("objectId", oid)
		body, err = c.signAjax(q)

	case KindLocation:
		if !in.Location.Valid() {
			return fail("位置签到需要先设置经纬度")
		}
		q := c.baseSignParams(act)
		q.Set("address", in.Location.Address)
		q.Set("latitude", in.Location.Latitude)
		q.Set("longitude", in.Location.Longitude)
		q.Set("ifTiJiao", "1")
		body, err = c.signAjax(q)

	case KindQR:
		if strings.TrimSpace(in.Enc) == "" {
			return fail("二维码签到需要 enc 参数")
		}
		loc := in.Location
		if !loc.Valid() {
			loc = Location{Latitude: "0", Longitude: "0", Address: ""}
		}
		payload := map[string]any{
			"result":    "1",
			"address":   loc.Address,
			"latitude":  numOrZero(loc.Latitude),
			"longitude": numOrZero(loc.Longitude),
			"altitude":  100,
		}
		raw, _ := json.Marshal(payload)
		q := c.baseSignParams(act)
		q.Set("enc", strings.TrimSpace(in.Enc))
		q.Set("location", string(raw))
		q.Set("latitude", "-1")
		q.Set("longitude", "-1")
		body, err = c.signAjax(q)

	case KindGesture, KindCode:
		code := strings.TrimSpace(in.SignCode)
		if code == "" {
			return fail("需要签到码或手势轨迹编码")
		}
		if cerr := c.checkSignCode(act.ActiveID, code); cerr != nil {
			return fail(cerr.Error())
		}
		time.Sleep(200 * time.Millisecond)
		q := c.baseSignParams(act)
		q.Set("latitude", "")
		q.Set("longitude", "")
		q.Set("signCode", code)
		body, err = c.signAjax(q)

	default:
		q := c.baseSignParams(act)
		q.Set("latitude", "-1")
		q.Set("longitude", "-1")
		body, err = c.signAjax(q)
	}

	if err != nil {
		return fail("提交失败: " + err.Error())
	}
	return finalize(kind, body, started), nil
}

// SignWithRetry 只用于手动点「立即签到」这类一次性操作。
func (c *Client) SignWithRetry(act Activity, in SignInput, attempts int) (SignResult, error) {
	if attempts < 1 {
		attempts = 1
	}
	var res SignResult
	var err error
	for i := 0; i < attempts; i++ {
		res, err = c.Sign(act, in)
		if err == nil {
			return res, nil
		}
		if i < attempts-1 {
			time.Sleep(time.Duration(800*(i+1)) * time.Millisecond)
		}
	}
	return res, err
}

func finalize(kind Kind, body []byte, started time.Time) SignResult {
	raw := strings.TrimSpace(string(body))
	res := SignResult{
		Kind:     kind,
		KindName: kind.Label(),
		Raw:      raw,
		At:       started,
		Duration: time.Since(started).Milliseconds(),
	}
	low := strings.ToLower(raw)
	switch {
	case low == "success" || strings.Contains(low, `"result":1`) && strings.Contains(raw, "success"):
		res.OK = true
		res.Message = "签到成功"
	case strings.Contains(raw, "已签到"):
		res.OK = true
		res.Message = "已签到过，无需重复"
	case strings.Contains(raw, "已结束") || strings.Contains(raw, "活动结束"):
		res.Message = "活动已结束"
	case raw == "":
		res.Message = "服务端返回空响应"
	default:
		res.Message = raw
	}
	return res
}

func numOrZero(s string) float64 {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	var f float64
	fmt.Sscanf(s, "%f", &f)
	return f
}
