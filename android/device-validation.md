# Android 设备验收清单

当前验证对象：`app-debug.apk`（`com.x1rain0.chaoxingstudio`，v0.1.0，min API 26 / target API 36）。这份清单适用于 API 35/36 模拟器或实体手机；尚未完成的项目保持未勾选。模拟器适合验证界面、地图、登录、通知和基础服务流程；厂商专属的后台限制仍需实体手机复核。

## 0. 记录设备环境

- 设备型号 / 厂商（模拟器填写镜像与设备配置）：
- Android 版本 / API：
- 安全补丁日期：
- 是否启用 USB 调试：
- 通知权限状态：
- 电池优化/后台限制状态：
- APK SHA-256：`547D79F0C59F830A14D8FB892113A07C520854C25407F0CE48EE82CB0B218D47`（77,155,180 bytes，v1.2.0 Release 附件，CI 构建）

## 1. 安装与首屏

- [x] 启动 Android 模拟器，或连接手机；实体手机需允许 USB 调试 RSA 弹窗；`adb devices -l` 显示 `device` 状态。
- [x] 从仓库根目录运行 `powershell -ExecutionPolicy Bypass -File .\android\device-smoke.ps1`；多台设备时附加 `-Serial <device-id>`。
- [x] 检查脚本保存的首屏截图 `android/build/device-smoke/screen-<serial>.png` 和运行记录。
- [x] 已登录后的概览、活动卡片、自动签到开关和底部导航正常显示。
- [ ] 有活动卡片时检查计数区域可滚动查看；系统返回键行为正常（尚未实测）。

## 2. 登录与本地状态

- [x] 登录后课程列表加载，课程选择状态可见。
- [x] Force-stop 并重开后，登录态与已保存的课程选择恢复。
- [ ] 登出后重新打开，确认凭据和会话已清除。
- [ ] 用户主动取消所有课程后重开，确认不会把全部课程自动重新选中。

## 3. 位置签到地图

- [x] 设置 → 位置签到 →「在地图上选点」打开地图；高德瓦片能加载。
- [x] 平移、缩放、矢量/卫星切换都工作；点击地图后坐标更新为 GCJ-02，经纬度约 6 位小数。
- [ ] 使用位置并保存，返回设置页后经纬度保留；再次进入地图时落点中心正确。
- [x] 未授予设备定位权限也能点选；地名搜索和地址反查当前未实现。

## 4. 前台巡检与停止语义

- [x] 已授予通知权限；巡检间隔默认 60 秒。
- [ ] 调整巡检间隔、保存并确认下一轮使用新间隔。
- [x] 前台启动巡检，最近扫描时间更新并显示活动卡片。
- [x] 手动停止后 force-stop / 重开应用，巡检保持停止。
- [ ] 再次手动开始后，应用进入后台并返回，前台模式在可见时恢复，离开前台按设计暂停。

## 5. 后台巡检、通知与自动签到

- [x] 选择后台模式并启动；`MonitorService`成为前台服务，状态栏显示常驻通知（`specialUse`，`startForegroundCount=1`）。
- [x] 按Home后服务仍在后台；返回应用时扫描时间继续推进。
- [x] 手动Stop后服务退出，活动通知清空；force-stop并重开时，未停止的后台巡检意图会恢复。
- [ ] 新活动发现时只发一次活动通知；普通扫描轮次不产生通知（本轮未出现新活动，未验证）。
- [ ] 通知权限被拒绝时，界面给出状态且不假报后台巡检正在运行。
- [x] `autoSign`默认关闭且与巡检开关独立；关闭时未提交签到（用户明确选择不进行真实签到提交）。
- [x] 照片签到改为手机相册选图：Photo Picker 选中后只在本机生成本地预览，确认过的预览才允许提交（本轮以 `--ei debug_sign_preview 1` 预览模式验证，未上传、未提交）。
- [x] 手势签到键盘输入 `1357` 保持原样，自动补中点后显示“已连接 5 个点”（中途点 2），与画布一致。
- [ ] 在合适的测试活动中验证六种签到及对应输入；本次测试未提交任何真实签到，也未向学习通上传任何图片。
- [ ] 真实上传路径（`/api/token/uservalid` → `/upload?_token=`）只在 Go Fake Transport 单测里覆盖，尚未用真实账号上传验证。
- [ ] 任务划掉、系统回收进程后，后台服务能否按设计继续；记录厂商电池管理的影响。

## 6. 重开与开机恢复

- [ ] 后台巡检开启时手动关闭/重开应用，监控意图恢复。
- [ ] 开机恢复开关默认关闭；关闭时重启模拟器/设备后服务保持停止。
- [ ] 单独打开开机恢复后重启模拟器/设备，记录服务/通知是否恢复，以及系统是否限制了从 `BOOT_COMPLETED` 启动 `specialUse` FGS。

## 7. 日志与产物

- 最新分支 APK（79,367,020 bytes = 75.7 MiB；SHA-256见本页开头，来自 `:app:clean :app:assembleDebug`）已完成离线构建、v2签名和16 KB zipalign检查；24项Kotlin JVM测试、Go tests/vet、Android arm64 bridge build及Node地图回归全部通过。同一提交在全新克隆（`git clone --depth 1` → `go test ./...` → `build-go-aar.ps1` → `:app:testDebugUnitTest :app:assembleDebug`）里同样构建成功且24项测试全过，说明仓库自身可复现构建（AAR 不入库）。本轮在 MuMu 15/API35 上走通了相册选图→本地预览→预览模式禁用提交，并修正手势键盘被输入法改写的缺陷；真实上传与真实签到均未执行。

