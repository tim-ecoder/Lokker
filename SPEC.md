# Lokker — System App Specification

**Package:** `com.lokker.app`
**Target:** LineageOS 22 · Android 15 · API 35
**Language:** Java
**Type:** Privileged system APK (`/system/priv-app`)

---

## 1. Feature Overview

### Core Features

- Hide any installed app from the launcher
- **Hide Lokker itself from the launcher** (preference toggle — when enabled, Lokker is only accessible via hotkey)
- Password/biometric gate on Lokker open
- Password gate on hidden app open
- **Rehide app automatically when user switches away** (Home, Back, Recents, or opening another app)
- Remove hidden apps from Recents screen
- Suppress notifications from hidden apps
- **GUI for adding/removing apps** to the hidden list (searchable app picker)
- Secret hotkey to reveal Lokker (the **only** entry point when Lokker is self-hidden)
- Secret hotkey to open a hidden app directly
- Dialer secret code entry (`*#5655#`) — optional fallback

### Non-Goals (Out of Scope)

- File encryption / SafeBox
- Gallery / file manager
- Cloud backup / sync
- Multi-user / Work Profile
- Remote wipe
- Network traffic isolation

---

## 2. Architecture

Single-module Java app. MVVM-lite pattern. No external dependencies beyond AndroidX and Security Crypto. Four runtime component types.

```
┌─────────────────────────────────────────────────────────────┐
│                      Lokker Process                         │
├──────────────┬──────────────┬──────────────┬───────────────┤
│   UI Layer   │  ViewModel   │   Services   │  BroadcastRx  │
│              │              │              │               │
│ MainActivity │LokkerViewModel│LokkerAccessSvc│ PackageMonitor│
│ AuthActivity │              │              │ BootReceiver  │
│ SetupActivity│              │              │ SecretCodeRx  │
│ HotkeySetup  │              │              │ ScreenReceiver│
├──────────────┴──────────────┴──────────────┴───────────────┤
│                      Repository                             │
│              AppRepository (single source of truth)         │
├────────────────────────────┬────────────────────────────────┤
│       LokkerDatabase       │    EncryptedPreferences        │
│  Room: HiddenApp, HotkeyMap│  auth hash, self-hide,        │
│                            │  pendingRehide set             │
└────────────────────────────┴────────────────────────────────┘
```

> **No NotificationListenerService needed.** `setApplicationHiddenSetting` prevents hidden apps from running entirely — they cannot post notifications. See Section 8.

### Component Responsibilities

| Component | Type | Responsibility |
|---|---|---|
| `LokkerAccessibilityService` | AccessibilityService | Detect foreground app changes via `TaskStackListener` + `TYPE_WINDOW_STATE_CHANGED`; **trigger rehide when hidden app loses focus**; remove from Recents; hotkey detection |
| `PackageMonitor` | BroadcastReceiver | `PACKAGE_REPLACED`/`ADDED` — re-apply hide state after updates (belt-and-suspenders; system maintains hidden state across updates) |
| `BootReceiver` | BroadcastReceiver | `BOOT_COMPLETED` — verify all hidden app states (belt-and-suspenders; state persists in packages.xml) |
| `ScreenReceiver` | BroadcastReceiver | `SCREEN_ON` — re-verify all hidden app states |
| `SecretCodeReceiver` | BroadcastReceiver | Dialer `*#5655#` → launch AuthActivity (optional — may not work on all ROMs) |
| `AppRepository` | Repository | Single interface to Room DB + EncryptedSharedPreferences + `setApplicationHiddenSetting` |

---

## 3. App Hiding Mechanism

Core hiding uses the `@hide` API `PackageManager.setApplicationHiddenSetting()` — the system-level hiding API designed for this purpose. This provides **complete invisibility**: the app disappears from Settings → Apps, `pm list packages`, all PackageManager queries, and cannot run any components.

> **Why not `setComponentEnabledSetting`?** That API only disables individual launcher activities — the app remains fully visible in Settings → Apps, storage stats, battery stats, `adb shell pm list packages`, and to other apps with `QUERY_ALL_PACKAGES`. It is wholly inadequate for true hiding.

### API comparison

