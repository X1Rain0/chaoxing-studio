package chaoxing

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"hash/crc32"
	"image"
	"image/color"
	"image/png"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestPanRootTokensSupportsLegacyAndObjectSyntax(t *testing.T) {
	cases := []string{
		`var enc = "token-value"; var _rootdir = "root-id";`,
		`const pan = { enc: 'token-value', _rootdir: 'root-id' };`,
		`const pan = { &quot;enc&quot;: &quot;token-value&quot;, &quot;_rootdir&quot;: &quot;root-id&quot; };`,
	}
	for _, page := range cases {
		enc, rootID := panRootTokens([]byte(page))
		if enc != "token-value" || rootID != "root-id" {
			t.Fatalf("panRootTokens(%q) = %q, %q", page, enc, rootID)
		}
	}

	enc, rootID := panRootTokens([]byte(`<html><title>Cloud</title></html>`))
	if enc != "" || rootID != "" {
		t.Fatalf("unexpected tokens from page without root data: %q, %q", enc, rootID)
	}
}

func TestSelectPanObjectIDPrefersZeroImageThenFirstFallback(t *testing.T) {
	files := []panFileEntry{
		{Name: "notes.txt", ObjectID: flexString("text-id")},
		{Name: "folder", ObjectID: flexString("folder-id")},
		{Name: "class-photo.JPG", ObjectID: flexString("photo-id")},
		{Name: "0.PNG", ID: flexString("default-id")},
		{Name: "no-id.png"},
	}
	id, fallback := selectPanObjectID(files)
	if id != "default-id" || fallback {
		t.Fatalf("default selection = %q, fallback=%t", id, fallback)
	}
	id, fallback = selectPanObjectID([]panFileEntry{{Name: "one.png", ObjectID: flexString("first-id")}})
	if id != "first-id" || !fallback {
		t.Fatalf("fallback selection = %q, fallback=%t", id, fallback)
	}
	id, fallback = selectPanObjectID(nil)
	if id != "" || fallback {
		t.Fatalf("empty selection = %q, fallback=%t", id, fallback)
	}
}

func TestInspectPanUploadAcceptsPNGAndEnforcesMIME(t *testing.T) {
	data := tinyPNG(t, 4, 3)
	mimeType, filename, err := inspectPanUpload(data, "image/png")
	if err != nil {
		t.Fatal(err)
	}
	if mimeType != "image/png" || filename != "selected-photo.png" {
		t.Fatalf("metadata = %q %q", mimeType, filename)
	}
	if _, _, err := inspectPanUpload(data, "image/jpeg"); err == nil {
		t.Fatal("accepted PNG bytes labeled as JPEG")
	}
	if _, _, err := inspectPanUpload([]byte("not an image"), "image/png"); err == nil {
		t.Fatal("accepted invalid image bytes")
	}
	if _, _, err := inspectPanUpload(make([]byte, panUploadMaxBytes+1), "image/png"); err == nil {
		t.Fatal("accepted image larger than upload cap")
	}
	if _, _, err := inspectPanUpload(data, "image/svg+xml"); err == nil {
		t.Fatal("accepted unsupported content type")
	}
}

func TestInspectPanUploadRejectsHugeDimensions(t *testing.T) {
	data := pngConfigHeader(7000, 7000)
	_, _, err := inspectPanUpload(data, "image/png")
	if err == nil || !strings.Contains(err.Error(), "尺寸过大") {
		t.Fatalf("expected dimension rejection, got %v", err)
	}
}

func TestParsePanUploadTokenSupportsRawAndJSON(t *testing.T) {
	for _, body := range [][]byte{
		[]byte("token-fixture-123"),
		[]byte(`{"token":"token-fixture-123"}`),
		[]byte(`{"data":{"_token":"token-fixture-123"}}`),
		[]byte(`"token-fixture-123"`),
	} {
		got, err := parsePanUploadToken(body)
		if err != nil || got != "token-fixture-123" {
			t.Fatalf("parsePanUploadToken(%s) = %q, %v", body, got, err)
		}
	}
	for _, body := range [][]byte{nil, []byte("<html>login</html>"), []byte(`{"msg":"success"}`), []byte("token with spaces")} {
		if _, err := parsePanUploadToken(body); err == nil {
			t.Fatalf("accepted invalid token response %q", body)
		}
	}
}

