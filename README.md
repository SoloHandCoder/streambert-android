# Streambert Android

Android port of [Streambert](https://github.com/truelockmc/streambert) (Electron desktop app) — same UI, adapted for phones.

## How it works
- **UI**: the desktop React app (`src/`) almost unchanged, plus `src/styles/mobile.css` (bottom nav in portrait, side rail in landscape, touch sizing).
- **`window.electron` shim** (`src/mobile/electronShim.js`): implements the desktop preload API on Android, so the pages run as-is.
- **`<webview>` → `MobileWebView`** (`src/mobile/MobileWebView.jsx`): iframe with `executeJavaScript` / load events, backed by a bridge injected into every frame (`android/app/src/main/assets/sb-inject.js`).
- **Native layer** (`android/app/src/main/java/com/streambert/android/`):
  - `SbWebViewClient` — ad/tracker blocking (desktop block list), m3u8/vtt capture, blocks embed players from hijacking the app.
  - `SbChromeClient` — popup blocking, native fullscreen with auto-landscape.
  - `StreamProxy` — in-WebView media proxy that adds the Referer (replaces the desktop's local player server for HiAnime).
  - `Downloads` + `DownloadService` — native HLS downloader (best quality, 6 parallel connections, keys/init segments, background foreground-service). Downloads play offline in-app.
- **HiAnime** resolver and **subtitles** (SubDL / Wyzie) ported to browser JS using native HTTP (no CORS).

## Build (local, no CI)
Requirements: Node 22+, JDK 21, Android SDK (platform 35).

```
npm install
npm run build
npx cap sync android
cd android
gradlew.bat assembleRelease
```
Output: `android/app/build/outputs/apk/release/app-release.apk`.

Release signing reads `android/keystore.properties` (not committed). Without it, the release APK is unsigned; use `assembleDebug` for a quick installable build.

## Differences from desktop
- No pop-out (PiP) window, Discord RPC, or desktop auto-updater.
- Downloads are saved in the app's storage (`Android/data/com.streambert.android/files/Movies/Streambert`) as HLS folders or MP4 and play in the built-in player; "folder" opens them in an external player (e.g. VLC).
- External player = Android "Open with" (VLC, MX Player, …).

License: GPL-3.0 (same as upstream).
