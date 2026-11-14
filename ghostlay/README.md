# Ghostlay

A floating "Hello World" box that is visible on the physical screen but absent from
screen recordings, screenshots and casting. The recorder sees the real background, not black.

## How it works
1. A normal `TYPE_APPLICATION_OVERLAY` window (touchable, draggable).
2. Its `SurfaceControl` layer gets SurfaceFlinger's `SKIP_SCREENSHOT` flag (0x40), which
   excludes the layer from anything rendered to a non-primary display (recording, mirroring).
   AOSP uses the same flag for the mouse-pointer sprite.
3. Hidden APIs are reached with `AndroidHiddenApiBypass`, scoped to `android.view.*`, in-process only.
4. Root is used only to grant the app's own `SYSTEM_ALERT_WINDOW` / `POST_NOTIFICATIONS`.
5. The overlay starts at alpha 0 and is revealed only after the flag is applied. If the flag
   can't be applied, the service stops instead of showing a capturable overlay.

## Build
Push to GitHub; the Actions workflow builds `app-debug.apk` (artifact). Locally: `gradle assembleDebug`.

## System footprint
- No `settings put`, no SELinux changes, no remount, no files outside app data, no daemon.
- Only change: the app's own appops grant. Undo with `su -c "appops reset dev.ghostlay.demo"` or uninstall.
- The flag lives on the window's layer and disappears with the window.

## Known limits
- Relies on non-SDK internals (`ViewRootImpl.getSurfaceControl`, `SurfaceControl.Transaction`); may need
  adjusting per Android release. Failures are logged under the tag `Ghostlay`.
- A just-recreated surface (rotation) may show for a frame before the flag is re-applied.
- Apps that capture the physical display by other means (e.g. camera) are of course unaffected.

For use on your own device. Don't use it to deceive people or bypass rules you've agreed to.