func TestParsePanUploadObjectIDRequiresExplicitField(t *testing.T) {
	for _, body := range [][]byte{
		[]byte(`{"objectId":"object-fixture-123"}`),
		[]byte(`{"data":{"objectid":"object-fixture-123"}}`),
		[]byte(`{"result":{"objectID":"object-fixture-123"}}`),
	} {
		got, err := parsePanUploadObjectID(body)
		if err != nil || got != "object-fixture-123" {
			t.Fatalf("parsePanUploadObjectID(%s) = %q, %v", body, got, err)
		}
	}
	for _, body := range [][]byte{nil, []byte("not json"), []byte(`"success"`), []byte(`{"msg":"success"}`), []byte(`{"objectId":"../secret"}`)} {
		if _, err := parsePanUploadObjectID(body); err == nil {
			t.Fatalf("accepted invalid object ID response %q", body)
		}
	}
}

func TestUploadPanPhotoUsesTokenAndMultipartWithoutRetry(t *testing.T) {
	client := newUploadTestClient(t)
	photoBytes := tinyPNG(t, 2, 2)
	calls := 0
	client.hc.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		calls++
		switch request.URL.Path {
		case "/api/token/uservalid":
			if request.Method != http.MethodGet || request.URL.Host != "pan-yz.chaoxing.com" {
				t.Fatalf("token request = %s %s", request.Method, request.URL)
			}
			assertPanAuthCookies(t, request)
			if request.Header.Get("Referer") != basePan+"/" {
				t.Fatalf("token referer = %q", request.Header.Get("Referer"))
			}
			return fakeHTTPResponse(request, http.StatusOK, `{"token":"token-fixture-123"}`, nil), nil
		case "/upload":
			if request.Method != http.MethodPost || request.URL.Query().Get("_token") != "token-fixture-123" {
				t.Fatalf("upload request = %s %s", request.Method, request.URL)
			}
			assertPanAuthCookies(t, request)
			mediaType, params, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
			if err != nil || mediaType != "multipart/form-data" || params["boundary"] == "" {
				t.Fatalf("upload content type = %q, err=%v", request.Header.Get("Content-Type"), err)
			}
			reader := multipart.NewReader(request.Body, params["boundary"])
			file, err := reader.NextPart()
			if err != nil {
				t.Fatal(err)
			}
			if file.FormName() != "file" || file.FileName() != "selected-photo.png" || file.Header.Get("Content-Type") != "image/png" {
				t.Fatalf("file part = name %q filename %q type %q", file.FormName(), file.FileName(), file.Header.Get("Content-Type"))
			}
			gotBytes, err := io.ReadAll(file)
			if err != nil || !bytes.Equal(gotBytes, photoBytes) {
				t.Fatalf("file part bytes mismatch, err=%v", err)
			}
			puid, err := reader.NextPart()
			if err != nil || puid.FormName() != "puid" {
				t.Fatalf("puid part = %#v, err=%v", puid, err)
			}
			puidBytes, err := io.ReadAll(puid)
			if err != nil || string(puidBytes) != "uid-fixture" {
				t.Fatalf("puid = %q, err=%v", puidBytes, err)
			}
			return fakeHTTPResponse(request, http.StatusOK, `{"msg":"success","objectId":"object-fixture-123"}`, nil), nil
		default:
			t.Fatalf("unexpected upload endpoint %s", request.URL)
			return nil, nil
		}
	})

	objectID, err := client.UploadPanPhoto("image/png", photoBytes)
	if err != nil {
		t.Fatal(err)
	}
	if objectID != "object-fixture-123" {
		t.Fatalf("objectID = %q", objectID)
	}
	if calls != 2 {
		t.Fatalf("request count = %d, want token+single upload", calls)
	}
}

func TestUploadPanPhotoDoesNotFollowRedirectOrRetry(t *testing.T) {
	client := newUploadTestClient(t)
	calls := 0
	client.hc.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		calls++
		if request.URL.Path == "/api/token/uservalid" {
			return fakeHTTPResponse(request, http.StatusOK, `token-fixture-123`, nil), nil
		}
		if request.URL.Path == "/upload" {
			return fakeHTTPResponse(request, http.StatusFound, "", http.Header{"Location": []string{"https://evil.example/collect"}}), nil
		}
		t.Fatalf("unexpected request %s", request.URL)
		return nil, nil
	})
	if _, err := client.UploadPanPhoto("image/png", tinyPNG(t, 2, 2)); err == nil {
		t.Fatal("accepted upload redirect")
	}
	if calls != 2 {
		t.Fatalf("redirect was followed: request count=%d", calls)
	}
}

