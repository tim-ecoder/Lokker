package com.lokker.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.domain.HotkeyManager;
import com.lokker.app.ui.AuthActivity;

import com.lokker.app.receiver.ScreenReceiver;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Accessibility service that provides two core capabilities for Lokker:
 *
 * <ol>
 *   <li><b>Foreground detection</b> &mdash; listens for
 *       {@code TYPE_WINDOW_STATE_CHANGED} events to track the current
 *       foreground package.  When the user navigates <em>away</em> from a
 *       temporarily-unhidden app (one in {@link #pendingRehide}), the
 *       service immediately re-hides that app and removes it from the
 *       recent-apps list.</li>
 *   <li><b>Hardware-key hotkeys</b> &mdash; intercepts key events via
 *       {@link #onKeyEvent(KeyEvent)} and buffers them with a 1500&nbsp;ms
 *       inactivity timeout.  When the buffer matches the Lokker hotkey
 *       or a per-app hotkey, {@link AuthActivity} is launched.</li>
 * </ol>
 *
 * On {@link #onServiceConnected()}, the service also attempts to register
 * a {@code TaskStackListener} via reflection on {@code IActivityTaskManager}
 * to receive richer task-change callbacks on devices that support it.
 */
public class LokkerAccessibilityService extends AccessibilityService {

    private static final String TAG = "LokkerA11yService";
    private static final String LOKKER_PACKAGE = "com.lokker.app";

    /** The package name currently in the foreground. */
    private String currentForegroundPkg;

    /**
     * Set of package names that have been temporarily unhidden and must be
     * re-hidden as soon as the user navigates away from them.
     */
    private final Set<String> pendingRehide = ConcurrentHashMap.newKeySet();

    private HotkeyManager hotkeyManager;
    private LokkerDatabase db;
    private ExecutorService executor;
    private BroadcastReceiver screenReceiver;

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility service connected");

        db = LokkerDatabase.getInstance(this);
        hotkeyManager = new HotkeyManager(db.hotkeyMapDao(), db.lokkerAppDao());
        executor = Executors.newSingleThreadExecutor();

        // Ensure we receive key events
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }

        // Register ScreenReceiver dynamically (SCREEN_ON cannot be in manifest)
        screenReceiver = new ScreenReceiver();
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screenFilter);

        // Try to register TaskStackListener via reflection for richer
        // task-change callbacks (works on Android 9+ with system-level
        // permissions, harmless NoSuchMethod on other devices).
        tryRegisterTaskStackListener();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        Log.i(TAG, "Accessibility service destroyed");
    }

    // ── Accessibility events ────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;

        String newPkg = pkgCs.toString();

        // Ignore system UI noise (status bar, keyboard, etc.)
        if (newPkg.equals("com.android.systemui")) return;

        // Detect foreground change
        if (!newPkg.equals(currentForegroundPkg)) {
            String previousPkg = currentForegroundPkg;
            currentForegroundPkg = newPkg;

            // If the user navigated away from a temporarily-unhidden app,
            // re-hide it immediately and scrub it from recents.
            if (previousPkg != null && pendingRehide.contains(previousPkg)) {
                pendingRehide.remove(previousPkg);
                executor.execute(() -> {
                    rehideApp(previousPkg);
                    removeFromRecents(previousPkg);
                });
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }

    // ── Key event handling (hotkeys) ────────────────────────────────────

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.onKeyEvent(event);
        }

        hotkeyManager.onKeyDown(event.getKeyCode(), SystemClock.elapsedRealtime());

        // Check hotkeys on a background thread to avoid blocking key dispatch.
        // We snapshot the buffer so the check is consistent.
        List<Integer> bufferSnapshot = hotkeyManager.getBuffer();

        executor.execute(() -> {
            try {
                checkHotkeys(bufferSnapshot);
            } catch (Exception e) {
                Log.e(TAG, "Error checking hotkeys", e);
            }
        });

        return super.onKeyEvent(event);
    }

    /**
     * Check the current buffer against all configured hotkey sequences.
     *
     * @param buffer snapshot of the key-code buffer at the time the key
     *               was pressed.
     */
    private void checkHotkeys(List<Integer> buffer) {
        HotkeyManager.HotkeyConfig config = hotkeyManager.getHotkeyConfig();

        // 1. Check the Lokker hotkey (opens AuthActivity with no target).
        List<Integer> lokkerHotkey = config.getLokkerHotkey();
        if (lokkerHotkey != null && !lokkerHotkey.isEmpty()) {
            if (HotkeyManager.endsWith(buffer, lokkerHotkey)) {
                hotkeyManager.clearBuffer();
                launchAuth(null);
                return;
            }
        }

        // 2. Check per-app hotkeys.
        Map<String, List<Integer>> appHotkeys = config.getAppHotkeys();
        for (Map.Entry<String, List<Integer>> entry : appHotkeys.entrySet()) {
            List<Integer> seq = entry.getValue();
            if (seq != null && !seq.isEmpty()
                    && HotkeyManager.endsWith(buffer, seq)) {
                hotkeyManager.clearBuffer();
                launchAuth(entry.getKey());
                return;
            }
        }
    }

    // ── App hiding / recents ────────────────────────────────────────────

    /**
     * Re-hide a temporarily-unhidden app using the hidden
     * {@code PackageManager.setApplicationHiddenSetting} API via reflection.
     */
    private void rehideApp(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            Method setHidden = pm.getClass().getMethod(
                    "setApplicationHiddenSetting",
                    String.class, boolean.class);
            setHidden.invoke(pm, packageName, true);

            // Update database to reflect the hidden state
            db.lokkerAppDao().setHidden(packageName, true);

            Log.d(TAG, "Re-hid app: " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to re-hide app: " + packageName, e);
        }
    }

    /**
     * Remove all tasks belonging to the given package from the recent-apps
     * list so that no trace remains after re-hiding.
     */
    private void removeFromRecents(String packageName) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;

            List<ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks == null) return;

            for (ActivityManager.AppTask task : tasks) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.baseIntent != null
                        && info.baseIntent.getComponent() != null
                        && packageName.equals(
                                info.baseIntent.getComponent().getPackageName())) {
                    task.finishAndRemoveTask();
                    Log.d(TAG, "Removed task from recents for: " + packageName);
                }
            }

            // Fallback: use the hidden removeTask API for tasks not in our own
            // app-task list (i.e. tasks belonging to other apps).
            @SuppressWarnings("deprecation")
            List<ActivityManager.RecentTaskInfo> recentTasks =
                    am.getRecentTasks(50, ActivityManager.RECENT_WITH_EXCLUDED);
            if (recentTasks != null) {
                for (ActivityManager.RecentTaskInfo info : recentTasks) {
                    if (info.baseIntent != null
                            && info.baseIntent.getComponent() != null
                            && packageName.equals(
                                    info.baseIntent.getComponent().getPackageName())) {
                        try {
                            Method removeTask = am.getClass().getMethod(
                                    "removeTask", int.class);
                            removeTask.invoke(am, info.id);
                            Log.d(TAG, "Removed recent task id=" + info.id
                                    + " for: " + packageName);
                        } catch (Exception e) {
                            Log.w(TAG, "removeTask reflection failed", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove from recents: " + packageName, e);
        }
    }

    // ── Auth launching ──────────────────────────────────────────────────

    /**
     * Launch {@link AuthActivity}.
     *
     * @param targetPackage the hidden app to unlock after auth, or
     *                      {@code null} to open Lokker itself.
     */
    private void launchAuth(String targetPackage) {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (targetPackage != null) {
            intent.putExtra("target_package", targetPackage);
        }
        startActivity(intent);
        Log.d(TAG, "Launched AuthActivity"
                + (targetPackage != null ? " for " + targetPackage : ""));
    }

    // ── TaskStackListener registration (best-effort) ────────────────────

    /**
     * Attempt to register a {@code TaskStackListener} via
     * {@code IActivityTaskManager} reflection.  This gives us richer
     * task lifecycle callbacks (e.g. {@code onTaskRemoved},
     * {@code onTaskMovedToFront}) on devices where the hidden API is
     * accessible (typically device-owner / system-app deployments).
     *
     * Fails silently on stock devices without elevated privileges.
     */
    private void tryRegisterTaskStackListener() {
        try {
            // Step 1: Get IActivityTaskManager.Stub.asInterface(ServiceManager.getService("activity_task"))
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "activity_task");

            if (binder == null) {
                Log.d(TAG, "activity_task service binder is null; skipping TaskStackListener");
                return;
            }

            Class<?> stubClass = Class.forName(
                    "android.app.IActivityTaskManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object activityTaskManager = asInterface.invoke(null, binder);

            if (activityTaskManager == null) {
                Log.d(TAG, "IActivityTaskManager is null; skipping TaskStackListener");
                return;
            }

            // Step 2: Create a TaskStackListener proxy.
            // android.app.TaskStackListener is an abstract class that implements
            // ITaskStackListener.Stub.  We instantiate it anonymously.
            Class<?> taskStackListenerClass = Class.forName("android.app.TaskStackListener");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    taskStackListenerClass.getClassLoader(),
                    taskStackListenerClass.getInterfaces(),
                    (proxy, method, args) -> {
                        // We only care about onTaskMovedToFront for now.
                        if ("onTaskMovedToFront".equals(method.getName())) {
                            // args[0] is RunningTaskInfo on newer APIs,
                            // or int taskId on older ones.
                            Log.d(TAG, "TaskStackListener: onTaskMovedToFront");
                        }
                        // Return sensible defaults for other methods
                        if (method.getReturnType() == void.class) return null;
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    });

            // Step 3: Register the listener.
            Method registerMethod = activityTaskManager.getClass().getMethod(
                    "registerTaskStackListener",
                    Class.forName("android.app.ITaskStackListener"));
            registerMethod.invoke(activityTaskManager, listener);

            Log.i(TAG, "Successfully registered TaskStackListener");
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "TaskStackListener classes not found (expected on stock devices)", e);
        } catch (NoSuchMethodException e) {
            Log.d(TAG, "TaskStackListener methods not found (API level mismatch)", e);
        } catch (SecurityException e) {
            Log.d(TAG, "No permission to register TaskStackListener (expected on stock)", e);
        } catch (Exception e) {
            Log.d(TAG, "TaskStackListener registration failed", e);
        }
    }

    // ── Public API for other components ─────────────────────────────────

    /**
     * Mark a package as pending re-hide.  Called by {@code AppRepository}
     * (or similar) after temporarily unhiding an app for the user.
     * The service will automatically re-hide it when the user navigates
     * away.
     */
    public void addPendingRehide(String packageName) {
        pendingRehide.add(packageName);
        Log.d(TAG, "Added to pendingRehide: " + packageName);
    }

    /**
     * @return the package name currently detected in the foreground,
     *         or {@code null} if unknown.
     */
    public String getCurrentForegroundPkg() {
        return currentForegroundPkg;
    }
}
