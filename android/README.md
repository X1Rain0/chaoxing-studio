# Chaoxing Studio for Android

Native Android prototype. The Android app owns screens, lifecycle, monitoring service, notifications, and device storage; the Go AAR reuses the existing Chaoxing HTTP/sign-in implementation.

## Prototype scope

- Single account; login credentials are encrypted with a non-exportable Android Keystore AES/GCM key and removed on logout.
- All six sign-in kinds use the existing Go client. The user can choose foreground or background monitoring; the interval defaults to 60 seconds and is adjustable from 30 seconds to 5 minutes.
- Auto-sign and monitoring are separate controls. Auto-sign can process ordinary, photo, and location activities when default coordinates are configured; QR, gesture, and teacher-provided sign-in codes always require the current activity dialog and are never saved or reused across activities. Background mode keeps one quiet foreground-service notification and alerts only on newly discovered activities.
- QR sign-in uses a camera scanner with a paste fallback that stays collapsed until requested; accepted QR text is discarded after extracting the temporary token. Location sign-in keeps a saved default point and can use a temporary map point for one activity.
- Manual photo sign-in uses the Android system Photo Picker, validates and previews JPEG/PNG locally (6 MiB / 40 MP limits), then uploads only after the user confirms. The returned object ID stays in the current activity dialog; an upload failure does not start sign-in, and retry after upload reuses the same ID. Auto-sign retains the existing cloud `0.jpg` / `0.png` default strategy. The personal photo-upload endpoint is covered by fake-transport tests but has not been exercised against a real account; no synthetic image has been uploaded or sign-in submitted during tests. Gesture sign-in supports the 3×3 canvas and a keyboard/screen-reader 1–9 sequence input using the same midpoint insertion rules. Sign codes keep leading zeroes.
- Reopening the app restores the saved monitoring intent. Boot recovery is a separate opt-in, off by default.
- Location sign includes an in-app Amap tile picker using GCJ-02, with tap-to-select, pan/zoom, and vector/satellite layers. It needs no location permission or Amap key; place-name search and reverse-geocoded addresses are not included yet.
- The `specialUse` foreground-service declaration and boot-start behavior are provisional until APK/device validation across Android versions.

## Build prerequisites

- JDK 17 (the verified toolchain for this project)
- Android SDK Platform 36 and Android Build Tools installed through Android Studio
- Android NDK installed through SDK Manager
- Go 1.26+ (the repository currently declares Go 1.26.0)
- Windows: set `ANDROID_HOME` or `ANDROID_SDK_ROOT`; the AAR script finds an NDK under `ANDROID_HOME\ndk` or accepts `ANDROID_NDK_HOME`

## Build

From the repository root, first generate the Go AAR:

```powershell
.\android\build-go-aar.ps1
```

Then build from `android/` with the checked-in Gradle wrapper (JDK 17 is used for the verified toolchain):

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

`app/libs/mobilebridge.aar` is generated, not committed, so a fresh clone must run `build-go-aar.ps1` before Gradle. That path was verified end to end: clone → `go test ./...` → `build-go-aar.ps1` → `:app:testDebugUnitTest :app:assembleDebug`, giving 24 passing JVM tests and a ~75.7 MiB debug APK.

Build the APK from a clean tree when reporting its size or hash. A stale `app/build/` can leave padding inside `app-debug.apk` — one earlier build measured 96.4 MiB while carrying the same 75.7 MiB of entries; `:app:clean :app:assembleDebug` restored the real size.

## CI

