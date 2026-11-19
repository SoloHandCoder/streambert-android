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
- **Picture-in-picture**: leaving the app while a video plays (or the pop-out button) shrinks it into a floating window.
- **Lock-screen & notification controls** (`MediaControl` + `MediaPlaybackService`): play/pause/±10s from the lock screen, notification and headset buttons; playback continues with the screen off.
- **In-app player** (`public/sb-player.*`): custom controls, double-tap ±10s, swipe to seek, brightness (left) and volume (right), quality/speed menus, subtitles (SRT converted), resume for downloads.
- **Android TV**: launcher banner, D-pad / remote navigation (reuses the desktop controller navigation).
- **Chromecast** (`CastPlugin` + `LanServer`): casts streams and downloads to the Default Media Receiver; the phone serves/proxies the media over the local network with the right headers.

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

## Releasing
```
node scripts/release.mjs 2.7.2 --notes-file notes.md
```
Bumps the version (package.json + `android/app/build.gradle`), builds the signed APK into `release/`, commits, tags `v<version>-android`, pushes, and creates the GitHub release with the APK attached.

## Auto-update
The app checks this repo's GitHub releases on startup (Settings → "Check for updates on startup") and in Settings → Check for Updates. If a newer `v*-android` release has an `.apk` asset, "Install Update" downloads it and opens the Android installer (asks once for "Install unknown apps"). The repo must be **public** for this, since the app calls the GitHub API without a token.

Release signing reads `android/keystore.properties` (not committed). Without it, the release APK is unsigned; use `assembleDebug` for a quick installable build.

## Differences from desktop
- No pop-out (PiP) window or Discord RPC. Updates come from this repo (APK) instead of the desktop releases.
- Downloads are saved in the app's storage (`Android/data/com.streambert.android/files/Movies/Streambert`) as HLS folders or MP4 and play in the built-in player; "folder" opens them in an external player (e.g. VLC).
- External player = Android "Open with" (VLC, MX Player, …).

License: GPL-3.0 (same as upstream).
