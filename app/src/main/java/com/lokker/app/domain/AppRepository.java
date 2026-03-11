package com.lokker.app.domain;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.lokker.app.R;
import com.lokker.app.data.LokkerPrefs;
import com.lokker.app.data.db.Converters;
import com.lokker.app.data.db.HotkeyMap;
import com.lokker.app.data.db.HotkeyMapDao;
import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerAppDao;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.ui.AuthActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for Lokker's managed applications.
 *
 * Wraps Room database, PackageManager hidden APIs (via reflection), and
 * EncryptedSharedPreferences into one cohesive interface.
 */
public class AppRepository {

    private static final String TAG = "AppRepository";
    private static final String KEY_PENDING_REHIDE = "pending_rehide";
    private static final String KEY_UNHIDE_ALL_SNAPSHOT = "unhide_all_snapshot";
    private static final String KEY_SELF_HIDDEN = "self_hidden";

    private static volatile AppRepository INSTANCE;

    private final Context ctx;
    private final LokkerDatabase db;
    private final LokkerAppDao appDao;
    private final HotkeyMapDao hotkeyMapDao;
    private final PackageManager pm;
    private final LokkerPrefs lokkerPrefs;
    private final SharedPreferences prefs;

    private Set<String> pendingRehide = new HashSet<>();

    private AppRepository(Context context) {
        this.ctx = context.getApplicationContext();
        this.db = LokkerDatabase.getInstance(ctx);
        this.appDao = db.lokkerAppDao();
        this.hotkeyMapDao = db.hotkeyMapDao();
        this.pm = ctx.getPackageManager();
        this.lokkerPrefs = LokkerPrefs.getInstance(ctx);
        this.prefs = lokkerPrefs.getPrefs();
        loadPendingRehide();
    }

