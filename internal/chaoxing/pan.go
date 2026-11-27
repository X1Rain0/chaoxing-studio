package chaoxing

import (
	"bytes"
	"encoding/json"
	"fmt"
	"html"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"regexp"
	"strings"
	"unicode"
)

const (
	panUploadMaxBytes         = 6 << 20
	panUploadMaxResponseBytes = 1 << 20
	panUploadTokenMaxBytes    = 64 << 10
	panUploadMaxPixels        = 40_000_000
)

var (
	panEncRe  = regexp.MustCompile(`(?i)\benc["']?\s*[:=]\s*["']([^"'<>]+)["']`)
	panRootRe = regexp.MustCompile(`(?i)\b_rootdir["']?\s*[:=]\s*["']([^"'<>]+)["']`)
)

type panFileEntry struct {
	Name     string     `json:"name"`
	ObjectID flexString `json:"objectId"`
	ID       flexString `json:"id"`
	FileSize flexInt    `json:"filesize"`
	Size     flexInt    `json:"size"`
}

// PanObjectID selects the automatic-sign default image: prefer 0.jpg/0.png,
// then use the first supported image in the account's cloud-drive root.
func (c *Client) PanObjectID() (string, error) {
	files, err := c.panRootFiles()
	if err != nil {
		return "", err
	}
	objectID, usedFallback := selectPanObjectID(files)
	if usedFallback {
		c.logf("info", "云盘未找到 0.jpg/0.png，改用首张图片")
	}
	return objectID, nil
}

// UploadPanPhoto uploads one user-selected JPEG/PNG. This POST is intentionally
// single-shot: retrying a non-idempotent upload could create duplicate files.
// The returned object ID is meant for one current sign only and is not stored.
func (c *Client) UploadPanPhoto(contentType string, data []byte) (string, error) {
	mimeType, filename, err := inspectPanUpload(data, contentType)
	if err != nil {
		return "", err
	}

	tokenBody, status, err := c.panRequest(http.MethodGet, basePan+"/api/token/uservalid", nil, "", panUploadTokenMaxBytes)
	if err != nil {
		return "", fmt.Errorf("获取照片上传凭据失败: %w", err)
	}
	if status < 200 || status >= 300 {
		return "", fmt.Errorf("获取照片上传凭据返回HTTP %d", status)
	}
	token, err := parsePanUploadToken(tokenBody)
	if err != nil {
		return "", err
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	partHeader := make(textproto.MIMEHeader)
	partHeader.Set("Content-Disposition", fmt.Sprintf(`form-data; name="file"; filename="%s"`, filename))
	partHeader.Set("Content-Type", mimeType)
	part, err := writer.CreatePart(partHeader)
	if err != nil {
		return "", fmt.Errorf("构造照片上传内容失败: %w", err)
	}
	if _, err := part.Write(data); err != nil {
		return "", fmt.Errorf("构造照片上传内容失败: %w", err)
	}
	if err := writer.WriteField("puid", c.UID()); err != nil {
		return "", fmt.Errorf("构造照片上传内容失败: %w", err)
	}
	if err := writer.Close(); err != nil {
		return "", fmt.Errorf("构造照片上传内容失败: %w", err)
	}

	query := url.Values{}
	query.Set("_token", token)
	uploadURL := basePan + "/upload?" + query.Encode()
	responseBody, status, err := c.panRequest(http.MethodPost, uploadURL, &body, writer.FormDataContentType(), panUploadMaxResponseBytes)
	if err != nil {
		return "", fmt.Errorf("上传照片失败: %w", err)
	}
	if status < 200 || status >= 300 {
		return "", fmt.Errorf("照片上传返回HTTP %d", status)
	}
	objectID, err := parsePanUploadObjectID(responseBody)
	if err != nil {
		return "", err
	}
	return objectID, nil
}

func (c *Client) panRequest(method, target string, body io.Reader, contentType string, maxResponseBytes int64) ([]byte, int, error) {
	parsed, err := url.Parse(target)
	if err != nil || parsed.Scheme != "https" || parsed.Hostname() != "pan-yz.chaoxing.com" {
		return nil, 0, fmt.Errorf("照片接口地址无效")
	}
	cookieHeader := c.panCookieHeader()
	if cookieHeader == "" {
		return nil, 0, fmt.Errorf("请先登录学习通账号")
	}
	request, err := http.NewRequest(method, parsed.String(), body)
	if err != nil {
		return nil, 0, err
	}
	request.Header.Set("User-Agent", userAgent)
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("Referer", basePan+"/")
	request.Header.Set("Cookie", cookieHeader)
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}

	// Use the session transport and timeout, but never forward credentials to a
	// redirect host and never retry the upload POST automatically.
	hc := *c.hc
	hc.Jar = nil
	hc.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	c.lim.wait()
	response, err := hc.Do(request)
	if err != nil {
		return nil, 0, err
	}
	defer response.Body.Close()
	if response.ContentLength > maxResponseBytes {
		return nil, response.StatusCode, fmt.Errorf("照片接口响应超过大小上限")
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		return nil, response.StatusCode, err
	}
	if int64(len(data)) > maxResponseBytes {
		return nil, response.StatusCode, fmt.Errorf("照片接口响应超过大小上限")
	}
	return data, response.StatusCode, nil
}