func TestUploadPanPhotoNetworkFailureIsNotRetried(t *testing.T) {
	client := newUploadTestClient(t)
	calls := 0
	client.hc.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		calls++
		if request.URL.Path == "/api/token/uservalid" {
			return fakeHTTPResponse(request, http.StatusOK, `token-fixture-123`, nil), nil
		}
		return nil, errors.New("fixture network failure")
	})
	if _, err := client.UploadPanPhoto("image/png", tinyPNG(t, 2, 2)); err == nil {
		t.Fatal("expected upload network failure")
	}
	if calls != 2 {
		t.Fatalf("request count=%d, want one token request and one upload attempt", calls)
	}
}

func TestInvalidPanPhotoRejectedBeforeNetwork(t *testing.T) {
	client := newUploadTestClient(t)
	calls := 0
	client.hc.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
		calls++
		return nil, errors.New("network must not be used")
	})
	if _, err := client.UploadPanPhoto("image/png", []byte("not an image")); err == nil {
		t.Fatal("accepted invalid image")
	}
	if calls != 0 {
		t.Fatalf("invalid image triggered %d requests", calls)
	}
}

func TestSignInputPhotoObjectIDJSON(t *testing.T) {
	var input SignInput
	if err := json.Unmarshal([]byte(`{"photoObjectId":"selected-1"}`), &input); err != nil {
		t.Fatal(err)
	}
	if input.PhotoObjectID != "selected-1" {
		t.Fatalf("PhotoObjectID = %q", input.PhotoObjectID)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (fn roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) { return fn(request) }

func newUploadTestClient(t *testing.T) *Client {
	t.Helper()
	client := NewClient(Credential{}, nil)
	client.setIdentity("uid-fixture", "fid-fixture", "name-fixture")
	panURL, err := url.Parse(basePan)
	if err != nil {
		t.Fatal(err)
	}
	client.hc.Jar.SetCookies(panURL, []*http.Cookie{
		{Name: "uf", Value: "uf-fixture"},
		{Name: "_d", Value: "d-fixture"},
		{Name: "UID", Value: "uid-fixture"},
		{Name: "vc3", Value: "vc3-fixture"},
	})
	return client
}

func assertPanAuthCookies(t *testing.T, request *http.Request) {
	t.Helper()
	for name, want := range map[string]string{
		"uf":  "uf-fixture",
		"_d":  "d-fixture",
		"UID": "uid-fixture",
		"vc3": "vc3-fixture",
	} {
		cookie, err := request.Cookie(name)
		if err != nil || cookie.Value != want {
			t.Fatalf("request missing expected %s session cookie", name)
		}
	}
}

func fakeHTTPResponse(request *http.Request, status int, body string, headers http.Header) *http.Response {
	responseHeaders := make(http.Header)
	for key, values := range headers {
		responseHeaders[key] = append([]string(nil), values...)
	}
	responseHeaders.Set("Content-Type", "application/json")
	response := &http.Response{
		StatusCode: status,
		Header:     responseHeaders,
		Body:       io.NopCloser(strings.NewReader(body)),
		Request:    request,
	}
	response.ContentLength = int64(len(body))
	return response
}

func tinyPNG(t *testing.T, width, height int) []byte {
	t.Helper()
	var buffer bytes.Buffer
	canvas := image.NewRGBA(image.Rect(0, 0, width, height))
	canvas.Set(0, 0, color.RGBA{R: 200, A: 255})
	if err := png.Encode(&buffer, canvas); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}

func pngConfigHeader(width, height uint32) []byte {
	var out bytes.Buffer
	out.Write([]byte{137, 80, 78, 71, 13, 10, 26, 10})
	chunk := make([]byte, 13)
	binary.BigEndian.PutUint32(chunk[0:4], width)
	binary.BigEndian.PutUint32(chunk[4:8], height)
	chunk[8], chunk[9] = 8, 2
	binary.Write(&out, binary.BigEndian, uint32(len(chunk)))
	out.WriteString("IHDR")
	out.Write(chunk)
	checksum := crc32.ChecksumIEEE(append([]byte("IHDR"), chunk...))
	binary.Write(&out, binary.BigEndian, checksum)
	return out.Bytes()
}
