# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

delegated: Kotlin native Android application (Jetpack Compose UI, Android lifecycle and services) plus a narrow Go gomobile AAR bridge for the existing Chaoxing protocol core; direct APK distribution through GitHub Releases. The user approved this route on 2026-11-26.

## Users

A single Chaoxing/Learning通 account holder who wants to monitor attendance activities from an Android phone and optionally submit sign-ins without keeping the app screen open.

## Product Purpose

Chaoxing Studio's Android edition monitors selected courses for active sign-in activities, notifies the user when a new activity appears, and can submit supported sign-in types when automatic sign-in is enabled.

## Operating Context

The user chooses whether monitoring runs in the foreground or background. Background mode reports new activities rather than producing a status notification on every polling cycle. The user can return to the app to review state, change options, or stop monitoring. The last monitoring intent is restored when the app is opened again.

## Capabilities and Constraints

- Android only; no iPhone edition is planned.
- One account; retain the login session across app restarts. Store credentials using Android Keystore-backed protection; logging out clears saved credentials.
- Support the six existing sign-in types: ordinary, photo, QR code, gesture, location, and sign-in code.
- Automatic sign-in and background monitoring are independent settings.
- Monitoring interval defaults to 60 seconds and is adjustable.
- Location sign includes an in-app Amap GCJ-02 tile map for tap-to-select coordinates; map search and reverse geocoding are deferred.
- A separate boot auto-start setting defaults off.
- Background notifications are for newly discovered activities, not each polling cycle.
- The app must use native Android service, notification, permission, and lifecycle behavior. The exact foreground-service type and Android 15+ boot-recovery behavior remain an implementation question to verify in a device prototype.
- Existing desktop protocol code is the reuse source; Wails and Windows-specific startup code are not part of the Android app.
- Distribution target approved for the first release: APK downloaded directly from GitHub Releases. Google Play is not the current target.
- Inferred from the existing desktop implementation: preserve the local-first product behavior and do not introduce a Chaoxing Studio cloud backend.

## Brand Commitments

- Product name remains Chaoxing Studio / 学习通签到工坊; Android is the mobile edition of the existing product, not a separate brand.
- The existing desktop design uses a point-roster identity with a vermilion sign-in stamp. Android should carry that recognizable identity while following Android-native interaction conventions.

## Evidence on Hand

- Existing desktop source and feature documentation in this repository; the README documents all six sign-in types, the polling behavior, and the map picker.
- Desktop screenshots: `docs/overview.png`, `docs/settings.png`, and `docs/picker.png`.
- The Android Kotlin/Compose prototype builds a debug APK with the generated Go AAR. MuMu Android 15/API 35 has verified cold launch, signed-in/session and course-state restoration after force-stop, the Settings map callback (unsaved test point discarded on relaunch), and one foreground poll/start-stop with autoSign off. The debug map-picker WebView also passed tile/tap/pan/zoom/vector-satellite checks; screenshots are under `android/build/device-smoke/`. No sign submission was performed; notification permission remains denied, so notifications/background service/boot recovery and physical-device OEM behavior remain unverified.

## Product Principles

- Give the user control over monitoring, automatic submission, and boot recovery as separate choices.
- Keep credentials on-device and protect them with platform storage.
- Notify on meaningful activity, not routine polling noise.
- Reuse the tested protocol core where its platform boundary is clean; use native Android facilities for app lifecycle and user interaction.