| Aspect | `setComponentEnabledSetting` | `setApplicationHiddenSetting` |
|---|---|---|
| Hidden from launcher | Yes | Yes |
| Hidden from Settings → Apps | **NO** | **YES** |
| Hidden from `pm list packages` | **NO** | **YES** |
| Hidden from other apps' queries | **NO** | **YES** |
| Prevents app from running | **NO** (services/receivers still work) | **YES** |
| Notifications blocked inherently | **NO** (need NotificationListenerService) | **YES** (app can't run) |
| Persists across reboot | Yes | Yes |
| Persists across app update | Fragile (need PackageMonitor) | Yes (system maintains) |
| Data preserved | Yes | Yes |
| Permission | `CHANGE_COMPONENT_ENABLED_STATE` | `MANAGE_USERS` (signature) |

### Permission

`setApplicationHiddenSetting()` requires `MANAGE_USERS` (signature|privileged). Since Lokker is built with `certificate: "platform"` in the AOSP tree, this permission is granted automatically. Must also be whitelisted in `privapp-permissions-lokker.xml`.

### hideApp(packageName)

```java
// AppRepository.java

public void hideApp(String packageName) {
    // Cache app label BEFORE hiding (queries won't work after)
    String label = getAppLabel(packageName);

    // System-level hide — app disappears from Settings, pm list, all queries
    pm.setApplicationHiddenSetting(packageName, true);

    // Persist to Room for our own tracking (hotkeys, labels, etc.)
    HiddenApp record = new HiddenApp(
        packageName,
        label,
        null, // hotkeySequence
        System.currentTimeMillis()
    );
    db.hiddenAppDao().insert(record);
}
```

> **Note:** `setApplicationHiddenSetting` is a `@hide` API. In AOSP builds (Android.bp with `platform_apis: true`), it is callable directly. If building against SDK stubs, use reflection:
> ```java
> Method m = PackageManager.class.getMethod(
>     "setApplicationHiddenSetting", String.class, boolean.class);
> m.invoke(pm, packageName, true);
> ```

### unhideTemporarily(packageName)

Called before launching a hidden app through Lokker UI. Unhides the entire application; `LokkerAccessibilityService` will rehide when the app loses foreground.

```java
public boolean unhideTemporarily(String packageName) {
    HiddenApp record = db.hiddenAppDao().get(packageName);
    if (record == null) return false;

    // Unhide at system level — app becomes fully functional
    pm.setApplicationHiddenSetting(packageName, false);

    // Persist pending state to survive process death
    pendingRehide.add(packageName);
    persistPendingRehide();

    return true;
}
```

### unhideApp(packageName) — permanent unhide

Called when user removes an app from the hidden list via the GUI.

```java
public void unhideApp(String packageName) {
    // Unhide at system level
    pm.setApplicationHiddenSetting(packageName, false);

    db.hiddenAppDao().delete(packageName);
    pendingRehide.remove(packageName);
    persistPendingRehide();
}
```

### pendingRehide persistence

The set of temporarily-unhidden apps **must** survive process death. If Lokker is killed while an app is temporarily visible, the app must be re-hidden on next start.

```java
// AppRepository.java

private Set<String> pendingRehide = new HashSet<>();

private void persistPendingRehide() {
    encryptedPrefs.edit()
        .putStringSet("pending_rehide", pendingRehide)
        .apply();
}

private void loadPendingRehide() {
    pendingRehide = new HashSet<>(
        encryptedPrefs.getStringSet("pending_rehide", Collections.emptySet())
    );
}

/** Called from LokkerApp.onCreate() — re-hide any leaked apps */
public void recoverLeakedApps() {
    loadPendingRehide();
    for (String pkg : new HashSet<>(pendingRehide)) {
        pm.setApplicationHiddenSetting(pkg, true);
        pendingRehide.remove(pkg);
    }
    persistPendingRehide();
}
```

### Remaining visibility leaks

Even with `setApplicationHiddenSetting`, these cannot be prevented:

| Leak | Notes |
|---|---|
| `pm list packages -u` (ADB) | Shows hidden packages — requires ADB/root, outside threat model |
| `/data/app/` filesystem | APK still on disk — requires root |
| Usage stats from before hiding | Can clear via `UsageStatsManager` with system permission |
| Briefly visible during temp-unhide | Exposed only while user is actively using the app |

---

## 4. Rehide on App Switch

**This is the critical behavioral feature.** When a hidden app is temporarily unlocked and the user navigates away (Home, Back, Recents, or simply opening another app), the hidden app must immediately be re-hidden and removed from Recents.

### Detection — dual mechanism

Two independent foreground-detection systems ensure reliability:

#### Primary: TaskStackListener (AOSP @hide API)

More reliable than AccessibilityService for detecting task/foreground changes. Available to platform-signed apps.

```java
// LokkerAccessibilityService.java — registers on service start

private void registerTaskStackListener() {
    IActivityTaskManager atm = ActivityTaskManager.getService();
    atm.registerTaskStackListener(new TaskStackListener() {
        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo info) {
            String pkg = info.baseActivity != null
                ? info.baseActivity.getPackageName() : null;
            handleForegroundChange(pkg);
        }
    });
}
```

#### Secondary: AccessibilityService (fallback)

```java
// LokkerAccessibilityService.java

private String currentForegroundPkg = null;

@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

    CharSequence pkgSeq = event.getPackageName();
    if (pkgSeq == null) return;
    String pkg = pkgSeq.toString();

    if (!pkg.equals(currentForegroundPkg)) {
        handleForegroundChange(pkg);
    }
}
```

#### Shared rehide logic

```java
private void handleForegroundChange(String newPkg) {
    String prev = currentForegroundPkg;
    currentForegroundPkg = newPkg;

    if (prev != null && repo.isPendingRehide(prev)) {
        // Re-hide at system level — complete invisibility restored
        repo.rehideApp(prev);
        // Remove from Recents
        repo.removeFromRecents(prev);
    }
}
```

```java
// AppRepository.java
public void rehideApp(String packageName) {
    pm.setApplicationHiddenSetting(packageName, true);
    pendingRehide.remove(packageName);
    persistPendingRehide();
}
```

### Recents Removal (dual mechanism)

```java
// Mechanism 1: Flags at launch time
public void launchHiddenApp(String packageName) {
    // Must unhide before getLaunchIntentForPackage (hidden apps return null)
    unhideTemporarily(packageName);

    Intent intent = pm.getLaunchIntentForPackage(packageName);
    if (intent == null) return;
    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
    ctx.startActivity(intent);
}

// Mechanism 2: ActivityManager.removeTask() when app loses focus
public void removeFromRecents(String packageName) {
    ActivityManager am = ctx.getSystemService(ActivityManager.class);
    List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasks(100, 0);
    for (ActivityManager.RecentTaskInfo task : tasks) {
        if (task.baseIntent.getComponent() != null
                && packageName.equals(task.baseIntent.getComponent().getPackageName())) {
            am.removeTask(task.id);
        }
    }
}
```

### Rehide flow diagram

```
User opens hidden app via Lokker
  → Auth (biometric/PIN)
  → unhideTemporarily() — setApplicationHiddenSetting(pkg, false)
  → pendingRehide persisted to EncryptedPrefs
  → startActivity with EXCLUDE_FROM_RECENTS flags
  → user uses the app normally
  → user presses Home / Back / switches app
  → TaskStackListener OR AccessibilityService detects foreground change
  → previous package was in pendingRehide
  → setApplicationHiddenSetting(pkg, true) — full system-level re-hide
  → removeFromRecents() — clears from task list
  → pendingRehide cleared and persisted
  → done — app is completely invisible again
```

### Process death recovery

If Lokker is killed while an app is temporarily unhidden, `LokkerApp.onCreate()` calls `recoverLeakedApps()` which scans the persisted `pendingRehide` set and re-hides any leaked apps immediately (see Section 3).

---

## 5. Self-Hiding & Secret Entry

Lokker can hide itself from the launcher via a **preference toggle** in Settings. When self-hidden, the app icon disappears completely — the **only** way to open Lokker is through the configured hotkey (default: Vol↑ Vol↑ Vol↓). The dialer secret code (`*#5655#`) is available as an optional fallback but the hotkey is the primary and recommended entry point.

### Manifest aliases

Two Activity entries: the real `MainActivity` (never disabled, reachable via explicit intent) and a `LokkerLauncher` alias (disabled when self-hidden).

```xml
<!-- Real activity — always exists, never disabled -->
<activity
    android:name=".ui.MainActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:showWhenLocked="false">
    <intent-filter>
        <action android:name="com.lokker.app.OPEN"/>
        <category android:name="android.intent.category.DEFAULT"/>
    </intent-filter>
</activity>

<!-- Launcher alias — THIS is disabled when self-hidden -->
<activity-alias
    android:name=".LokkerLauncher"
    android:targetActivity=".ui.MainActivity"
    android:enabled="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
</activity-alias>
```

### setSelfHidden()

Self-hide is controlled by a preference toggle. Before enabling, the system **must** verify that a Lokker hotkey is configured — otherwise the user would lock themselves out.

```java
public void setSelfHidden(boolean hidden) {
    if (hidden) {
        // Guard: refuse to self-hide if no hotkey is configured
        HotkeyConfig config = repo.getHotkeyConfig();
        if (config.getLokkerHotkey() == null || config.getLokkerHotkey().isEmpty()) {
            throw new IllegalStateException("Cannot self-hide without a configured hotkey");
        }
    }

    ComponentName alias = new ComponentName(ctx, "com.lokker.app.LokkerLauncher");
    int state = hidden
        ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

    ctx.getPackageManager().setComponentEnabledSetting(
        alias, state, PackageManager.DONT_KILL_APP
    );
    prefs.edit().putBoolean("self_hidden", hidden).apply();
}
```

### Auto-unhide on reinstall

When Lokker itself is reinstalled (or updated), it **must** reset to visible in the launcher. On reinstall, `setComponentEnabledSetting` resets to the manifest default (enabled), so the alias is already restored. `PackageMonitor` detects `PACKAGE_REPLACED` for Lokker's own package and clears the `self_hidden` pref to keep state consistent:

```java
// In PackageMonitor.onReceive():
if (packageName.equals(ctx.getPackageName())) {
    // Lokker was reinstalled/updated — ensure it's visible in launcher
    prefs.edit().putBoolean("self_hidden", false).apply();
    return; // don't process as a hidden app
}
```

This prevents a scenario where the pref says "self-hidden" but the alias was already reset by the system, and ensures the user can always find Lokker in the launcher after reinstalling.

### Secret entry via dialer

Register `*#LOKK#` (`*#5655#`) as a secret code:

```xml
<receiver android:name=".receiver.SecretCodeReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.SECRET_CODE"/>
        <data android:scheme="android_secret_code" android:host="5655"/>
    </intent-filter>
</receiver>
```

---

## 6. Authentication Layer

Two-layer auth: **password hash** (PIN/password for quick unlock) + **BiometricPrompt** (fingerprint/face).

### Password storage

Password stored as PBKDF2-HMAC-SHA256 hash in EncryptedSharedPreferences. (PBKDF2 chosen over Argon2 to avoid native library dependency in AOSP build.)

```java
// AuthManager.java

public void setPassword(String password) {
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);

    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 310000, 256);
    byte[] hash = factory.generateSecret(spec).getEncoded();

    String encoded = Base64.encodeToString(salt, Base64.NO_WRAP) + ":"
                   + Base64.encodeToString(hash, Base64.NO_WRAP);
    encryptedPrefs.edit().putString("pw_hash", encoded).apply();
}

public boolean verifyPassword(String input) {
    String stored = encryptedPrefs.getString("pw_hash", null);
    if (stored == null) return false;

    String[] parts = stored.split(":");
    byte[] salt = Base64.decode(parts[0], Base64.NO_WRAP);
    byte[] storedHash = Base64.decode(parts[1], Base64.NO_WRAP);

    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    KeySpec spec = new PBEKeySpec(input.toCharArray(), salt, 310000, 256);
    byte[] inputHash = factory.generateSecret(spec).getEncoded();

    return MessageDigest.isEqual(storedHash, inputHash);
}
```

### BiometricPrompt

```java
public void showBiometric(FragmentActivity activity, Runnable onSuccess, Runnable onFail) {
    BiometricPrompt prompt = new BiometricPrompt(activity,
        ContextCompat.getMainExecutor(activity),
        new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                onSuccess.run();
            }
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                onFail.run();
            }
            @Override
            public void onAuthenticationFailed() {
                onFail.run();
            }
        }
    );

    BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
        .setTitle("Lokker")
        .setSubtitle("Authenticate to continue")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
            | BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build();

    prompt.authenticate(info);
}
```

### AuthActivity Flow

1. **AuthActivity launched** with intent extra `target_package` (if opening a hidden app) or null (opening Lokker)
2. **BiometricPrompt shown** — fingerprint/face first
3. Biometric fails/unavailable → **PIN entry** shown
4. Auth success + `target_package` set → `unhideTemporarily()` → `launchHiddenApp()` → `finish()`
5. Auth success + no target → `startActivity(MainActivity)` → `finish()`
6. Auth fail 5× → **30-second lockout**, counter in EncryptedPrefs

> **Security:** AuthActivity must have `excludeFromRecents="true"` and `showWhenLocked="false"`.

---

## 7. GUI — App Management Screen

The main UI is `MainActivity`, which shows two tabs/sections:

### 7.1 Hidden Apps List (default view)

- **Search bar** at the top — filters the hidden apps list by app name in real time
- RecyclerView showing all currently hidden apps
- Each row shows: **app icon** | **app name** | **package name** (dimmed) | **unhide button**
- Tapping a row launches the app (auth → unhide → launch → rehide on switch)
- Long-press on a row opens options: Unhide, Set Hotkey, Create Shortcut
- **Floating Action Button (FAB)** → opens the App Picker to add apps
- Empty state: centered message "No hidden apps. Tap + to hide an app." (search bar hidden when list is empty)

### 7.2 App Picker Dialog (add apps to hidden list)

Triggered by the FAB. Full-screen dialog or bottom sheet:

- **Search bar** at the top — filters by app name or package name
- RecyclerView of all installed user apps (excludes system apps by default)
- Toggle: "Show system apps" — includes system apps in the list
- Each row: **app icon** | **app name** | **package name** | **checkbox**
- Multi-select: user can check multiple apps at once
- **"Hide Selected" button** at the bottom — calls `hideApp()` for each selected app
- Apps already hidden are shown with a "Hidden" badge and are not selectable

### 7.3 Settings Section (accessible via toolbar menu or gear icon)

- **Self-hide toggle** — hide/show Lokker in the launcher. When enabled, Lokker icon disappears; only accessible via hotkey. Shows confirmation dialog: "Lokker will be hidden from the launcher. You can only open it with the hotkey (Vol↑ Vol↑ Vol↓). Continue?"
- **Change password** — re-enter current, set new
- **Hotkey configuration** — opens `HotkeySetupActivity` (must be configured before self-hide can be enabled)
- **Hide all / Unhide all toggle** — single preference that toggles between two states:
  - When apps are hidden: shows **"Unhide all apps"**. Confirmation: "This will unhide all N hidden apps. Pinned shortcuts will remain. Continue?" Calls `unhideAll()`, which snapshots the list then unhides.
  - When snapshot exists (apps were just unhidden): shows **"Hide all apps"**. Confirmation: "Re-hide all N previously hidden apps?" Calls `rehideAll()`, which re-hides from the snapshot.
  - Hidden when no apps are hidden and no snapshot exists.
- **Biometric toggle** — enable/disable biometric auth
- **About** — version info

### 7.4 UI Layout Summary

```
┌──────────────────────────────────┐
│ Toolbar: "Lokker"     [⚙ gear]  │
├──────────────────────────────────┤
│ 🔍 Filter apps...               │
├──────────────────────────────────┤
│                                  │
│  ┌──────────────────────────┐    │
│  │ 📱 WhatsApp              │    │
│  │    com.whatsapp     [⊘]  │    │
│  ├──────────────────────────┤    │
│  │ 📱 Signal                │    │
│  │    org.signal.app   [⊘]  │    │
│  ├──────────────────────────┤    │
│  │ 📱 Gallery               │    │
│  │    com.google...    [⊘]  │    │
│  └──────────────────────────┘    │
│                                  │
│                          [+ FAB] │
└──────────────────────────────────┘

🔍 = search/filter field (filters by app name)
[⊘] = unhide button
[+ FAB] = open app picker
Tap row = launch app (auth → unhide → launch → rehide)
```

### App Picker Dialog

```
┌──────────────────────────────────┐
│ [← Back]  Hide Apps             │
├──────────────────────────────────┤
│ 🔍 Search apps...               │
│ ☐ Show system apps              │
├──────────────────────────────────┤
│  ☐  📱 Calculator               │
│  ☐  📱 Camera                   │
│  ☑  📱 Chrome                   │
│  ──  📱 WhatsApp  [Hidden]      │
│  ☐  📱 YouTube                  │
│  ...                             │
├──────────────────────────────────┤
│     [ Hide 1 Selected App ]     │
└──────────────────────────────────┘
```

### 7.5 Pinned Shortcuts for Hidden Apps

Lokker can create **pinned home-screen shortcuts** for hidden apps. Each shortcut routes through `AuthActivity`, so tapping it triggers auth → unhide → launch → rehide-on-switch. This also enables key remappers (KeyMapper, etc.) to trigger hidden apps without needing a framework patch — the remapper simply targets the pinned shortcut.

#### Creating a shortcut

Offered via the long-press menu on any hidden app row in `MainActivity`.

```java
// AppRepository.java

public void createPinnedShortcut(String packageName) {
    HiddenApp record = db.hiddenAppDao().get(packageName);
    if (record == null) return;

    ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
    if (!sm.isRequestPinShortcutSupported()) return;

    // Build intent that routes through AuthActivity
    Intent target = new Intent(ctx, AuthActivity.class);
    target.setAction("com.lokker.app.LAUNCH_HIDDEN");
    target.putExtra("target_package", packageName);

    // Use cached icon from before hiding
    Icon icon = loadCachedIcon(packageName);  // see below
    if (icon == null) {
        icon = Icon.createWithResource(ctx, R.drawable.ic_launcher);
    }

    ShortcutInfo shortcut = new ShortcutInfo.Builder(ctx, "lokker_" + packageName)
        .setShortLabel(record.appLabel)
        .setIcon(icon)
        .setIntent(target)
        .build();

    sm.requestPinShortcut(shortcut, null);
}
```

#### Caching app icons before hiding

App icons must be cached **before** `setApplicationHiddenSetting(true)` because hidden apps are invisible to `PackageManager` queries. Icons are stored as PNGs in Lokker's internal storage.

```java
// AppRepository.java

private void cacheAppIcon(String packageName) {
    try {
        Drawable icon = pm.getApplicationIcon(packageName);
        Bitmap bmp = drawableToBitmap(icon);
        File file = new File(ctx.getFilesDir(), "icons/" + packageName + ".png");
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    } catch (PackageManager.NameNotFoundException ignored) {}
}

private Icon loadCachedIcon(String packageName) {
    File file = new File(ctx.getFilesDir(), "icons/" + packageName + ".png");
    if (!file.exists()) return null;
    return Icon.createWithBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
}

private Bitmap drawableToBitmap(Drawable drawable) {
    if (drawable instanceof BitmapDrawable) {
        return ((BitmapDrawable) drawable).getBitmap();
    }
    Bitmap bmp = Bitmap.createBitmap(
        drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(),
        Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
    drawable.draw(canvas);
    return bmp;
}
```

Update `hideApp()` to cache the icon:

```java
public void hideApp(String packageName) {
    String label = getAppLabel(packageName);
    cacheAppIcon(packageName);  // cache icon BEFORE hiding

    pm.setApplicationHiddenSetting(packageName, true);

    HiddenApp record = new HiddenApp(packageName, label, null, System.currentTimeMillis());
    db.hiddenAppDao().insert(record);
}
```

#### Removing stale shortcuts

When a user permanently unhides an app, remove any corresponding pinned shortcut:

```java
// AppRepository.java

public void unhideApp(String packageName) {
    pm.setApplicationHiddenSetting(packageName, false);
    db.hiddenAppDao().delete(packageName);
    pendingRehide.remove(packageName);
    persistPendingRehide();

    // Remove pinned shortcut
    ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
    sm.disableShortcuts(List.of("lokker_" + packageName));

    // Clean up cached icon
    new File(ctx.getFilesDir(), "icons/" + packageName + ".png").delete();
}

public void unhideAll() {
    List<HiddenApp> allApps = db.hiddenAppDao().getAllSync();
    if (allApps.isEmpty()) return;

    // Snapshot the list so we can re-hide later
    saveUnhideAllSnapshot(allApps);

    for (HiddenApp app : allApps) {
        pm.setApplicationHiddenSetting(app.packageName, false);
        // Keep pinned shortcuts intact — user may re-hide via toggle
        // Keep cached icons — needed if user re-hides
    }

    // Bulk cleanup
    db.hiddenAppDao().deleteAll();
    pendingRehide.clear();
    persistPendingRehide();
}

/**
 * Re-hide all apps from the last unhideAll() snapshot.
 * Only available while the snapshot exists.
 */
public void rehideAll() {
    List<HiddenApp> snapshot = loadUnhideAllSnapshot();
    if (snapshot == null || snapshot.isEmpty()) return;

    for (HiddenApp app : snapshot) {
        // Verify app is still installed before re-hiding
        try {
            pm.getPackageInfo(app.packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            continue; // app was uninstalled, skip
        }

        pm.setApplicationHiddenSetting(app.packageName, true);
        db.hiddenAppDao().insert(app);
    }

    clearUnhideAllSnapshot();
}

// --- Snapshot persistence (EncryptedSharedPreferences) ---

private void saveUnhideAllSnapshot(List<HiddenApp> apps) {
    JSONArray arr = new JSONArray();
    for (HiddenApp app : apps) {
        JSONObject obj = new JSONObject();
        obj.put("packageName", app.packageName);
        obj.put("appLabel", app.appLabel);
        obj.put("hiddenAt", app.hiddenAt);
        arr.put(obj);
    }
    prefs.edit().putString("unhide_all_snapshot", arr.toString()).apply();
}

private List<HiddenApp> loadUnhideAllSnapshot() {
    String json = prefs.getString("unhide_all_snapshot", null);
    if (json == null) return null;

    List<HiddenApp> result = new ArrayList<>();
    JSONArray arr = new JSONArray(json);
    for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        result.add(new HiddenApp(
            obj.getString("packageName"),
            obj.getString("appLabel"),
            null,  // hotkey not preserved in snapshot
            obj.getLong("hiddenAt")
        ));
    }
    return result;
}

private void clearUnhideAllSnapshot() {
    prefs.edit().remove("unhide_all_snapshot").apply();
}

public boolean hasUnhideAllSnapshot() {
    return prefs.getString("unhide_all_snapshot", null) != null;
}
```

#### AuthActivity handling

`AuthActivity` already supports `target_package` extras (Section 6). The shortcut intent uses action `com.lokker.app.LAUNCH_HIDDEN` to distinguish from other entry points, but the auth + launch flow is identical.

#### Manifest addition

```xml
<!-- AuthActivity needs to accept the shortcut action -->
<activity android:name=".ui.AuthActivity"
    android:excludeFromRecents="true"
    android:showWhenLocked="true">
    <intent-filter>
        <action android:name="com.lokker.app.LAUNCH_HIDDEN"/>
        <category android:name="android.intent.category.DEFAULT"/>
    </intent-filter>
</activity>
```

#### Key remapper integration

The user configures their key remapper to launch the pinned shortcut (or the explicit intent `com.lokker.app.LAUNCH_HIDDEN` with extra `target_package`). No proxy activity, no framework patch. The remapper triggers Lokker's standard auth flow.

#### UI in hidden apps list

Add "Create shortcut" to the long-press context menu:

```
┌──────────────────────────────────┐
│  WhatsApp                        │
│  com.whatsapp                    │
├──────────────────────────────────┤
│  ▶ Launch                        │
│  🔗 Create shortcut              │
│  ⌨ Set hotkey                    │
│  ⊘ Unhide                        │
└──────────────────────────────────┘
```

---

## 8. Notification Suppression

**Not needed.** With `setApplicationHiddenSetting`, hidden apps cannot run any components — no services, no receivers, no alarms. They **cannot post notifications**. The system blocks all component starts for hidden packages at the `PackageManagerService` level.

> **Note:** During the brief temp-unhide window (while user is actively using a hidden app), the app CAN post notifications. These are acceptable because the user is actively using the app. When the app is re-hidden via `setApplicationHiddenSetting(pkg, true)`, the app is force-stopped and any pending notifications are cleared by the system.

`LokkerNotificationListener` is removed from the architecture. No `NotificationListenerService` permission is needed.

---

## 9. Hotkey System

Hotkey detection runs inside `LokkerAccessibilityService` via `onKeyEvent()`:

```java
// LokkerAccessibilityService.java

private List<Integer> hotkeySequence = new ArrayList<>();
private long lastKeyTime = 0L;
private static final long SEQ_TIMEOUT = 1500L;

@Override
protected boolean onKeyEvent(KeyEvent event) {
    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

    long now = SystemClock.elapsedRealtime();
    if (now - lastKeyTime > SEQ_TIMEOUT) hotkeySequence.clear();
    lastKeyTime = now;

    hotkeySequence.add(event.getKeyCode());

    HotkeyConfig config = repo.getHotkeyConfig();

    // Lokker open hotkey (e.g., Vol↑ Vol↑ Vol↓)
    if (config.getLokkerHotkey() != null && endsWith(hotkeySequence, config.getLokkerHotkey())) {
        hotkeySequence.clear();
        launchAuth(null);
        return true;
    }

    // Per-app hotkeys
    for (Map.Entry<String, List<Integer>> entry : config.getAppHotkeys().entrySet()) {
        if (endsWith(hotkeySequence, entry.getValue())) {
            hotkeySequence.clear();
            launchAuth(entry.getKey());
            return true;
        }
    }

    return false;
}

private void launchAuth(String targetPackage) {
    Intent intent = new Intent(this, AuthActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    if (targetPackage != null) {
        intent.putExtra("target_package", targetPackage);
    }
    startActivity(intent);
}
```

| Hotkey type | Default | Configurable | Keys supported |
|---|---|---|---|
| Open Lokker | Vol↑ Vol↑ Vol↓ | Yes | Volume, Power (long), Camera |
| Open hidden app N | None | Yes, per-app | Same |
| Dialer code | `*#5655#` | Yes | USSD-style `*#XXXX#` |

> **Caveat:** Volume keys are the safest. Power long-press triggers system power menu on Android 12+. `canRequestFilterKeyEvents="true"` required in accessibility service config.

---

## 10. Data Layer

### Room Database

```java
// HiddenApp.java — Entity
// NOTE: No disabledComponents field needed — setApplicationHiddenSetting
// hides the entire application at the system level.
@Entity(tableName = "hidden_apps")
public class HiddenApp {
    @PrimaryKey @NonNull
    public String packageName;
    public String appLabel;
    @TypeConverters(Converters.class)
    public List<Integer> hotkeySequence; // nullable, per-app hotkey
    public long hiddenAt;
}

// HotkeyMap.java — Entity
@Entity(tableName = "hotkey_map")
public class HotkeyMap {
    @PrimaryKey
    public int id = 1;
    @TypeConverters(Converters.class)
    public List<Integer> lokkerHotkey;
}

// HiddenAppDao.java
@Dao
public interface HiddenAppDao {
    @Query("SELECT * FROM hidden_apps ORDER BY hiddenAt DESC")
    LiveData<List<HiddenApp>> getAllLive();

    @Query("SELECT * FROM hidden_apps")
    List<HiddenApp> getAll();

    @Query("SELECT * FROM hidden_apps WHERE packageName = :pkg")
    HiddenApp get(String pkg);

    @Query("SELECT COUNT(*) > 0 FROM hidden_apps WHERE packageName = :pkg")
    boolean isHidden(String pkg);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(HiddenApp app);

    @Query("DELETE FROM hidden_apps WHERE packageName = :pkg")
    void delete(String pkg);

    @Query("SELECT * FROM hidden_apps")
    List<HiddenApp> getAllSync();

    @Query("DELETE FROM hidden_apps")
    void deleteAll();
}
```

### EncryptedSharedPreferences

```java
// LokkerPrefs.java
public class LokkerPrefs {
    private static SharedPreferences prefs;

    public static SharedPreferences get(Context ctx) {
        if (prefs == null) {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

            prefs = EncryptedSharedPreferences.create(
                ctx,
                "lokker_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        }
        return prefs;
    }

    // Keys:
    // "pw_hash"          — PBKDF2 hash of user password
    // "self_hidden"      — boolean
    // "fail_count"       — int (auth failure counter)
    // "lockout_until"    — long (timestamp)
    // "pending_rehide"    — StringSet (packages temporarily unhidden)
    // "unhide_all_snapshot" — JSON array of previously hidden apps (for re-hide all)
    // NOTE: "notif_auto_granted" removed — NLS no longer needed
}
```

---

## 11. Build System (AOSP/LineageOS)

### Android.bp

```
android_app {
    name: "Lokker",
    srcs: ["src/**/*.java"],
    privileged: true,
    certificate: "platform",
    platform_apis: true,
    sdk_version: "",
    min_sdk_version: "35",
    static_libs: [
        "androidx.core_core",
        "androidx.room_room-runtime",
        "androidx.security_security-crypto",
        "androidx.biometric_biometric",
        "androidx.lifecycle_lifecycle-runtime",
        "androidx.lifecycle_lifecycle-viewmodel",
        "androidx.appcompat_appcompat",
        "androidx.recyclerview_recyclerview",
        "com.google.android.material_material",
    ],
    plugins: ["androidx.room_room-compiler-plugin"],
    optimize: { enabled: false },
    resource_dirs: ["res"],
}
```

### privapp-permissions-lokker.xml

```xml
<permissions>
    <privapp-permissions package="com.lokker.app">
        <permission name="android.permission.MANAGE_USERS"/>
        <permission name="android.permission.CHANGE_COMPONENT_ENABLED_STATE"/>
        <permission name="android.permission.REMOVE_TASKS"/>
        <permission name="android.permission.GET_TASKS"/>
        <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
        <permission name="android.permission.REAL_GET_TASKS"/>
        <permission name="android.permission.MANAGE_ACTIVITY_STACKS"/>
    </privapp-permissions>
</permissions>
```

> **Note:** `MANAGE_USERS` is the key permission for `setApplicationHiddenSetting()`. `CHANGE_COMPONENT_ENABLED_STATE` is retained only for Lokker's own self-hiding via activity-alias (Section 5).

### device.mk integration

```makefile
PRODUCT_PACKAGES += Lokker

PRODUCT_COPY_FILES += \
    packages/apps/Lokker/privapp-permissions-lokker.xml:\
    $(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-lokker.xml
```

---

## 12. Project Structure

```
packages/apps/Lokker/
├── Android.bp
├── AndroidManifest.xml
├── privapp-permissions-lokker.xml
├── SPEC.md
├── res/
│   ├── drawable/
│   │   └── ic_launcher.xml
│   ├── layout/
│   │   ├── activity_main.xml          # search bar + hidden apps list + FAB
│   │   ├── activity_auth.xml          # PIN entry screen
│   │   ├── activity_setup.xml         # first-run password setup
│   │   ├── activity_hotkey_setup.xml   # hotkey recording
│   │   ├── dialog_app_picker.xml      # full-screen app picker
│   │   ├── item_hidden_app.xml        # row in hidden apps list
│   │   └── item_picker_app.xml        # row in app picker
│   ├── values/
│   │   ├── strings.xml
│   │   ├── styles.xml
│   │   └── colors.xml
│   └── xml/
│       └── accessibility_service_config.xml
└── src/
    └── com/lokker/app/
        ├── LokkerApp.java                 # Application subclass
        ├── receiver/
        │   ├── BootReceiver.java           # BOOT_COMPLETED → re-apply hidden state
        │   ├── PackageMonitor.java         # PACKAGE_REPLACED → re-hide
        │   ├── SecretCodeReceiver.java     # dialer *#5655# → open auth
        │   └── ScreenReceiver.java         # SCREEN_ON → re-verify states
        ├── service/
        │   └── LokkerAccessibilityService.java   # foreground monitor + rehide + hotkey + TaskStackListener
        ├── ui/
        │   ├── MainActivity.java           # hidden apps list, FAB, settings menu
        │   ├── AuthActivity.java           # PIN + biometric gate
        │   ├── SetupActivity.java          # first-run password setup
        │   ├── HotkeySetupActivity.java    # record key sequences
        │   ├── AppPickerDialog.java        # searchable app picker (add to hidden)
        │   ├── LokkerViewModel.java        # LiveData for hidden apps list + search filter
        │   └── adapter/
        │       ├── HiddenAppsAdapter.java  # RecyclerView adapter for main list
        │       └── AppPickerAdapter.java   # RecyclerView adapter for picker
        ├── domain/
        │   ├── AppRepository.java          # single source of truth
        │   ├── AuthManager.java            # password + biometric
        │   └── HotkeyManager.java          # sequence matching
        └── data/
            ├── db/
            │   ├── LokkerDatabase.java     # Room database
            │   ├── HiddenApp.java          # entity
            │   ├── HotkeyMap.java          # entity
            │   ├── HiddenAppDao.java       # DAO
            │   └── Converters.java         # TypeConverters (JSON lists)
            └── LokkerPrefs.java            # EncryptedSharedPreferences
```

---

## 13. Behavior Matrix

| Scenario | Expected Behavior | Mechanism |
|---|---|---|
| User opens launcher | Hidden app icon not visible | `setApplicationHiddenSetting(pkg, true)` |
| User opens Settings → Apps | **Hidden app NOT listed** | `setApplicationHiddenSetting` — complete system-level hiding |
| User runs `pm list packages` | **Hidden app NOT listed** | Same (hidden from all PM queries) |
| User enables self-hide in preferences | Lokker icon disappears from launcher | `setComponentEnabledSetting` disables `LokkerLauncher` alias |
| User tries to find Lokker (self-hidden) | Lokker not visible anywhere — launcher, Recents, Settings app list | Alias disabled + `excludeFromRecents` |
| User presses hotkey (only way in) | Auth prompt → Lokker UI opens | `onKeyEvent` intercept → `AuthActivity` → `MainActivity` |
| User launches hidden app via Lokker | Auth → app launches normally | `setApplicationHiddenSetting(false)` → `startActivity` → rehide on switch |
| **User switches away from hidden app** | **App re-hides immediately, removed from Recents** | **`TaskStackListener` + `TYPE_WINDOW_STATE_CHANGED` → `setApplicationHiddenSetting(true)` + `removeTask()`** |
| User presses Home in hidden app | App re-hides, removed from Recents | Same as above |
| User presses Back out of hidden app | App re-hides, removed from Recents | Same as above |
| User opens Recents while in hidden app | App re-hides, not visible in Recents | Same + `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` |
| Hidden app tries to send notification | **Impossible** — app cannot run while hidden | `setApplicationHiddenSetting` blocks all component starts |
| Hidden app updated via system | Stays hidden after update | System maintains hidden state; `PackageMonitor` as safety check |
| Lokker reinstalled/updated | Lokker visible in launcher, `self_hidden` pref cleared | `PackageMonitor` detects own package → clears `self_hidden`; alias resets to manifest default (enabled) |
| Device reboots | All hidden apps remain hidden | `packages.xml` persists; `BootReceiver` verifies |
| Lokker process killed during temp-unhide | **App re-hidden on next start** | `pendingRehide` persisted to EncryptedPrefs; `recoverLeakedApps()` on `Application.onCreate()` |
| User adds app via GUI picker | App hidden, appears in hidden list | `setApplicationHiddenSetting(true)` + Room insert + LiveData update |
| User removes app via GUI | App unhidden, disappears from list | `setApplicationHiddenSetting(false)` + Room delete + LiveData update |
| User creates pinned shortcut | Shortcut appears on home screen with app's icon/label | `ShortcutManager.requestPinShortcut()` → cached icon + `AuthActivity` intent |
| User taps pinned shortcut | Auth → unhide → launch → rehide on switch | Shortcut intent → `AuthActivity` → standard launch flow |
| Key remapper triggers shortcut | Same as tapping shortcut — auth → launch → rehide | Remapper targets `com.lokker.app.LAUNCH_HIDDEN` intent |
| User unhides app permanently | Pinned shortcut disabled, icon cache cleaned | `ShortcutManager.disableShortcuts()` + file delete |
| User taps "Unhide all apps" toggle in settings | All hidden apps restored, list cleared, shortcuts unchanged; snapshot saved; toggle flips to "Hide all apps" | `unhideAll()` — snapshots list to prefs, bulk unhide + `deleteAll()` + clear `pendingRehide`; shortcuts left intact |
| User taps "Hide all apps" toggle in settings | All previously hidden apps re-hidden from snapshot; toggle flips back to "Unhide all apps" | `rehideAll()` — reads snapshot, re-hides each (skips uninstalled), re-inserts to Room, clears snapshot |

---

## 14. Full Permissions Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.lokker.app">

    <!-- Runtime permissions -->
    <uses-permission android:name="android.permission.USE_BIOMETRIC"/>
    <uses-permission android:name="android.permission.USE_FINGERPRINT"/>
    <uses-permission android:name="android.permission.VIBRATE"/>
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>

    <!-- Privileged permissions (whitelisted in privapp XML) -->
    <uses-permission android:name="android.permission.MANAGE_USERS"/>
    <uses-permission android:name="android.permission.CHANGE_COMPONENT_ENABLED_STATE"/>
    <uses-permission android:name="android.permission.REMOVE_TASKS"/>
    <uses-permission android:name="android.permission.GET_TASKS"/>
    <uses-permission android:name="android.permission.REAL_GET_TASKS"/>
    <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>
    <uses-permission android:name="android.permission.MANAGE_ACTIVITY_STACKS"/>
</manifest>
```

> **Permission notes:**
> - `MANAGE_USERS` — required for `setApplicationHiddenSetting()` (the core hiding API)
> - `CHANGE_COMPONENT_ENABLED_STATE` — only used for Lokker's own self-hiding (activity-alias)
> - No `NotificationListenerService` declaration needed — hidden apps cannot post notifications

---

## 15. Development Phases

### Phase 1 — Foundation (~3 days)
- AOSP module scaffold: `Android.bp` (with `platform_apis: true`), manifest, build integration
- `privapp-permissions` XML (including `MANAGE_USERS`) + `device.mk` wiring
- `LokkerDatabase` (Room) + `HiddenApp` entity (no `disabledComponents`) + DAO
- `LokkerPrefs` (EncryptedSharedPreferences) with `pendingRehide` persistence
- `AppRepository` skeleton with `setApplicationHiddenSetting` wiring
- `LokkerApp.onCreate()` → `recoverLeakedApps()` (re-hide any leaked apps)
- Verify `setApplicationHiddenSetting` works as priv-app on device
- Verify hidden app disappears from Settings → Apps

### Phase 2 — Core Hiding (~2 days)
- `hideApp()` / `unhideApp()` / `unhideTemporarily()` / `rehideApp()` full implementation
- `BootReceiver` — verify all hidden app states on boot (belt-and-suspenders)
- `PackageMonitor` — `PACKAGE_REPLACED` / `PACKAGE_ADDED` safety check
- Self-hiding via activity-alias `setComponentEnabledSetting` (Lokker's own icon only)
- `SecretCodeReceiver` (dialer code entry — optional, may not work on all ROMs)

### Phase 3 — GUI (~3 days)
- `MainActivity` layout: RecyclerView + FAB + toolbar menu
- `HiddenAppsAdapter` with app icon, name, package, unhide button
- `AppPickerDialog`: searchable list of all installed apps
- `AppPickerAdapter` with checkbox multi-select
- "Show system apps" toggle in picker
- `LokkerViewModel` with LiveData from Room DAO + `MutableLiveData<String>` search query filter via `Transformations.switchMap`
- Empty state for no hidden apps

### Phase 4 — Auth Layer (~2 days)
- `AuthManager`: PBKDF2 password hashing
- `BiometricPrompt` integration
- `AuthActivity`: PIN entry UI + biometric fallback
- `SetupActivity`: first-run password setup
- Lockout logic: 5 failures → 30s timeout
- Gate `MainActivity` behind auth on launch

### Phase 5 — Accessibility + Rehide (~3 days)
- `LokkerAccessibilityService` scaffold + manifest declaration
- `TaskStackListener` registration (primary foreground detection)
- `TYPE_WINDOW_STATE_CHANGED` (secondary/fallback foreground detection)
- `pendingRehide` set: persisted to EncryptedPrefs, track temporarily-unlocked apps
- **Rehide on app switch** — `setApplicationHiddenSetting(true)` + `removeTask()`
- `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` on launch
- Auto-enable Accessibility via `WRITE_SECURE_SETTINGS`

### Phase 6 — Hotkeys (~2 days)
- `HotkeyManager`: sequence buffer + timeout logic
- `onKeyEvent` in AccessibilityService
- `HotkeySetupActivity`: record and save sequences
- Per-app hotkey support

### Phase 7 — Polish & Testing (~3 days)
- Verify: hidden apps invisible in Settings → Apps, `pm list packages`, battery stats
- Edge cases: apps that re-enable themselves
- Process death recovery: kill Lokker during temp-unhide, verify rehide on restart
- Test on LineageOS 22 device
- Dark theme, edge-to-edge (Android 15 mandatory)
- No-icon mode: verify Lokker invisible in all launchers
- Stress test: hide/unhide 20+ apps
- `ScreenReceiver`: on unlock re-check all hidden state integrity

**Total estimate: ~18 developer-days for solo dev**

---

## Shelved — Framework Patch: Intercept ActivityNotFoundException

**Status:** Shelved for future consideration
**Rationale:** Enables seamless integration with key remappers (e.g., KeyMapper) without requiring users to configure a proxy activity. The remapper can target the real hidden app component directly — Lokker intercepts the failed launch and handles auth + unhide automatically.

### Problem

When an app is hidden via `setApplicationHiddenSetting`, its components are invisible to `PackageManager.resolveActivity()`. Any external tool (key remapper, shortcut launcher) that tries to launch the hidden app gets `ActivityNotFoundException`. As a system app, Lokker **cannot** intercept this — `IActivityController.activityStarting()` only fires for successfully resolved activities, and the exception is thrown client-side in the caller's process.

### Solution: LineageOS framework patch

Add ~15 lines to `ActivityStarter.java` in the LineageOS source tree. When intent resolution fails and the target package is installed-but-hidden, broadcast the failed intent so Lokker can intercept it.

#### Framework side (packages/services/core)

**File:** `frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java`

In `executeRequest()`, where `START_INTENT_NOT_RESOLVED` is returned after `aInfo == null`:

```java
// After: if (aInfo == null) { ... }
// Check if the target is a hidden (not uninstalled) package
if (aInfo == null && intent.getComponent() != null) {
    String targetPkg = intent.getComponent().getPackageName();
    try {
        PackageManager pm = mService.mContext.getPackageManager();
        // getApplicationInfo with MATCH_HIDDEN flag — only works for hidden apps
        ApplicationInfo ai = pm.getApplicationInfo(targetPkg,
            PackageManager.MATCH_UNINSTALLED_PACKAGES);
        if (ai != null) {
            Intent failedBroadcast = new Intent("android.intent.action.ACTIVITY_NOT_RESOLVED");
            failedBroadcast.putExtra("original_intent", intent);
            failedBroadcast.putExtra("calling_package", callingPackage);
            failedBroadcast.setPackage("com.lokker.app");  // targeted — only Lokker receives
            failedBroadcast.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mService.mContext.sendBroadcastAsUser(failedBroadcast,
                UserHandle.of(userId),
                android.Manifest.permission.MANAGE_USERS);
        }
    } catch (PackageManager.NameNotFoundException ignored) {
        // Truly uninstalled — no broadcast needed
    }
}
```

#### Lokker side (receiver)

**File:** `src/com/lokker/app/receiver/ActivityNotResolvedReceiver.java`

```java
public class ActivityNotResolvedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent original = intent.getParcelableExtra("original_intent", Intent.class);
        if (original == null || original.getComponent() == null) return;

        String targetPkg = original.getComponent().getPackageName();
        AppRepository repo = AppRepository.getInstance(context);

        if (repo.isHiddenApp(targetPkg)) {
            // Launch auth gate → on success: unhide + launch original intent
            Intent auth = new Intent(context, AuthActivity.class);
            auth.putExtra("target_package", targetPkg);
            auth.putExtra("original_intent", original);
            auth.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            context.startActivity(auth);
        }
    }
}
```

**Manifest entry:**

```xml
<receiver android:name=".receiver.ActivityNotResolvedReceiver"
    android:permission="android.permission.MANAGE_USERS"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.ACTIVITY_NOT_RESOLVED"/>
    </intent-filter>
</receiver>
```

### Why shelved

- Requires maintaining a framework patch across LineageOS updates
- Adds coupling between Lokker and a custom ROM build
- The proxy-activity approach (Variant A) works without framework changes
- Can be revisited once the core app is stable and tested
