# ArenaNative — Media Saving, App Icon & Battery Requirements

This document records the next feature requirements for ArenaNative. It is a development specification, not a claim that these features are already implemented.

## 1. Save AI-generated images and videos

Add native Android actions for media that the official Arena website makes legitimately accessible to the WebView:

- Save image to device Gallery (Pictures/ArenaNative).
- Save video to device Gallery (Movies/ArenaNative).
- Download to the user's Downloads folder.
- Share using Android's native Sharesheet.
- Open with a compatible app.
- Copy the media URL when a real, usable URL exists.
- Show download progress, completion/failure, and a cancel action.
- Preserve the original available quality and MIME type; do not silently recompress or resize.
- Handle duplicate filenames safely and report storage/network errors clearly.
- Provide a simple Downloads screen/history for ArenaNative-initiated downloads, with open, share, delete, retry, and cancel actions where supported.

Only show actions that are actually supported for the selected media item. Do not pretend a save succeeded before Android confirms it.

## 2. Download implementation and limitations

- Use Android DownloadManager for ordinary accessible HTTP(S) downloads.
- Where a download requires the authenticated Arena session, pass only the required session cookies and user agent securely and only to the trusted Arena origin.
- Use MediaStore for saving to Gallery on modern Android versions; use scoped storage and request only permissions that are genuinely necessary.
- Support official site download links and media URLs exposed by the page through normal, permitted browser behavior.
- Investigate `blob:` media URLs carefully. A blob URL is not an ordinary network URL and DownloadManager cannot download it directly. If support is technically possible, use a narrowly scoped, origin-validated bridge and bounded/chunked streaming to a temporary file. Never convert a large video into one huge Base64 string or hold the whole file in memory.
- Never bypass authentication, paywalls, DRM, access controls, or Arena restrictions. Do not scrape undocumented APIs or guess hidden media endpoints.
- If Arena does not expose the generated media in a way the app can lawfully and reliably access, explain that limitation and leave the website's own controls available. Do not promise that every generated item can always be saved.
- Validate MIME types, filenames, size limits, and destination paths. Do not expose a JavaScript bridge to arbitrary external origins. Clean up temporary files after success, failure, or cancellation.

## 3. App icon and branding

Create and include a complete launcher icon set; the app must not use the default Android robot or a blank icon.

- Design an original AI-inspired symbol, visually simple and recognizable at small sizes.
- Use a deep/dark background with restrained electric blue and violet accents.
- Do not copy Arena's official logo or imply official affiliation.
- Include adaptive icon foreground/background resources, legacy launcher mipmap resources, and a monochrome icon where supported.
- Set the correct application icon in AndroidManifest.xml.
- Verify it appears correctly in the launcher/app drawer, app info/settings, and recent-apps/task switcher.
- Check circular, squircle, and other adaptive launcher masks; keep important artwork inside the safe area.

## 4. Battery and low-end device performance

Battery efficiency is a priority. Keep the app responsive on entry-level Android phones.

- Do not add background polling, auto-refresh, auto-download, continuous network monitoring, unnecessary foreground services, or wake locks.
- Do not request battery-optimization exemptions.
- Start downloads only after an explicit user action. Limit simultaneous downloads to 1–2 by default and provide pause/cancel where supported.
- Use Android DownloadManager or OS-managed transfer mechanisms for suitable downloads rather than custom always-running workers.
- Avoid high-frequency timers and excessive progress UI updates; update progress at a modest interval.
- Avoid autoplaying media; respect Arena/site playback controls and Android power-saving behavior.
- Minimize dependencies, analytics, trackers, animations, and unnecessary logging.
- Reuse a single WebView where practical; release resources correctly when the Activity is actually destroyed.
- Keep large files streamed to disk with bounded memory use. Never load entire images/videos into RAM unnecessarily.
- Ensure downloads and UI work do not block the main thread.

## 5. User experience

- Add a clear native media action menu or contextual actions for supported image/video items.
- Add a simple Downloads page reachable from the app menu/settings.
- Provide clear states: waiting, downloading, saved, failed, cancelled.
- Use Android's native permission and confirmation flows; do not ask for broad storage access when MediaStore is sufficient.
- Keep controls accessible, touch-friendly, and consistent with both light and dark themes.

## 6. Security and privacy

- Keep Arena login/session data within the official Arena origin and WebView cookie store.
- Validate every URL before handing it to DownloadManager or an external app.
- Restrict any JavaScript bridge to the trusted Arena origin and expose only the minimum required methods.
- Do not log cookies, authentication headers, prompts, generated content, or private media URLs.
- Do not add analytics or third-party tracking.

## 7. Acceptance tests

Before marking these requirements complete, test on a real Android device:

1. Save an ordinary image exposed by the official site to Gallery and open it.
2. Save an ordinary video exposed by the official site to Gallery and play it.
3. Download an accessible HTTP(S) file and verify progress, cancel, and retry behavior.
4. Test authenticated downloads without leaking cookies to another domain.
5. Test a blob-based media item if Arena currently uses one; document clearly if it cannot be supported safely.
6. Verify duplicate filenames and low-storage/network-failure behavior.
7. Verify app icon on launcher and recent-apps screens.
8. Verify light/dark/system theme and status/navigation bar insets remain correct.
9. Confirm idle battery use is minimal: no polling, wake locks, or persistent background work.
10. Test on a low-RAM device and confirm large media does not cause excessive memory use or app crashes.

## 8. Implementation status

These are requested requirements. Update this section as work is implemented and tested:

- [ ] Image save to Gallery / Downloads / Share
- [ ] Video save to Gallery / Downloads / Share
- [ ] Download progress, cancel, retry, and history
- [ ] Safe handling or documented limitation for blob media
- [ ] Custom adaptive and legacy launcher icons
- [ ] Battery/performance checks on a low-end device
- [ ] Acceptance tests completed
