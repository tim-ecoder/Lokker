# Lokker

Android system app for hiding applications at the OS level. Designed for devices running LineageOS / AOSP with system-level privileges.

## Features

### App Hiding
- Hides apps using `IPackageManager.setApplicationHiddenSettingAsUser()` — apps become invisible in launcher, Settings, and all PackageManager queries
- Hidden state persists across reboots (re-applied by BootReceiver and ScreenReceiver)
- App icons are cached before hiding for use in Lokker's UI and shortcuts
- Temporarily unhides apps for use and automatically re-hides when user navigates away

### Authentication
- **PIN**: 6-digit numeric code, hashed with PBKDF2-HMAC-SHA256, stored in EncryptedSharedPreferences
- **Biometric**: Fingerprint / Face Unlock via AndroidX BiometricPrompt
- **Lockout**: 30-second lockout after 5 failed PIN attempts
- **Input**: On-screen numpad, hardware keyboard, and IME keyboard support
- PIN displayed as `*` characters, empty slots as `_`

### Hotkeys
- **Lokker hotkey**: Hardware key sequence to open Lokker (even when hidden from launcher)
- **Per-app hotkeys**: Hardware key sequences to directly launch specific hidden apps
- Long-press detection (500ms threshold) and sequence buffer with 1.5s timeout
- Conflict detection prevents assigning the same sequence to multiple targets
- Vibration feedback on hotkey prefix match

### Self-Hide
- Lokker can hide itself from the launcher by disabling its launcher activity-alias
- Requires a Lokker hotkey to be configured first (safety mechanism)
- Accessible via hotkeys or dialer secret codes when hidden

### KeyMapper / Shortcut Integration
- Implements `ACTION_CREATE_SHORTCUT` for external apps like KeyMapper
- Shows list of hidden apps + "Open Lokker" option
- Returns standard Android shortcut intent with cached app icon
- Shortcut launch flow: shortcut trigger -> PIN/biometric auth -> unhide -> launch app

### Dynamic Shortcuts
- Long-press Lokker icon in launcher to see shortcuts for hidden apps
- Uses `lokker://launch/{package}` data URIs for reliable PendingIntent serialization
- Automatically rebuilt when app list changes

### Secret Dialer Codes
- `*#*#565537#*#*` — open Lokker (with auth)
- `*#*#5655377469#*#*` — unhide all apps system-wide (no auth)

### Management
- **Unhide all**: Temporarily unhide all managed apps (with snapshot for re-hiding)
- **Hide all**: Re-hide all previously unhidden apps from snapshot
- **Unhide all system-wide**: Unhides ALL hidden packages on device, not just Lokker-managed

### Foreground Monitoring
- Accessibility service tracks foreground app changes via `TYPE_WINDOW_STATE_CHANGED`
- Filters out system UI and keyboard IME events to avoid false triggers
- When user leaves a temporarily-unhidden app, it is re-hidden and removed from recents

### Screen-Off Protection
- On screen-on, re-applies hidden flag to all managed apps
- Safety net against hidden state drift during sleep

### Settings
- **Security**: Self-hide toggle, PIN set/change, biometric toggle
- **Hotkeys**: Configure Lokker hotkey and per-app hotkeys
- **Management**: Unhide all / Hide all with confirmation dialogs
- **About**: App version and platform info

## Requirements

- Android 8.0+ (API 26)
- Must be installed as a system app (`/system/system_ext/priv-app/`) with platform signature
- Required permissions: `MANAGE_USERS`, `CHANGE_COMPONENT_ENABLED_STATE`, `REMOVE_TASKS`, `QUERY_ALL_PACKAGES`
- Accessibility service must be enabled for hotkey detection and foreground monitoring

## Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/Lokker-v{version}.apk`

## Installation

```bash
adb root
adb remount
adb push Lokker-v1.0.apk /system/system_ext/priv-app/Lokker/Lokker.apk
adb reboot
```

## Recovery

If you forgot the Lokker hotkey and Lokker is hidden from launcher:
```bash
adb shell pm enable com.lokker.app/.LokkerLauncher
```

If you forgot the PIN, clear Lokker app data from Settings.

If you cleared Lokker app data and need to unhide previously hidden apps, dial `*#*#5655377469#*#*` or via ADB (root required):
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
