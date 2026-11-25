package chaoxing

import (
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"
)

var (
	panEncRe  = regexp.MustCompile(`enc\s*=\s*"(.*?)"`)
	panRootRe = regexp.MustCompile(`_rootdir\s*=\s*"(.*?)"`)
)

// PanObjectID 从超星云盘根目录取一张图片作为拍照签到的附件。
// 优先 0.jpg / 0.png，找不到时退化为任意图片（覆盖更多真实情况）。
func (c *Client) PanObjectID() (string, error) {
	page, err := c.getRef(basePan+"/", basePan+"/")
	if err != nil {
		return "", err
	}
	encM := panEncRe.FindSubmatch(page)
	rootM := panRootRe.FindSubmatch(page)
	if encM == nil || rootM == nil {
		return "", fmt.Errorf("云盘页面结构变化，未找到 enc/_rootdir")
	}
	params := fmt.Sprintf("puid=0&shareid=0&parentId=%s&page=1&size=50&enc=%s",
		string(rootM[1]), string(encM[1]))

	data, err := c.send(requestSpec{
		method:  http.MethodPost,
		url:     basePan + "/opt/listres?" + params,
		rawBody: params,
		referer: basePan + "/",
	})
	if err != nil {
		return "", err
	}

	var res struct {
		List []struct {
			Name     string     `json:"name"`
			ObjectID flexString `json:"objectId"`
		} `json:"list"`
	}
	if err := json.Unmarshal(data, &res); err != nil {
		return "", fmt.Errorf("云盘列表解析失败: %s", snippet(data))
	}
	var fallback string
	for _, f := range res.List {
		oid := string(f.ObjectID)
		if oid == "" {
			continue
		}
		name := strings.ToLower(f.Name)
		if name == "0.jpg" || name == "0.png" {
			return oid, nil
		}
		if fallback == "" && (strings.HasSuffix(name, ".jpg") || strings.HasSuffix(name, ".png") ||
			strings.HasSuffix(name, ".jpeg")) {
			fallback = oid
		}
	}
	if fallback != "" {
		c.logf("info", "云盘未找到 0.jpg/0.png，改用首张图片")
		return fallback, nil
	}
	return "", nil
}
