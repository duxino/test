# Ghostlay (TX9 ESP overlay test)

Based on the original Ghostlay demo: a floating overlay that is visible on the physical screen but hidden from screen recordings/screenshots/casting via SurfaceFlinger's `SKIP_SCREENSHOT` flag on the window's `SurfaceControl` layer.

This copy is adapted to draw **ESP Line/Box** (default test shapes) for the TX9 mod menu:
- Full-screen transparent `TYPE_APPLICATION_OVERLAY` with a custom `OverlayDrawView` (line/box).
- Starts with a default visible test (red box + bottom line) so behavior can be tested immediately.
- Polls `http://127.0.0.1:6969/api/config` (TX9 modmenu_server) and applies `esp.line`, `esp.box`, `esp.linePosition`, `esp.boxStyle`, `esp.playerColor`. If the server is not reachable, the default test overlay remains visible.
- Overlay is kept hidden from capture by reapplying `SKIP_SCREENSHOT` (Ghostlay mechanism). If the flag cannot be applied, the service stops (fail-closed).

## Build
Push to GitHub; the Actions workflow builds `app-debug.apk` (artifact). Locally: `gradle assembleDebug`.

## Use with TX9
1. Build/install this app and grant "Display over other apps" (root helper in app can grant its own appop) and start the overlay.
2. Run `modmenu_server` on the same device (port 6969). Toggle ESP **Line/Box** and settings in the web panel to control the overlay.
3. Test with a screen recorder: overlay should be visible on-device but absent/hidden in capture on supported devices/Android versions.

## Notes / limits
- Uses hidden/internal APIs (`ViewRootImpl.getSurfaceControl`, `SurfaceControl.Transaction`) + `AndroidHiddenApiBypass`; behavior depends on Android version/device (same limits as Ghostlay).
- Root in this app is only used to grant its own permissions; overlay capture-hiding itself relies on the system flag.
- For use on your own device.