    public static AppRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AppRepository(context);
                }
            }
        }
        return INSTANCE;
    }

    // ── Application management ──────────────────────────────────────────

    /**
     * Add an application to Lokker's managed list.  Caches the icon and
     * label (which must happen before the app is hidden), inserts a Room
     * record, and updates dynamic shortcuts.  Does <b>not</b> hide the
     * app; call {@link #hideApp(String)} separately.
     */
    public void addApplication(String packageName) {
        String label = getAppLabel(packageName);
        cacheAppIcon(packageName);

        LokkerApp record = new LokkerApp(
                packageName,
                label,
                null,   // hotkeySequence
                false,  // hidden
                System.currentTimeMillis()
        );
        appDao.insert(record);

        rebuildDynamicShortcuts();
    }

    /**
     * Completely remove an application from Lokker.  Unhides on the system
     * level, deletes from Room, removes the pinned shortcut, and clears
     * the cached icon.
     */
    public void removeApplication(String packageName) {
        setApplicationHiddenSetting(packageName, false);

        appDao.delete(packageName);
        pendingRehide.remove(packageName);
        persistPendingRehide();

        rebuildDynamicShortcuts();

        new File(ctx.getFilesDir(), "icons/" + packageName + ".png").delete();
    }

    /**
     * Hide a managed application.  The app disappears from the launcher,
     * Settings, and all PM queries.
     */
    public void hideApp(String packageName) {
        LokkerApp record = appDao.get(packageName);
        if (record == null) return;

        setApplicationHiddenSetting(packageName, true);
        appDao.setHidden(packageName, true);
    }

    /**
     * Unhide a managed application permanently.  The app reappears in the
     * launcher/Settings but remains in Lokker's managed list.
     */
    public void unhideApp(String packageName) {
        LokkerApp record = appDao.get(packageName);
        if (record == null) return;

        setApplicationHiddenSetting(packageName, false);
        appDao.setHidden(packageName, false);
    }

    /**
     * Temporarily unhide an application so the user can interact with it.
     * The accessibility service will re-hide when the app loses foreground.
     *
     * @return {@code true} if the app was hidden and is now temporarily
     *         visible, {@code false} if not managed or not currently hidden.
     */
    public boolean unhideTemporarily(String packageName) {
        LokkerApp record = appDao.get(packageName);
        if (record == null || !record.hidden) return false;

        setApplicationHiddenSetting(packageName, false);

        pendingRehide.add(packageName);
        persistPendingRehide();

        return true;
    }

    /**
     * Re-hide a temporarily unhidden application and remove it from the
     * pending-rehide set.
     */
    public void rehideApp(String packageName) {
        setApplicationHiddenSetting(packageName, true);
        pendingRehide.remove(packageName);
        persistPendingRehide();
    }

    /**
     * Called from Application.onCreate() to re-hide any apps that leaked
     * visibility because the process was killed while they were temporarily
     * unhidden.
     */
    public void recoverLeakedApps() {
        loadPendingRehide();
        for (String pkg : new HashSet<>(pendingRehide)) {
            setApplicationHiddenSetting(pkg, true);
            pendingRehide.remove(pkg);
        }
        persistPendingRehide();
    }

    /**
     * Remove a package from the Recents list using
     * {@code ActivityManager.removeTask()}.
     */
    public void removeFromRecents(String packageName) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        if (am == null) return;

        @SuppressWarnings("deprecation")
        List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasks(100, 0);
        for (ActivityManager.RecentTaskInfo task : tasks) {
            if (task.baseIntent != null
                    && task.baseIntent.getComponent() != null
                    && packageName.equals(task.baseIntent.getComponent().getPackageName())) {
                try {
                    Method removeTask = ActivityManager.class.getMethod("removeTask", int.class);
                    removeTask.invoke(am, task.id);
                } catch (Exception ignored) {
                    // Not available on all API levels or permission denied
                }
            }
        }
    }

    /**
     * Launch a hidden app with flags that exclude it from Recents.
     * Must call {@link #unhideTemporarily(String)} first so that
     * {@code getLaunchIntentForPackage} can resolve the hidden package.
     */
    public void launchHiddenApp(String packageName) {
        Intent intent = pm.getLaunchIntentForPackage(packageName);

        // If null, the launcher activity may have been left disabled — fix it
        if (intent == null) {
            repairDisabledLauncherActivity(packageName);
            intent = pm.getLaunchIntentForPackage(packageName);
        }
        if (intent == null) return;

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        ctx.startActivity(intent);
    }

    /**
     * Re-enable any disabled launcher activities for the given package.
     */
    private void repairDisabledLauncherActivity(String packageName) {
        try {
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launcherIntent.setPackage(packageName);

            List<ResolveInfo> results = pm.queryIntentActivities(launcherIntent,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ResolveInfo ri : results) {
                ComponentName comp = new ComponentName(ri.activityInfo.packageName,
                        ri.activityInfo.name);
                pm.setComponentEnabledSetting(comp,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (Exception ignored) {}
    }

    // ── Queries ─────────────────────────────────────────────────────────

    /**
     * @return a {@link LiveData} list of all managed applications, ordered
     *         by label ascending.
     */
    public LiveData<List<LokkerApp>> getAllAppsLive() {
        return appDao.getAllLive();
    }

    /**
     * @return all currently hidden applications.
     */
    public List<LokkerApp> getAllHidden() {
        return appDao.getAllHidden();
    }

    /**
     * @return {@code true} if {@code packageName} is in Lokker's managed list.
     */
    public boolean isManaged(String packageName) {
        return appDao.isManaged(packageName);
    }

    /**
     * @return {@code true} if {@code packageName} is in the pending-rehide
     *         set (temporarily unhidden).
     */
    public boolean isPendingRehide(String packageName) {
        return pendingRehide.contains(packageName);
    }

    // ── Unhide all / Rehide all ─────────────────────────────────────────

    /**
     * Unhide all currently hidden applications.  Saves a snapshot of their
     * package names so {@link #rehideAll()} can restore the state later.
     */
    public void unhideAll() {
        List<LokkerApp> hiddenApps = appDao.getAllHidden();
        if (hiddenApps.isEmpty()) return;

        saveUnhideAllSnapshot(hiddenApps);

        for (LokkerApp app : hiddenApps) {
            setApplicationHiddenSetting(app.packageName, false);
            appDao.setHidden(app.packageName, false);
        }

        pendingRehide.clear();
        persistPendingRehide();
    }

    /**
     * Re-hide all applications that were previously unhidden via
     * {@link #unhideAll()}.  Uses the saved snapshot; skips apps that
     * have since been removed from Lokker or uninstalled from the device.
     */
    public void rehideAll() {
        List<String> snapshot = loadUnhideAllSnapshot();
        if (snapshot == null || snapshot.isEmpty()) return;

        for (String pkg : snapshot) {
            LokkerApp record = appDao.get(pkg);
            if (record == null) continue;

            try {
                pm.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }

            setApplicationHiddenSetting(pkg, true);
            appDao.setHidden(pkg, true);
        }

        clearUnhideAllSnapshot();
    }

    /**
     * @return {@code true} if an unhide-all snapshot exists (apps are
     *         currently mass-unhidden).
     */
    public boolean hasUnhideAllSnapshot() {
        return prefs.getString(KEY_UNHIDE_ALL_SNAPSHOT, null) != null;
    }

    // ── Self-hide ───────────────────────────────────────────────────────

    /**
     * Hide or show Lokker's own launcher icon by toggling the
     * {@code .LokkerLauncher} activity-alias.  Refuses to self-hide
     * unless a Lokker hotkey is configured (to prevent lockout).
     *
     * @param hidden {@code true} to remove from launcher, {@code false}
     *               to restore.
     * @throws IllegalStateException if attempting to self-hide without a
     *                               configured hotkey.
     */
    public void setSelfHidden(boolean hidden) {
        if (hidden) {
            HotkeyMap hk = hotkeyMapDao.get();
            if (hk == null || hk.lokkerHotkey == null || hk.lokkerHotkey.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot self-hide without a configured hotkey");
            }
        }

        ComponentName alias = new ComponentName(ctx, "com.lokker.app.LokkerLauncher");
        int state = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP);
        prefs.edit().putBoolean(KEY_SELF_HIDDEN, hidden).apply();
    }

    /**
     * @return {@code true} if Lokker's launcher icon is currently hidden.
     */
    public boolean isSelfHidden() {
        return prefs.getBoolean(KEY_SELF_HIDDEN, false);
    }

    // ── Export / Import ─────────────────────────────────────────────────

    /**
     * Export the current Lokker configuration as a JSON string.
     * Includes managed apps, Lokker hotkey, self-hidden flag, and
     * biometric flag.  Does <b>not</b> export password hash or
     * pending-rehide state.
     *
     * @return a pretty-printed JSON string.
     */
    public String exportSettings() {
        try {
            JSONObject root = new JSONObject();

            // App list
            JSONArray apps = new JSONArray();
            for (LokkerApp app : appDao.getAll()) {
                JSONObject obj = new JSONObject();
                obj.put("packageName", app.packageName);
                obj.put("appLabel", app.appLabel);
                obj.put("hidden", app.hidden);
                if (app.hotkeySequence != null) {
                    obj.put("hotkey", new JSONArray(app.hotkeySequence));
                }
                apps.put(obj);
            }
            root.put("apps", apps);

            // Lokker hotkey
            HotkeyMap hk = hotkeyMapDao.get();
            if (hk != null && hk.lokkerHotkey != null) {
                root.put("lokkerHotkey", new JSONArray(hk.lokkerHotkey));
            }

            // Settings
            root.put("selfHidden", prefs.getBoolean(KEY_SELF_HIDDEN, false));
            root.put("biometricEnabled",
                    prefs.getBoolean(LokkerPrefs.KEY_BIOMETRIC_ENROLLED, false));
            root.put("version", 1);

            return root.toString(2);
        } catch (JSONException e) {
            throw new RuntimeException("Failed to export settings", e);
        }
    }

    /**
     * Import Lokker configuration from a JSON string.  Replaces all
     * current data.  Packages not installed on the device are silently
     * skipped.
     *
     * @param json the JSON string previously produced by
     *             {@link #exportSettings()}.
     * @return the number of applications successfully imported.
     * @throws JSONException if the JSON is malformed.
     */
    public int importSettings(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray apps = root.getJSONArray("apps");

        // Clear existing data
        for (LokkerApp existing : appDao.getAll()) {
            removeApplication(existing.packageName);
        }

        int imported = 0;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject obj = apps.getJSONObject(i);
            String pkg = obj.getString("packageName");

            // Skip packages not installed on this device
            try {
                pm.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }

            addApplication(pkg);
            if (obj.optBoolean("hidden", false)) {
                hideApp(pkg);
            }

            // Per-app hotkey
            if (obj.has("hotkey")) {
                JSONArray hkArr = obj.getJSONArray("hotkey");
                List<Integer> seq = new ArrayList<>();
                for (int j = 0; j < hkArr.length(); j++) {
                    seq.add(hkArr.getInt(j));
                }
                appDao.setHotkey(pkg, Converters.fromIntList(seq));
            }
            imported++;
        }

        // Lokker hotkey
        if (root.has("lokkerHotkey")) {
            JSONArray hkArr = root.getJSONArray("lokkerHotkey");
            List<Integer> seq = new ArrayList<>();
            for (int i = 0; i < hkArr.length(); i++) {
                seq.add(hkArr.getInt(i));
            }
            HotkeyMap hk = new HotkeyMap();
            hk.lokkerHotkey = seq;
            hotkeyMapDao.insertOrUpdate(hk);
        }

        // Settings
        prefs.edit()
                .putBoolean(KEY_SELF_HIDDEN, root.optBoolean("selfHidden", false))
                .putBoolean(LokkerPrefs.KEY_BIOMETRIC_ENROLLED,
                        root.optBoolean("biometricEnabled", false))
                .apply();

        return imported;
    }

    // ── Dynamic shortcuts (appear on long-press of Lokker icon) ────────

    /**
     * Rebuild the full list of dynamic shortcuts from the current DB state.
     * Each hidden app gets a shortcut that launches through AuthActivity.
     * Max count is limited by the launcher (typically 4–5).
     */
    public void rebuildDynamicShortcuts() {
        ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
        if (sm == null) return;

        List<LokkerApp> apps = appDao.getAll();
        if (apps == null || apps.isEmpty()) {
            sm.removeAllDynamicShortcuts();
            return;
        }

        List<ShortcutInfo> shortcuts = new ArrayList<>();

        for (LokkerApp app : apps) {
            ShortcutInfo si = buildShortcutInfo(sm, app);
            if (si != null) shortcuts.add(si);
        }

        sm.setDynamicShortcuts(shortcuts);
        Log.d(TAG, "rebuildDynamicShortcuts: " + shortcuts.size() + " shortcuts set");
    }

    private ShortcutInfo buildShortcutInfo(ShortcutManager sm, LokkerApp app) {
        Intent target = new Intent(ctx, AuthActivity.class);
        target.setAction("com.lokker.app.LAUNCH_HIDDEN");
        target.putExtra("target_package", app.packageName);

        Icon icon = loadCachedIcon(app.packageName);
        if (icon == null) {
            icon = Icon.createWithResource(ctx, R.drawable.ic_launcher);
        }

        String label = app.appLabel != null ? app.appLabel : app.packageName;

        return new ShortcutInfo.Builder(ctx, "lokker_" + app.packageName)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(icon)
                .setIntent(target)
                .setRank(0)
                .build();
    }

    // ── Icon caching ────────────────────────────────────────────────────

    /**
     * Cache the application icon as a PNG before the app is hidden (hidden
     * apps are invisible to PackageManager queries).
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void cacheAppIcon(String packageName) {
        try {
            Drawable icon = pm.getApplicationIcon(packageName);
            Bitmap bmp = drawableToBitmap(icon);
            File file = new File(ctx.getFilesDir(), "icons/" + packageName + ".png");
            file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (PackageManager.NameNotFoundException | IOException ignored) {
            // Icon caching is best-effort
        }
    }

    /**
     * Load a previously cached icon for use in shortcuts and the UI.
     *
     * @return an {@link Icon}, or {@code null} if no cached icon exists.
     */
    public Icon loadCachedIcon(String packageName) {
        File file = new File(ctx.getFilesDir(), "icons/" + packageName + ".png");
        if (!file.exists()) return null;
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bmp == null) return null;
        return Icon.createWithBitmap(bmp);
    }

    // ── Pending-rehide persistence ──────────────────────────────────────

    private void persistPendingRehide() {
        prefs.edit()
                .putStringSet(KEY_PENDING_REHIDE, new HashSet<>(pendingRehide))
                .apply();
    }

    private void loadPendingRehide() {
        Set<String> stored = prefs.getStringSet(KEY_PENDING_REHIDE, null);
        pendingRehide = stored != null ? new HashSet<>(stored) : new HashSet<>();
    }

    // ── Unhide-all snapshot persistence ─────────────────────────────────

    private void saveUnhideAllSnapshot(List<LokkerApp> apps) {
        JSONArray arr = new JSONArray();
        for (LokkerApp app : apps) {
            arr.put(app.packageName);
        }
        prefs.edit().putString(KEY_UNHIDE_ALL_SNAPSHOT, arr.toString()).apply();
    }

    private List<String> loadUnhideAllSnapshot() {
        String json = prefs.getString(KEY_UNHIDE_ALL_SNAPSHOT, null);
        if (json == null) return null;

        try {
            List<String> result = new ArrayList<>();
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getString(i));
            }
            return result;
        } catch (JSONException e) {
            return null;
        }
    }

    private void clearUnhideAllSnapshot() {
        prefs.edit().remove(KEY_UNHIDE_ALL_SNAPSHOT).apply();
    }

    // ── PackageManager hidden API (reflection) ──────────────────────────

    /**
     * Call {@code IPackageManager.setApplicationHiddenSettingAsUser()} via
     * reflection through the binder service.  This hidden API is available
     * to platform-signed apps with the {@code MANAGE_USERS} permission.
     */
    public static void setApplicationHiddenSetting(String packageName, boolean hidden) {
        try {
            // Get IPackageManager via ServiceManager
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "package");

            Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object ipm = asInterface.invoke(null, binder);

            int userId = android.os.Process.myUserHandle().hashCode();
            Method setHidden = ipm.getClass().getMethod(
                    "setApplicationHiddenSettingAsUser",
                    String.class, boolean.class, int.class);
            setHidden.invoke(ipm, packageName, hidden, userId);
        } catch (Exception e) {
            throw new RuntimeException(
                    "setApplicationHiddenSetting failed for " + packageName, e);
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private String getAppLabel(String packageName) {
        try {
            return pm.getApplicationLabel(
                    pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w <= 0) w = 1;
        if (h <= 0) h = 1;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bmp;
    }
}