func (c *Client) panCookieHeader() string {
	pairs := make([]string, 0, 4)
	for _, name := range []string{"uf", "_d", "UID", "vc3"} {
		value := c.Cookie(name)
		if name == "UID" && value == "" {
			value = c.UID()
		}
		if value != "" && !strings.ContainsAny(value, ";\r\n") {
			pairs = append(pairs, name+"="+value)
		}
	}
	return strings.Join(pairs, "; ")
}

func (c *Client) panRootFiles() ([]panFileEntry, error) {
	page, err := c.getRef(basePan+"/", basePan+"/")
	if err != nil {
		return nil, err
	}
	enc, rootID := panRootTokens(page)
	if enc == "" || rootID == "" {
		if c.isLoggedOut(page) {
			return nil, fmt.Errorf("云盘登录状态已失效，请重新登录后重试")
		}
		return nil, fmt.Errorf("云盘页面暂时无法读取自动照片信息")
	}
	params := fmt.Sprintf("puid=0&shareid=0&parentId=%s&page=1&size=50&enc=%s", rootID, enc)
	data, err := c.send(requestSpec{
		method:  http.MethodPost,
		url:     basePan + "/opt/listres?" + params,
		rawBody: params,
		referer: basePan + "/",
	})
	if err != nil {
		return nil, err
	}
	var res struct {
		List []panFileEntry `json:"list"`
	}
	if err := json.Unmarshal(data, &res); err != nil {
		return nil, fmt.Errorf("云盘列表解析失败")
	}
	return res.List, nil
}

func panRootTokens(page []byte) (enc string, rootID string) {
	source := html.UnescapeString(string(page))
	if match := panEncRe.FindStringSubmatch(source); len(match) > 1 {
		enc = strings.TrimSpace(match[1])
	}
	if match := panRootRe.FindStringSubmatch(source); len(match) > 1 {
		rootID = strings.TrimSpace(match[1])
	}
	return enc, rootID
}

func selectPanObjectID(files []panFileEntry) (objectID string, usedFallback bool) {
	for _, file := range files {
		if isDefaultPanPhoto(file.Name) {
			if id := firstPanObjectID(file); validPanObjectID(id) {
				return id, false
			}
		}
	}
	for _, file := range files {
		if isPanImageName(file.Name) {
			if id := firstPanObjectID(file); validPanObjectID(id) {
				return id, true
			}
		}
	}
	return "", false
}

func inspectPanUpload(data []byte, claimedType string) (mimeType string, filename string, err error) {
	if len(data) == 0 {
		return "", "", fmt.Errorf("所选图片为空")
	}
	if len(data) > panUploadMaxBytes {
		return "", "", fmt.Errorf("所选图片超过6MiB上限，请选择较小的JPG或PNG")
	}
	normalizedClaimedType := ""
	if strings.TrimSpace(claimedType) != "" {
		parsedType, _, parseErr := mime.ParseMediaType(claimedType)
		if parseErr != nil {
			return "", "", fmt.Errorf("所选文件类型无效")
		}
		normalizedClaimedType = strings.ToLower(parsedType)
		if normalizedClaimedType != "image/jpeg" && normalizedClaimedType != "image/jpg" && normalizedClaimedType != "image/pjpeg" && normalizedClaimedType != "image/png" {
			return "", "", fmt.Errorf("仅支持JPG或PNG图片")
		}
	}
	config, format, decodeErr := image.DecodeConfig(bytes.NewReader(data))
	if decodeErr != nil {
		return "", "", fmt.Errorf("所选文件不是有效的JPG或PNG图片")
	}
	if config.Width <= 0 || config.Height <= 0 || int64(config.Width)*int64(config.Height) > panUploadMaxPixels {
		return "", "", fmt.Errorf("图片尺寸过大，无法安全上传")
	}
	switch format {
	case "jpeg":
		if normalizedClaimedType != "" && normalizedClaimedType != "image/jpeg" && normalizedClaimedType != "image/jpg" && normalizedClaimedType != "image/pjpeg" {
			return "", "", fmt.Errorf("图片内容与文件类型不一致")
		}
		return "image/jpeg", "selected-photo.jpg", nil
	case "png":
		if normalizedClaimedType != "" && normalizedClaimedType != "image/png" {
			return "", "", fmt.Errorf("图片内容与文件类型不一致")
		}
		return "image/png", "selected-photo.png", nil
	default:
		return "", "", fmt.Errorf("仅支持JPG或PNG图片")
	}
}

