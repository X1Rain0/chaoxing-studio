// Package geo 提供地点检索与逆地理编码，走高德 Web 服务（GCJ-02 坐标系，
// 与学习通地图、高德瓦片一致）。
//
// 高德 Web 服务需要 Key，所以这里对「没有 Key」的情况单独返回 ErrNoKey，
// 让上层退回到「在地图上手动点选」这条不需要 Key 的路径。
package geo

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	endpointSearch = "https://restapi.amap.com/v3/place/text"
	endpointRegeo  = "https://restapi.amap.com/v3/geocode/regeo"
	userAgent      = "ChaoxingStudio/1.1"
)

// ErrNoKey 表示没有配置高德 Key。
var ErrNoKey = errors.New("未配置高德 Key，请在地图上直接点选位置")

// Place 是一条地理结果。纬度纬度均为 GCJ-02。
type Place struct {
	Name      string  `json:"name"`
	Address   string  `json:"address"`
	Region    string  `json:"region"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

var client = &http.Client{Timeout: 12 * time.Second}

type searchResp struct {
	Status string `json:"status"`
	Info   string `json:"info"`
	Pois   []struct {
		Name     string `json:"name"`
		Address  any    `json:"address"`
		Location string `json:"location"`
		Pname    string `json:"pname"`
		Cityname string `json:"cityname"`
		Adname   string `json:"adname"`
	} `json:"pois"`
}

type regeoResp struct {
	Status    string `json:"status"`
	Info      string `json:"info"`
	Regeocode struct {
		FormattedAddress string `json:"formatted_address"`
		AddressComponent struct {
			Province string `json:"province"`
			City     any    `json:"city"`
			District string `json:"district"`
			Township string `json:"township"`
			Street   any    `json:"streetNumber"`
		} `json:"addressComponent"`
	} `json:"regeocode"`
}

// Search 按关键词检索地点。city 可为空（全国检索），也可以是城市名或 adcode。
func Search(ctx context.Context, key, keyword, city string) ([]Place, error) {
	key = strings.TrimSpace(key)
	if key == "" {
		return nil, ErrNoKey
	}
	keyword = strings.TrimSpace(keyword)
	if keyword == "" {
		return nil, errors.New("请输入地点名称")
	}

	q := url.Values{}
	q.Set("key", key)
	q.Set("keywords", keyword)
	q.Set("offset", "12")
	q.Set("page", "1")
	q.Set("extensions", "base")
	if c := strings.TrimSpace(city); c != "" {
		q.Set("city", c)
		q.Set("citylimit", "true")
	}

	var out searchResp
	if err := getJSON(ctx, endpointSearch+"?"+q.Encode(), &out); err != nil {
		return nil, err
	}
	if out.Status != "1" {
		return nil, amapErr(out.Status, out.Info)
	}

	places := make([]Place, 0, len(out.Pois))
	for _, p := range out.Pois {
		lat, lng, ok := splitLocation(p.Location)
		if !ok {
			continue
		}
		addr := pickString(p.Address)
		region := strings.TrimSpace(strings.Join(nonEmpty(p.Pname, p.Cityname, p.Adname), " "))
		if addr == "" {
			addr = region
		}
		places = append(places, Place{
			Name:      strings.TrimSpace(p.Name),
			Address:   strings.TrimSpace(addr),
			Region:    region,
			Latitude:  lat,
			Longitude: lng,
		})
	}
	return places, nil
}

// Reverse 由坐标反查地址文字。lat/lng 为 GCJ-02。
func Reverse(ctx context.Context, key string, lat, lng float64) (Place, error) {
	key = strings.TrimSpace(key)
	if key == "" {
		return Place{}, ErrNoKey
	}
	if lat == 0 && lng == 0 {
		return Place{}, errors.New("坐标为空")
	}

	q := url.Values{}
	q.Set("key", key)
	q.Set("location", fmt.Sprintf("%.6f,%.6f", lng, lat))
	q.Set("extensions", "base")
	q.Set("radius", "200")

	var out regeoResp
	if err := getJSON(ctx, endpointRegeo+"?"+q.Encode(), &out); err != nil {
		return Place{}, err
	}
	if out.Status != "1" {
		return Place{}, amapErr(out.Status, out.Info)
	}

	ac := out.Regeocode.AddressComponent
	region := strings.TrimSpace(strings.Join(nonEmpty(pickString(ac.Province), pickString(ac.City), pickString(ac.District)), " "))
	addr := strings.TrimSpace(out.Regeocode.FormattedAddress)
	if addr == "" {
		addr = region
	}
	return Place{Name: region, Address: addr, Region: region, Latitude: lat, Longitude: lng}, nil
}

func getJSON(ctx context.Context, url string, out any) error {
	if ctx == nil {
		ctx = context.Background()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("请求高德服务失败：%w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return fmt.Errorf("读取高德响应失败：%w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("高德服务返回 HTTP %d", resp.StatusCode)
	}
	if err := json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("解析高德响应失败：%w", err)
	}
	return nil
}

func amapErr(status, info string) error {
	info = strings.TrimSpace(info)
	switch info {
	case "INVALID_USER_KEY", "USERKEY_PLAT_NOMATCH", "INVALID_USER_SCODE":
		return errors.New("高德 Key 无效，或没有开通「Web 服务」权限")
	case "DAILY_QUERY_OVER_LIMIT", "OVER_QUOTA", "CUQPS_HAS_EXCEEDED_THE_LIMIT":
		return errors.New("高德 Key 今日配额已用完，或请求过快")
	}
	if info == "" {
		info = "未知错误"
	}
	return fmt.Errorf("高德服务返回错误：%s（status=%s）", info, status)
}

func splitLocation(s string) (lat, lng float64, ok bool) {
	parts := strings.Split(strings.TrimSpace(s), ",")
	if len(parts) != 2 {
		return 0, 0, false
	}
	lon, err1 := strconv.ParseFloat(strings.TrimSpace(parts[0]), 64)
	la, err2 := strconv.ParseFloat(strings.TrimSpace(parts[1]), 64)
	if err1 != nil || err2 != nil {
		return 0, 0, false
	}
	return la, lon, true
}

func pickString(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case nil:
		return ""
	default:
		return fmt.Sprint(t)
	}
}

func nonEmpty(vals ...string) []string {
	out := make([]string, 0, len(vals))
	for _, v := range vals {
		if s := strings.TrimSpace(v); s != "" {
			out = append(out, s)
		}
	}
	return out
}
