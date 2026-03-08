# Lokker — System App Specification

**Package:** `com.lokker.app`
**Target:** LineageOS 22 · Android 15 · API 35
**Language:** Java
**Type:** Privileged system APK (`/system/priv-app`)

---

## 1. Feature Overview

### Core Features

- Hide any installed app from the launcher
- Hide Lokker itself from the launcher
- Password/biometric gate on Lokker open
- Password gate on hidden app open
- **Rehide app automatically when user switches away** (Home, Back, Recents, or opening another app)
- Remove hidden apps from Recents screen
- Suppress notifications from hidden apps
- **GUI for adding/removing apps** to the hidden list (searchable app picker)
- Secret hotkey to reveal Lokker
- Secret hotkey to open a hidden app directly
- Dialer secret code entry (`*#5655#`)

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
│ AuthActivity │              │NotifListener │ BootReceiver  │
│ SetupActivity│              │              │ SecretCodeRx  │
│ HotkeySetup  │              │              │ ScreenReceiver│
├──────────────┴──────────────┴──────────────┴───────────────┤
│                      Repository                             │
│              AppRepository (single source of truth)         │
├────────────────────────────┬────────────────────────────────┤
│       LokkerDatabase       │    EncryptedPreferences        │
│  Room: HiddenApp, HotkeyMap│  auth hash, self-hide flag     │
└────────────────────────────┴────────────────────────────────┘
```

### Component Responsibilities

| Component | Type | Responsibility |
|---|---|---|
| `LokkerAccessibilityService` | AccessibilityService | Detect foreground app changes; **trigger rehide when hidden app loses focus**; remove from Recents; hotkey detection |
| `LokkerNotificationListener` | NotificationListenerService | Intercept and cancel notifications from hidden packages |
| `PackageMonitor` | BroadcastReceiver | `PACKAGE_REPLACED`/`ADDED` — re-apply hide state after updates |
| `BootReceiver` | BroadcastReceiver | `BOOT_COMPLETED` — re-apply all component states |
| `ScreenReceiver` | BroadcastReceiver | `SCREEN_ON` — re-verify all hidden app states |
| `SecretCodeReceiver` | BroadcastReceiver | Dialer `*#5655#` → launch AuthActivity |
| `AppRepository` | Repository | Single interface to Room DB + EncryptedSharedPreferences + PackageManager |

---

## 3. App Hiding Mechanism

Core hiding uses `PackageManager.setComponentEnabledSetting()` to disable launcher activity components.

### hideApp(packageName)

```java
// AppRepository.java

public void hideApp(String packageName) {
    Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
    launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
    launcherIntent.setPackage(packageName);

    // queryIntentActivities works for priv-app even on API 35
    List<ResolveInfo> activities = pm.queryIntentActivities(
        launcherIntent,
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL)
    );

    List<String> componentNames = new ArrayList<>();
    for (ResolveInfo info : activities) {
        ComponentName cn = new ComponentName(
            info.activityInfo.packageName,
            info.activityInfo.name
        );
        pm.setComponentEnabledSetting(
            cn,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
        componentNames.add(info.activityInfo.name);
    }

    // Persist to Room
    HiddenApp record = new HiddenApp(
        packageName,
        componentNames,
        getAppLabel(packageName),
        null, // hotkeySequence
        System.currentTimeMillis()
    );
    db.hiddenAppDao().insert(record);
}
```

### unhideTemporarily(packageName)

Called before launching a hidden app through Lokker UI. Re-enables components temporarily; `LokkerAccessibilityService` will rehide when the app loses foreground.

```java
public boolean unhideTemporarily(String packageName) {
    HiddenApp record = db.hiddenAppDao().get(packageName);
    if (record == null) return false;

    for (String activityName : record.getDisabledComponents()) {
        pm.setComponentEnabledSetting(
            new ComponentName(packageName, activityName),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        );
    }

    // LokkerAccessibilityService will re-hide when app loses foreground
    pendingRehide.add(packageName);
    return true;
}
```

### unhideApp(packageName) — permanent unhide

Called when user removes an app from the hidden list via the GUI.

```java
public void unhideApp(String packageName) {
    HiddenApp record = db.hiddenAppDao().get(packageName);
    if (record == null) return;

    for (String activityName : record.getDisabledComponents()) {
        pm.setComponentEnabledSetting(
            new ComponentName(packageName, activityName),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        );
    }

    db.hiddenAppDao().delete(packageName);
    pendingRehide.remove(packageName);
}
```

---

## 4. Rehide on App Switch