func parsePanUploadToken(data []byte) (string, error) {
	if len(data) == 0 || len(data) > panUploadTokenMaxBytes {
		return "", fmt.Errorf("云盘上传凭据无效")
	}
	trimmed := strings.TrimSpace(string(data))
	if trimmed == "" || strings.HasPrefix(strings.ToLower(trimmed), "<html") || strings.Contains(trimmed, "passport2.chaoxing.com/login") {
		return "", fmt.Errorf("获取云盘上传凭据失败，请重新登录")
	}
	var raw json.RawMessage
	if json.Valid(data) {
		raw = json.RawMessage(data)
	}
	if len(raw) > 0 {
		if token := findPanToken(raw, 0); validPanOpaqueToken(token) {
			return token, nil
		}
		return "", fmt.Errorf("云盘上传凭据响应格式不受支持")
	}
	trimmed = strings.Trim(trimmed, "\"'")
	if !validPanOpaqueToken(trimmed) {
		return "", fmt.Errorf("云盘上传凭据格式无效")
	}
	return trimmed, nil
}

func findPanToken(raw json.RawMessage, depth int) string {
	if depth > 4 {
		return ""
	}
	var token string
	if json.Unmarshal(raw, &token) == nil {
		return token
	}
	var object map[string]json.RawMessage
	if json.Unmarshal(raw, &object) != nil {
		return ""
	}
	for _, key := range []string{"token", "_token"} {
		if value, ok := object[key]; ok {
			if json.Unmarshal(value, &token) == nil && token != "" {
				return token
			}
		}
	}
	if value, ok := object["data"]; ok {
		return findPanToken(value, depth+1)
	}
	return ""
}

func validPanOpaqueToken(value string) bool {
	if len(value) == 0 || len(value) > 4096 {
		return false
	}
	for _, r := range value {
		if unicode.IsSpace(r) || unicode.IsControl(r) || r == '<' || r == '>' {
			return false
		}
	}
	return true
}

func parsePanUploadObjectID(data []byte) (string, error) {
	if len(data) == 0 || len(data) > panUploadMaxResponseBytes {
		return "", fmt.Errorf("云盘上传响应无效")
	}
	var raw json.RawMessage
	if !json.Valid(data) {
		return "", fmt.Errorf("云盘未返回可用图片ID")
	}
	raw = json.RawMessage(data)
	objectID := findPanObjectID(raw, 0)
	if !validPanObjectID(objectID) {
		return "", fmt.Errorf("云盘未返回可用图片ID")
	}
	return objectID, nil
}

func findPanObjectID(raw json.RawMessage, depth int) string {
	if depth > 4 {
		return ""
	}
	var object map[string]json.RawMessage
	if json.Unmarshal(raw, &object) != nil {
		return ""
	}
	for _, key := range []string{"objectId", "objectID", "objectid"} {
		if field, ok := object[key]; ok {
			var value string
			if json.Unmarshal(field, &value) == nil && validPanObjectID(value) {
				return value
			}
		}
	}
	for _, key := range []string{"data", "result"} {
		if nested, ok := object[key]; ok {
			if value := findPanObjectID(nested, depth+1); value != "" {
				return value
			}
		}
	}
	return ""
}

func firstPanObjectID(file panFileEntry) string {
	if value := strings.TrimSpace(string(file.ObjectID)); value != "" {
		return value
	}
	return strings.TrimSpace(string(file.ID))
}

func isPanImageName(name string) bool {
	name = strings.ToLower(strings.TrimSpace(name))
	return strings.HasSuffix(name, ".jpg") || strings.HasSuffix(name, ".jpeg") || strings.HasSuffix(name, ".png")
}

func isDefaultPanPhoto(name string) bool {
	name = strings.ToLower(strings.TrimSpace(name))
	return name == "0.jpg" || name == "0.png"
}

func validPanObjectID(value string) bool {
	if len(value) == 0 || len(value) > 256 {
		return false
	}
	for _, r := range value {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '_' || r == '-' || r == '.' {
			continue
		}
		return false
	}
	return true
}