`.github/workflows/build.yml` runs an `android` job on GitHub-hosted `ubuntu-latest`: Temurin 17, Go 1.26, Android SDK Platform 36, Build Tools 36.0.0, NDK 27.3.13750724, then `go tool gomobile bind` with the same `-androidapi 21` and `-Wl,-z,max-page-size=16384` flags. The job verifies every `libgojni.so` `LOAD` segment is `0x4000`-aligned with `llvm-readelf`, runs `go test ./internal/... ./mobilebridge/...`, `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, and re-checks the APK with `apksigner` and `zipalign -c -P 16`.

It triggers on pushes to `main` that touch `android/**`, `mobilebridge/**`, `internal/**`, `go.mod`, `go.sum`, or the workflow itself, plus every `v*` tag and manual dispatch. The desktop job only builds on tags and manual dispatch. APKs are published as the `ChaoxingStudio-android-debug` artifact (zip, ~43 MiB); on a tag, both the Windows exe and the APK are attached to the same release under the release-friendly name `ChaoxingStudio-android.apk`, so `releases/latest/download/ChaoxingStudio-android.apk` always resolves to the newest one. The release notes live in `.github/release-notes.md` and both jobs pass it as `body_path`.

The root package is excluded from CI's Go tests on purpose: it is the Wails desktop entry point and needs cgo with gtk3/webkit2gtk, which only the Windows job builds. Because the Go toolchain and linker differ between Windows and the Linux runner, an APK built in CI will not hash-match a local Windows build of the same commit — compare the commit, not the hash.

## Device smoke test

After starting one API 35/36 Android emulator or connecting one Android phone, run this from the repository root. Physical phones need USB debugging enabled and their RSA prompt accepted:

```powershell
.\android\device-smoke.ps1
```

If more than one device is attached, pass `-Serial <device-id>`. The script installs the debug APK, launches the main activity, and stores a screenshot, run transcript, and app-process log in the ignored `android/build/device-smoke/` directory. The transcript also records manufacturer/model, Android release/API, security patch, and the `POST_NOTIFICATIONS` permission check to help fill the device record. Use [`device-validation.md`](device-validation.md) for the manual UI, map, notification, monitoring, and boot-recovery checklist. No device-level checks are claimed until those steps are run on an emulator or physical phone; OEM-specific background restrictions still need physical-device validation.

The embedded map math and pointer behavior can be regression-tested off-device with Node.js (no extra packages):

```powershell
node .\android\test-map-picker.mjs
```

The test covers the default GCJ-02 center, longitude wrapping across the date line, Android viewport-height injection, zoom/base-layer redraw, and distinguishing a pan from a tap.

To open the map dialog on a debug build before logging in, run `adb -s <device-id> shell am start -n com.x1rain0.chaoxingstudio/.MainActivity --ez debug_map_picker true`. This debug-only route does not persist the selected coordinates; release builds ignore the extra.

For read-only sign-flow previews, use `adb -s <device-id> shell am start -n com.x1rain0.chaoxingstudio/.MainActivity --ei debug_sign_preview 1` for photo or `--ei debug_sign_preview 3` for gesture. The route requires an existing login to reach the main screen, displays a synthetic local activity, disables the submit button, and has a second callback guard so it cannot submit a sign request or upload a photo; release builds ignore the extra.

Manual photo sign uses the system Photo Picker: pick a JPG/PNG from the phone gallery, the dialog decodes a local preview (≤768 px, 6 MiB and 40 MP caps), and only an explicit confirm uploads it and submits that one activity. Uploaded object IDs, picked URIs, and image bytes live only inside the open dialog; nothing is written to settings, and auto-sign for photo activities keeps using the cloud `0.jpg`/`0.png` fallback. If the upload succeeds but the activity disappears before submission, that image stays in the account cloud — the app has no delete API, so only re-pick when the activity is still listed.

Pure Kotlin tests cover activity-age labels, per-kind action/submit labels, QR token parsing, 3×3 gesture mapping with midpoint insertion, thumbnail sampling, photo-selection gating, auto-sign input gates, and the opt-in background-only boot-restore policy. The 24 JVM tests run without a phone. From `android/`, run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

The generated `android/app/libs/mobilebridge.aar` is ignored; regenerate it whenever `mobilebridge/` or the Go Chaoxing packages change. The Go AAR build strips DWARF data (`-w`) and passes `-Wl,-z,max-page-size=16384`, keeping the download smaller while giving `libgojni.so` 16 KB ELF page alignment. Local SDK paths and Gradle build outputs are ignored as well.

The first prototype targets API 36 and minSdk 26. Background monitoring uses a provisional `specialUse` foreground-service declaration and is not considered validated until tested on Android devices across supported API levels. Boot recovery is a separate opt-in setting. A denied boot-time service start must leave the app able to resume monitoring when opened.