- [ ] `android/device-smoke.ps1` 会在 `android/build/device-smoke/` 保存安装/启动记录、截图和（进程存活时）仅目标进程的 logcat。
- [ ] 设备/API、每个失败步骤、截图与日志文件名记录在本页下方；不把个人课程信息或账号凭据写入公开仓库。

### 实测记录

| 日期 | 设备 / Android API | 项目 | 结果 / 证据 |
|---|---|---|---|
| 2026-11-27 | MuMu 6.8.8.0; Android 15 / API 35; vivo V2344A / PD2344; patch 2026-11-05 | Install + cold launch | PASS: `Status: ok`, `LaunchState: COLD`, 0.914s; normal login screen; no fatal signature in app log. `POST_NOTIFICATIONS` was denied on first install and later manually granted for the background FGS test. Screenshot: `android/build/device-smoke/screen-127.0.0.1_16384.png`; transcript: `android/build/device-smoke/run-127.0.0.1_16384.txt`; app log: `android/build/device-smoke/logcat-127.0.0.1_16384.txt`. |
| 2026-11-27 | Same device | Debug map picker | PASS: Amap vector tiles loaded; tap updated GCJ-02 display and native callback; pan retained the point; zoom and satellite/vector switch worked; debug route intentionally does not persist. Screenshots: `map-picker-debug-20261127-004047.png`, `map-point-20261127-004119.png`, `map-zoom-20261127-004137.png`, `map-satellite-20261127-004155.png`, `map-pan-20261127-004222.png`, `map-retap-20261127-004222.png`. |
| 2026-11-27 | Same device | Login/course restore | PASS: signed-in state and selected-course state remained after force-stop and cold relaunch; no account or course names recorded here. |
| 2026-11-27 | Same device | Settings map callback | PASS: opened from Settings, tap updated the form through the real Compose callback; temporary test point was not saved. After force-stop/reopen, coordinate fields were blank again. |
| 2026-11-27 | Same device | Foreground poll/stop | PASS: start advanced the latest-scan time and surfaced an activity card; autoSign remained off; Stop changed the overview back to stopped, and force-stop/reopen remained stopped. No sign submission was performed. |
| 2026-11-27 | Same device | Background FGS | PASS: after manually granting notification permission, `MonitorService` ran foreground with a persistent notification; Home/return kept scanning; Stop removed the service and notification; force-stop/reopen restored the active background intent. No new event notification was available to test; autoSign stayed off. |
| 2026-11-27 | Same device | Per-activity photo picker (preview mode) | PASS: photo dialog opened with `--ei debug_sign_preview 1`; system Photo Picker listed the pushed test PNG; after selection the dialog rendered a local preview (`PNG · 3 KB · 仅在本次窗口暂存`) and the CTA stayed disabled (`仅预览，不提交`). App log had no upload/`pan-yz` request lines and no fatal exception. Test image and screenshots were deleted afterwards. |
| 2026-11-27 | Same device | Gesture keyboard regression | PASS: typing `1357` in the gesture field kept the raw text `1357` (previously rewritten to `12573` by the IME cursor), auto-inserted midpoint 2, and reported `已连接 5 个点` with the matching canvas path. No submission performed. |

### CI 构建记录

| 日期 | Run | 提交 | 结果 / 证据 |
|---|---|---|---|
| 2026-11-27 | Actions run `45954690381` | `7034895` | PASS：`ubuntu-latest` + Temurin 17 + Go 1.26 + SDK 36 / Build Tools 36.0.0 / NDK 27.3.13750724；`go test ./internal/... ./mobilebridge/...` 全过（chaoxing 1.057s、mobilebridge 0.002s）；AAR 12,641,147 B；四个 ABI 的 `libgojni.so` `LOAD` 段全部 `0x4000` 对齐；Gradle 53 tasks、`BUILD SUCCESSFUL`、lint 执行完成；APK 77,155,180 B、SHA-256 `32b75bdec19163b964e520b97278403ee4232f9150a778bea8064cabad99aa08`、`apksigner` v2 true、`zipalign -c -P 16` 通过；artifact `ChaoxingStudio-android-debug` 43.56 MiB。Linux 工具链产出的 APK 与本地 Windows 构建哈希不同，比对以提交为准。 |
| 2026-11-27 | Actions run `45954623576` | `ffd6dd4` | FAIL（已修）：`Go tests` 步跑 `go test ./...` 时连仓库根的 Wails main 包一起编译，Linux 上缺 gtk3/webkit2gtk 头文件；改为只测 `./internal/...` 与 `./mobilebridge/...`，桌面版交给 windows job。 |

真实照片上传与真实签到提交仍未在 CI 或本地自动执行，由使用者本人在自己账号上验证。

### Release 记录

| 版本 | 触发 | Run | 附件 | 校验 |
|---|---|---|---|---|
| `v1.2.0` | 标签推送（提交 `1b3082f`） | `45964556764`：windows success / android success | `ChaoxingStudio.exe` 11,784,704 B、`ChaoxingStudio-android.apk` 77,155,180 B | APK SHA-256 `547d79f0c59f830a14d8fb892113a07c520854c25407f0ce48ee82cb0b218d47`（CI 日志与下载回来的 Release 附件逐字节一致）；`apksigner` v2 `Verifies`、`zipalign -c -P 16` `Verification successful`；`aapt2` 读到 `com.x1rain0.chaoxingstudio` v0.1.0、min 26 / target 36、label 学习通签到工坊；exe SHA-256 `6ba6f209d2791e31e067ff3ea89c74e94480f1925dac33da3fe21d10099d8fe6`。`releases/latest` 已指向 v1.2.0。 |

