# Lokker

Android system app for hiding applications at the OS level. Built for LineageOS 22 / Android 15 devices where it runs as a privileged, platform-signed system app.

Current version: **v1.2a**. Full specification (in Russian, kept in sync with the code): [SPEC.md](SPEC.md).

## Features

### App Hiding
- Hides apps with `IPackageManager.setApplicationHiddenSettingAsUser()`, called via reflection. Hidden apps disappear from the launcher, Settings → Apps, `pm list packages` and every PackageManager query, and they cannot run any component or post notifications.
- Hidden state survives reboots and app updates. `BootReceiver`, `ScreenReceiver` (screen on) and `PackageMonitor` re-apply it as a safety net.
- App icons are cached as PNGs (`files/icons/`) before hiding, for use in Lokker's UI and shortcuts.
- Apps added from the picker are hidden immediately.

### Two unhide modes
- **Temporary** (default). The app is unhidden and launched with `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`. As soon as the user leaves it (Home, Back, Recents or another app), it is re-hidden and removed from Recents.
- **Unhide until closed** (v1.1). The app stays visible, and appears in Recents, until the user swipes its task away. It is then re-hidden. Triggered from the ⋮ menu, or by a per-app hotkey that has the "Unhide until closed" toggle on.
- Both pending sets are persisted. If Lokker's process dies while an app is unhidden, the app is re-hidden on the next process start.

### Authentication
- **PIN**: exactly 6 digits, hashed with PBKDF2-HMAC-SHA256 (10,000 iterations; older 310,000-iteration hashes are upgraded on the next successful login) and stored in EncryptedSharedPreferences.
- **Biometric**: optional (off by default), `BIOMETRIC_STRONG` via AndroidX BiometricPrompt, shown on top of the PIN screen.
- **Lockout**: 30 seconds after 5 failed PIN attempts.
- **Input**: on-screen numpad, hardware keyboard and IME. Entered digits show as `*`, empty slots as `_`.
- Lokker asks for the PIN again every time it is reopened. Until a PIN is set, Lokker opens without authentication.

### Hotkeys
- **Lokker hotkey** opens Lokker, even when Lokker is hidden from the launcher. There is no default; you record your own.
- **Per-app hotkeys** open a specific hidden app directly, after authentication.
- Recorded and detected by the accessibility service. Supported gestures:
  - short press;
  - long press (≥ 500 ms);
  - double or triple press (≤ 400 ms between presses);
  - hold-combo (hold one key while pressing the next).
- Sequences reset after 1.5 s of inactivity. Key events are never consumed outside recording mode, and auto-repeats while a key is held are ignored.
- Conflict detection prevents assigning the same sequence twice.
- Vibration feedback whenever the keys pressed so far match the start of a configured hotkey.

### Self-Hide
- Lokker can hide its own launcher icon by disabling the `.LokkerLauncher` activity-alias.
- A Lokker hotkey must be configured first (lockout protection).
- When hidden, Lokker is reachable through its hotkey, the dialer code, or an "Open Lokker" remapper shortcut.

### KeyMapper / Shortcut Integration
- Lokker implements `ACTION_CREATE_SHORTCUT`, so KeyMapper and similar apps can list it under app shortcuts.
- The shortcut list shows "Open Lokker" plus every managed app, and returns a standard shortcut intent with the cached icon.
- Launching a shortcut runs PIN/biometric auth, then a temporary unhide, the launch, and a re-hide when the user leaves the app.
- Dynamic launcher shortcuts were removed in v1.1 because they exposed hidden apps in launcher search. No home-screen shortcuts are created.

### Secret Dialer Codes
- `*#*#565537#*#*` (LOKKER): open Lokker (with auth).
- `*#*#5655377469#*#*` (LOKKERSHOW): unhide **all** hidden packages on the device (no auth, emergency recovery).

### Management
- **Unhide all**: unhide all managed apps, saving a snapshot so they can be re-hidden.
- **Hide all**: re-hide the apps from that snapshot.
- **Unhide all system-wide** (dialer code only): unhides every hidden package on the device, not only those Lokker manages.

### Foreground Monitoring
- The accessibility service watches `TYPE_WINDOW_STATE_CHANGED`. System UI and IME windows are ignored, so the status bar or the keyboard do not trigger a re-hide.
- A `TaskStackListener` is registered via reflection on a best-effort basis. Its `onTaskRemoved` callback, together with a 3 s poll, drives the "unhide until closed" re-hide.
- The accessibility service is enabled automatically on startup (via `WRITE_SECURE_SETTINGS`).

### Settings
- **Security**: self-hide toggle, set/change PIN, biometric toggle.
- **Hotkeys**: Lokker hotkey and per-app hotkeys, including the per-app "Unhide until closed" toggle.
- **Management**: Unhide all / Hide all, each with a confirmation dialog.
- **About**: version and platform.
- Import/export of settings was removed from the UI.

UI strings are in English with a Russian translation (`values-ru`).

## Requirements

- Android 8.0+ (minSdk 26); targets and is tested on API 35 (LineageOS 22)
- Must be installed as a privileged system app (`/system_ext/priv-app/`) signed with the platform key
- Privileged permissions (whitelisted in `deployment/com.lokker.app.privapp-permissions.xml`): `MANAGE_USERS`, `CHANGE_COMPONENT_ENABLED_STATE`, `REMOVE_TASKS`, `REAL_GET_TASKS`, `WRITE_SECURE_SETTINGS`, `MANAGE_ACTIVITY_STACKS`
- Also uses `QUERY_ALL_PACKAGES`, `USE_BIOMETRIC`, `VIBRATE`, `RECEIVE_BOOT_COMPLETED`
- The accessibility service is required for hotkeys and re-hiding (Lokker enables it automatically)

## Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/Lokker-v{versionName}.apk` (`versionName` is set in `app/build.gradle`).

Note: the signing config points to a hard-coded keystore path (`D:/Programming/keystores/keystore3.jks`). Adjust it in `app/build.gradle` when building on another machine.

## Installation

### As part of the OS build (recommended)

1. Copy `deployment/` into the device tree (for example `packages/apps/Lokker/` or `vendor/.../Lokker/`).
2. Put the built APK next to `Android.bp` as `Lokker.apk`.
3. Add `PRODUCT_PACKAGES += Lokker` to your `device.mk`.

`Android.bp` uses `android_app_import` with `certificate: "platform"`, `privileged: true` and `system_ext_specific: true`, so the APK is re-signed with the platform key. It also installs the privapp-permissions XML into `system_ext/etc/permissions/`.

### Manually via ADB (root)

```bash
adb root
adb remount
adb push Lokker-v1.2a.apk /system/system_ext/priv-app/Lokker/Lokker.apk
adb push deployment/com.lokker.app.privapp-permissions.xml /system/system_ext/etc/permissions/
adb reboot
```

## Recovery

If you forgot the Lokker hotkey and Lokker is hidden from the launcher, dial `*#*#565537#*#*` or run:
```bash
adb shell pm enable com.lokker.app/.LokkerLauncher
```

If you forgot the PIN, clear Lokker's app data from Settings.

If you cleared Lokker's app data and need to unhide previously hidden apps, dial `*#*#5655377469#*#*`, or use ADB (root required):
```powershell
$packages = adb shell "pm list packages -u --user 0"
foreach ($line in $packages) {
    $pkg = ($line -replace 'package:', '').Trim()
    if ($pkg) {
        adb shell "pm unhide --user 0 $pkg"
    }
}
Write-Host "Done"
```
