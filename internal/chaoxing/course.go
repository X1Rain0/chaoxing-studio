package chaoxing

import (
	"encoding/json"
	"fmt"
)

type backclazzResp struct {
	ChannelList []struct {
		Content struct {
			ID     flexString `json:"id"`
			ChatID flexString `json:"chatid"`
			Name   string     `json:"name"`
			Course struct {
				Data []struct {
					ID   flexString `json:"id"`
					Name string     `json:"name"`
				} `json:"data"`
			} `json:"course"`
		} `json:"content"`
	} `json:"channelList"`
}

// Courses 拉取全部课程/班级。使用 backclazzdata：courselistdata 已改版失效。
func (c *Client) Courses() ([]Course, error) {
	data, err := c.getRef(baseMooc+"/mycourse/backclazzdata?rss=1", baseMooc+"/")
	if err != nil {
		return nil, err
	}
	var resp backclazzResp
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("课程接口返回异常: %s", snippet(data))
	}

	out := make([]Course, 0, len(resp.ChannelList))
	seen := make(map[string]bool, len(resp.ChannelList))
	for _, ch := range resp.ChannelList {
		classID := string(ch.Content.ID)
		for _, cr := range ch.Content.Course.Data {
			courseID := string(cr.ID)
			if courseID == "" || classID == "" {
				continue
			}
			key := courseID + "|" + classID
			if seen[key] {
				continue
			}
			seen[key] = true
			out = append(out, Course{
				CourseID:   courseID,
				ClassID:    classID,
				ChatID:     string(ch.Content.ChatID),
				CourseName: cr.Name,
				ClassName:  ch.Content.Name,
			})
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("课程列表为空，可能登录态异常: %s", snippet(data))
	}
	return out, nil
}
