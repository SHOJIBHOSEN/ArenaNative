# ArenaNative
A lightweight Android WebView wrapper for the official Arena website. Arena's own website, login, account and AI services remain the source of truth.

## Included
- Loads https://arena.ai
- JavaScript, DOM storage and cookies for the web app
- Android file chooser for uploads
- Android DownloadManager for ordinary downloadable URLs
- WebView back navigation and HTML5 fullscreen
- GitHub Actions workflow for debug APK builds

## Feature requirements
See [Media Saving, App Icon & Battery Requirements](docs/MEDIA_ICON_BATTERY_REQUIREMENTS.md) for the requested image/video saving options, download management, custom app icon, battery-efficiency requirements, security constraints, and acceptance tests. These are requirements to implement and verify, not features claimed to be complete.

## Build
Build the Android APK with Codemagic using the `android-debug-apk` workflow in `codemagic.yaml`. Download the generated debug APK from the completed Codemagic build artifacts.

## Notes
This app does not recreate Arena, use undocumented APIs, bypass login, or require an Arena API key. WebView compatibility depends on Arena's current site implementation. Some browser-only features, CAPTCHA, blob/stream downloads, camera or DRM media may still require Chrome. Test the official site's login, uploads and core features on-device.
