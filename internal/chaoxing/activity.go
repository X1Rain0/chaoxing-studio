package chaoxing

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
	"time"
)

type activeListResp struct {
	Data struct {
		ActiveList []map[string]any `json:"activeList"`
	} `json:"data"`
	ActiveList []map[string]any `json:"activeList"`
}

// Activities 返回某门课当前可签到的活动，兼容 status 的数字/字符串形态。
func (c *Client) Activities(courseID, classID string) ([]Activity, error) {
	u := fmt.Sprintf("%s/v2/apis/active/student/activelist?fid=0&courseId=%s&classId=%s&_=%d",
		baseMobile, url.QueryEscape(courseID), url.QueryEscape(classID), time.Now().UnixMilli())
	data, err := c.getRef(u, baseMobile+"/")
	if err != nil {
		return nil, err
	}

	var resp activeListResp
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("活动接口返回异常: %s", snippet(data))
	}
	list := resp.Data.ActiveList
	if len(list) == 0 {
		list = resp.ActiveList
	}

	out := make([]Activity, 0, len(list))
	for _, a := range list {
		if !statusOpen(a["status"]) {
			continue
		}
		other := int64Safe(a["otherId"])
		if other < 0 || other > 5 {
			continue
		}
		start := int64Safe(a["startTime"])
		if start > 1_000_000_000_000 {
			start /= 1000
		}
		startAt := time.Unix(start, 0)
		if start > 0 && time.Since(startAt) > 2*time.Hour {
			continue
		}
		id := strings.TrimSpace(fmt.Sprint(a["id"]))
		if id == "" || id == "<nil>" {
			continue
		}
		kind := KindFromOtherID(int(other), false)
		out = append(out, Activity{
			ActiveID: id,
			Name:     firstString(a, "nameOne", "name"),
			OtherID:  int(other),
			Kind:     kind,
			KindName: kind.Label(),
			StartAt:  startAt,
		})
	}
	return out, nil
}

func statusOpen(v any) bool {
	switch t := v.(type) {
	case bool:
		return t
	case float64:
		return t == 1
	case string:
		s := strings.TrimSpace(strings.ToLower(t))
		return s == "1" || s == "true"
	}
	return false
}

// IsPhotoActivity 判断 otherId==0 的活动是否是拍照签到。
func (c *Client) IsPhotoActivity(activeID string) bool {
	u := fmt.Sprintf("%s/v2/apis/active/getPPTActiveInfo?activeId=%s", baseMobile, url.QueryEscape(activeID))
	data, err := c.getRef(u, baseMobile+"/")
	if err != nil {
		return false
	}
	var resp struct {
		Data struct {
			IfPhoto flexInt `json:"ifphoto"`
		} `json:"data"`
	}
	if err := json.Unmarshal(data, &resp); err != nil {
		return false
	}
	return int(resp.Data.IfPhoto) == 1
}
