# ArenaNative
A lightweight Android WebView wrapper for the official Arena website. Arena's own website, login, account and AI services remain the source of truth.

## Included
- Loads https://arena.ai
- JavaScript, DOM storage and cookies for the web app
- Android file chooser for uploads
- Android DownloadManager for ordinary downloadable URLs
- WebView back navigation and HTML5 fullscreen
- GitHub Actions workflow for debug APK builds

## Build
Open this repository's **Actions** tab and run **Build Arena APK**, or push a commit to main. Download the artifact named **ArenaNative-debug-apk** from the completed workflow run.

## Notes
This app does not recreate Arena, use undocumented APIs, bypass login, or require an Arena API key. WebView compatibility depends on Arena's current site implementation. Some browser-only features, CAPTCHA, blob/stream downloads, camera or DRM media may still require Chrome. Test the official site's login, uploads and core features on-device.