**This is the critical behavioral feature.** When a hidden app is temporarily unlocked and the user navigates away (Home, Back, Recents, or simply opening another app), the hidden app must immediately be re-hidden and removed from Recents.

### Detection via AccessibilityService

```java
// LokkerAccessibilityService.java

private String currentForegroundPkg = null;

@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

    CharSequence pkgSeq = event.getPackageName();
    if (pkgSeq == null) return;
    String pkg = pkgSeq.toString();

    // A different app came to foreground
    if (!pkg.equals(currentForegroundPkg)) {
        String prev = currentForegroundPkg;
        currentForegroundPkg = pkg;

        // Was the previous app a temporarily-unlocked hidden app?
        if (prev != null && repo.isPendingRehide(prev)) {
            // Re-disable component immediately
            repo.hideApp(prev);
            // Remove from Recents
            repo.removeFromRecents(prev);
            // Clear pending state
            repo.removePendingRehide(prev);
        }
    }
}
```

### Recents Removal (dual mechanism)

```java
// Mechanism 1: Flags at launch time
public void launchHiddenApp(String packageName) {
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
  → unhideTemporarily() — re-enables launcher component
  → app added to pendingRehide set
  → startActivity with EXCLUDE_FROM_RECENTS flags
  → user uses the app normally
  → user presses Home / Back / switches app
  → AccessibilityService detects TYPE_WINDOW_STATE_CHANGED
  → previous package was in pendingRehide
  → hideApp() — re-disables component
  → removeFromRecents() — clears from task list
  → done — app is invisible again
```

---

## 5. Self-Hiding & Secret Entry

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

```java
public void setSelfHidden(boolean hidden) {
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

- RecyclerView showing all currently hidden apps
- Each row shows: **app icon** | **app name** | **package name** (dimmed) | **unhide button**
- Long-press on a row opens options: Unhide, Set Hotkey, Launch
- **Floating Action Button (FAB)** → opens the App Picker to add apps
- Empty state: centered message "No hidden apps. Tap + to hide an app."

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

- **Self-hide toggle** — hide/show Lokker in the launcher
- **Change password** — re-enter current, set new
- **Hotkey configuration** — opens `HotkeySetupActivity`
- **Biometric toggle** — enable/disable biometric auth
- **About** — version info

### 7.4 UI Layout Summary

```
┌──────────────────────────────────┐
│ Toolbar: "Lokker"     [⚙ gear]  │
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

[⊘] = unhide button
[+ FAB] = open app picker
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

---

## 8. Notification Suppression

```java
// LokkerNotificationListener.java

public class LokkerNotificationListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (repo.isHidden(pkg)) {
            cancelNotification(sbn.getKey());
            // Also clear grouped notifications
            for (StatusBarNotification n : getActiveNotifications()) {
                if (pkg.equals(n.getPackageName())) {
                    cancelNotification(n.getKey());
                }
            }
        }
    }

    @Override
    public void onListenerConnected() {
        // On connect: cancel existing notifications from hidden apps
        for (StatusBarNotification sbn : getActiveNotifications()) {
            if (repo.isHidden(sbn.getPackageName())) {
                cancelNotification(sbn.getKey());
            }
        }
    }
}
```

