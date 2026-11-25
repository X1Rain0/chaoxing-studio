package chaoxing

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Kind 是签到类型。接口用 otherId 区分，0 再细分普通/拍照。
type Kind int

const (
	KindGeneral Kind = iota
	KindPhoto
	KindQR
	KindGesture
	KindLocation
	KindCode
)

func (k Kind) String() string {
	switch k {
	case KindGeneral:
		return "general"
	case KindPhoto:
		return "photo"
	case KindQR:
		return "qrcode"
	case KindGesture:
		return "gesture"
	case KindLocation:
		return "location"
	case KindCode:
		return "code"
	}
	return "unknown"
}

// Label 返回中文名，直接给 UI 用。
func (k Kind) Label() string {
	switch k {
	case KindGeneral:
		return "普通签到"
	case KindPhoto:
		return "拍照签到"
	case KindQR:
		return "二维码签到"
	case KindGesture:
		return "手势签到"
	case KindLocation:
		return "位置签到"
	case KindCode:
		return "签到码"
	}
	return "未知签到"
}

// NeedsCode 表示必须在提交前通过 checkSignCode 校验。
func (k Kind) NeedsCode() bool { return k == KindGesture || k == KindCode }

// KindFromOtherID 把接口字段映射成内部类型。
func KindFromOtherID(otherID int, isPhoto bool) Kind {
	switch otherID {
	case 0:
		if isPhoto {
			return KindPhoto
		}
		return KindGeneral
	case 2:
		return KindQR
	case 3:
		return KindGesture
	case 4:
		return KindLocation
	case 5:
		return KindCode
	}
	return KindGeneral
}

type Course struct {
	CourseID   string `json:"courseId"`
	ClassID    string `json:"classId"`
	ChatID     string `json:"chatId"`
	CourseName string `json:"courseName"`
	ClassName  string `json:"className"`
}

func (c Course) Title() string {
	if c.CourseName == "" {
		return c.ClassName
	}
	if c.ClassName == "" || c.ClassName == c.CourseName {
		return c.CourseName
	}
	return c.CourseName + " · " + c.ClassName
}

type Activity struct {
	ActiveID string    `json:"activeId"`
	Name     string    `json:"name"`
	OtherID  int       `json:"otherId"`
	Kind     Kind      `json:"kind"`
	KindName string    `json:"kindName"`
	Course   Course    `json:"course"`
	StartAt  time.Time `json:"startAt"`
	IsPhoto  bool      `json:"isPhoto"`
}

type Location struct {
	Latitude  string `json:"latitude"`
	Longitude string `json:"longitude"`
	Address   string `json:"address"`
}

func (l Location) Valid() bool {
	return strings.TrimSpace(l.Latitude) != "" && strings.TrimSpace(l.Longitude) != ""
}

func (l Location) Describe() string {
	if l.Address != "" {
		return l.Address
	}
	return l.Latitude + "," + l.Longitude
}

type SignResult struct {
	OK       bool      `json:"ok"`
	Message  string    `json:"message"`
	Raw      string    `json:"raw"`
	Kind     Kind      `json:"kind"`
	KindName string    `json:"kindName"`
	ActiveID string    `json:"activeId"`
	At       time.Time `json:"at"`
	Duration int64     `json:"durationMs"`
}

func (r SignResult) String() string {
	state := "失败"
	if r.OK {
		state = "成功"
	}
	return fmt.Sprintf("%s：%s（%s）", r.KindName, state, r.Message)
}

// flexString 兼容接口把 id 写成数字、字符串或 null 的情况。
type flexString string

func (f *flexString) UnmarshalJSON(b []byte) error {
	s := strings.TrimSpace(string(b))
	if s == "" || s == "null" {
		*f = ""
		return nil
	}
	s = strings.Trim(s, `"`)
	*f = flexString(s)
	return nil
}

type flexInt int

func (f *flexInt) UnmarshalJSON(b []byte) error {
	s := strings.TrimSpace(string(b))
	if s == "" || s == "null" {
		*f = 0
		return nil
	}
	s = strings.Trim(s, `"`)
	if n, err := strconv.Atoi(s); err == nil {
		*f = flexInt(n)
		return nil
	}
	if v, err := strconv.ParseFloat(s, 64); err == nil {
		*f = flexInt(int(v))
	}
	return nil
}

func atoiSafe(s string) int {
	n, err := strconv.Atoi(strings.TrimSpace(s))
	if err != nil {
		return -1
	}
	return n
}

func int64Safe(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case int64:
		return t
	case int:
		return int64(t)
	case string:
		s := strings.TrimSpace(t)
		if s == "" {
			return 0
		}
		if n, err := strconv.ParseInt(s, 10, 64); err == nil {
			return n
		}
		if f, err := strconv.ParseFloat(s, 64); err == nil {
			return int64(f)
		}
	}
	return 0
}

func snippet(b []byte) string {
	s := strings.TrimSpace(string(b))
	s = strings.ReplaceAll(s, "\r", " ")
	s = strings.ReplaceAll(s, "\n", " ")
	if len(s) > 200 {
		s = s[:200] + "…"
	}
	return s
}
