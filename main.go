package main

import (
	"embed"
	"os"
	"path/filepath"
	"syscall"
	"unsafe"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/windows"
)

//go:embed all:frontend/dist
var assets embed.FS

// writableDir 建目录并真的写一个探针文件，确认当前进程有写权限。
// WebView2 的浏览器进程要在这个目录里建 profile，权限不够时
// 控制器创建会直接失败（0x800700AA），所以必须提前验证。
func writableDir(dir string) bool {
	if dir == "" {
		return false
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return false
	}
	probe := filepath.Join(dir, ".writetest")
	if err := os.WriteFile(probe, []byte("ok"), 0o644); err != nil {
		return false
	}
	_ = os.Remove(probe)
	return true
}

// webviewDataPath 依次探测可用的 WebView2 用户数据目录：
//  1. %LOCALAPPDATA%\ChaoxingStudio\WebView2 —— 常规安装首选，
//     与 %APPDATA% 下的 data.json 分开，卸载时便于整体清理；
//  2. <exe 所在目录>\webview-data —— 便携式 / 受限目录（低完整性标签）场景，
//     进程对同目录通常仍有写权限；
//  3. %TEMP%\ChaoxingStudio-WebView2 —— 最后兜底。
func webviewDataPath() string {
	var candidates []string
	if base := os.Getenv("LOCALAPPDATA"); base != "" {
		candidates = append(candidates, filepath.Join(base, "ChaoxingStudio", "WebView2"))
	} else if base, err := os.UserCacheDir(); err == nil {
		candidates = append(candidates, filepath.Join(base, "ChaoxingStudio", "WebView2"))
	}
	if exe, err := os.Executable(); err == nil {
		candidates = append(candidates, filepath.Join(filepath.Dir(exe), "webview-data"))
	}
	candidates = append(candidates, filepath.Join(os.TempDir(), "ChaoxingStudio-WebView2"))

	for _, dir := range candidates {
		if writableDir(dir) {
			return dir
		}
	}
	return ""
}

// fatalDialog 弹一个系统消息框再退出：GUI 程序没有控制台，
// 出错时用户至少能看到原因，而不是窗口一闪就没了。
func fatalDialog(msg string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	proc := user32.NewProc("MessageBoxW")
	title, _ := syscall.UTF16PtrFromString("学习通签到工坊")
	text, _ := syscall.UTF16PtrFromString(msg)
	// MB_ICONERROR | MB_OK
	proc.Call(0, uintptr(unsafe.Pointer(text)), uintptr(unsafe.Pointer(title)), 0x00000010)
	os.Exit(1)
}

func main() {
	app := NewApp()

	err := wails.Run(&options.App{
		Title:     "学习通签到工坊",
		Width:     1320,
		Height:    880,
		MinWidth:  1120,
		MinHeight: 720,
		AssetServer: &assetserver.Options{
			Assets: assets,
		},
		BackgroundColour: &options.RGBA{R: 11, G: 13, B: 18, A: 1},
		OnStartup:        app.startup,
		OnShutdown:       app.shutdown,
		Bind: []interface{}{
			app,
		},
		Windows: &windows.Options{
			Theme:               windows.Dark,
			WebviewUserDataPath: webviewDataPath(),
		},
	})

	if err != nil {
		fatalDialog("程序启动失败：\n" + err.Error())
	}
}