> **Note:** NotificationListenerService needs user to enable in Settings → Notifications → Notification access. For priv-app, auto-grant via `WRITE_SECURE_SETTINGS`: `Settings.Secure.putString(resolver, "enabled_notification_listeners", "com.lokker.app/.service.LokkerNotificationListener")`

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
@Entity(tableName = "hidden_apps")
public class HiddenApp {
    @PrimaryKey @NonNull
    public String packageName;
    @TypeConverters(Converters.class)
    public List<String> disabledComponents;
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
    // "notif_auto_granted" — boolean
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
    sdk_version: "35",
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
        <permission name="android.permission.CHANGE_COMPONENT_ENABLED_STATE"/>
        <permission name="android.permission.REMOVE_TASKS"/>
        <permission name="android.permission.GET_TASKS"/>
        <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
        <permission name="android.permission.REAL_GET_TASKS"/>
        <permission name="android.permission.MANAGE_ACTIVITY_STACKS"/>
    </privapp-permissions>
</permissions>
```

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
│   │   ├── activity_main.xml          # hidden apps list + FAB
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
        │   ├── LokkerAccessibilityService.java   # foreground monitor + rehide + hotkey
        │   └── LokkerNotificationListener.java   # notification suppression
        ├── ui/
        │   ├── MainActivity.java           # hidden apps list, FAB, settings menu
        │   ├── AuthActivity.java           # PIN + biometric gate
        │   ├── SetupActivity.java          # first-run password setup
        │   ├── HotkeySetupActivity.java    # record key sequences
        │   ├── AppPickerDialog.java        # searchable app picker (add to hidden)
        │   ├── LokkerViewModel.java        # LiveData for hidden apps list
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
| User opens launcher | Hidden app icon not visible | `setComponentEnabledSetting DISABLED` |
| User opens Lokker (icon hidden) | Lokker not visible in launcher | `setComponentEnabledSetting` on self alias |
| User presses hotkey | Auth prompt → Lokker UI opens | `onKeyEvent` intercept → `BiometricPrompt` |
| User launches hidden app via Lokker | Auth → app launches normally | Temp re-enable → `startActivity` → rehide on switch |
| **User switches away from hidden app** | **App re-hides immediately, removed from Recents** | **`TYPE_WINDOW_STATE_CHANGED` → `hideApp()` + `removeTask()`** |
| User presses Home in hidden app | App re-hides, removed from Recents | Same as above |
| User presses Back out of hidden app | App re-hides, removed from Recents | Same as above |
| User opens Recents while in hidden app | App re-hides, not visible in Recents | Same + `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` |
| Hidden app sends notification | Notification suppressed | `NotificationListenerService.cancelNotification` |
| Hidden app updated via system | Stays hidden after update | `PACKAGE_REPLACED` broadcast → re-disable |
| Device reboots | All hidden apps remain hidden | `packages.xml` + `BOOT_COMPLETED` re-apply |
| User adds app via GUI picker | App hidden, appears in hidden list | `hideApp()` + Room insert + LiveData update |
| User removes app via GUI | App unhidden, disappears from list | `unhideApp()` + Room delete + LiveData update |

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
    <uses-permission android:name="android.permission.CHANGE_COMPONENT_ENABLED_STATE"/>
    <uses-permission android:name="android.permission.REMOVE_TASKS"/>
    <uses-permission android:name="android.permission.GET_TASKS"/>
    <uses-permission android:name="android.permission.REAL_GET_TASKS"/>
    <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>
    <uses-permission android:name="android.permission.MANAGE_ACTIVITY_STACKS"/>
</manifest>
```

---

## 15. Development Phases

### Phase 1 — Foundation (~3 days)
- AOSP module scaffold: `Android.bp`, manifest, build integration
- `privapp-permissions` XML + `device.mk` wiring
- `LokkerDatabase` (Room) + `HiddenApp` entity + DAO
- `LokkerPrefs` (EncryptedSharedPreferences)
- `AppRepository` skeleton with PackageManager wiring
- Verify `setComponentEnabledSetting` works as priv-app on device

### Phase 2 — Core Hiding (~3 days)
- `hideApp()` / `unhideApp()` / `unhideTemporarily()` full implementation
- `BootReceiver` — re-apply all hidden states on boot
- `PackageMonitor` — `PACKAGE_REPLACED` / `PACKAGE_ADDED` handling
- Self-hiding via activity-alias disable
- `SecretCodeReceiver` (dialer code entry)

### Phase 3 — GUI (~3 days)
- `MainActivity` layout: RecyclerView + FAB + toolbar menu
- `HiddenAppsAdapter` with app icon, name, package, unhide button
- `AppPickerDialog`: searchable list of all installed apps
- `AppPickerAdapter` with checkbox multi-select
- "Show system apps" toggle in picker
- `LokkerViewModel` with LiveData from Room DAO
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
- `TYPE_WINDOW_STATE_CHANGED` → detect foreground app change
- `pendingRehide` set: track temporarily-unlocked apps
- **Rehide on app switch** — re-disable + remove from Recents
- `removeFromRecents()` via `ActivityManager.removeTask()`
- `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` on launch
- Auto-enable Accessibility via `WRITE_SECURE_SETTINGS`

### Phase 6 — Notifications + Hotkeys (~2 days)
- `LokkerNotificationListener`: cancel notifications for hidden packages
- Auto-grant notification listener access via `Settings.Secure`
- `HotkeyManager`: sequence buffer + timeout logic
- `onKeyEvent` in AccessibilityService
- `HotkeySetupActivity`: record and save sequences
- Per-app hotkey support

### Phase 7 — Polish & Testing (~3 days)
- Edge cases: apps with multiple LAUNCHER activities
- Edge cases: apps that re-enable themselves
- Test on LineageOS 22 device
- Dark theme, edge-to-edge (Android 15 mandatory)
- No-icon mode: verify Lokker invisible in all launchers
- Stress test: hide/unhide 20+ apps
- `ScreenReceiver`: on unlock re-check all hidden state integrity

**Total estimate: ~19 developer-days for solo dev**
