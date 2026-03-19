package com.lokker.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.SystemClock;
import android.media.AudioAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.domain.AppRepository;
import com.lokker.app.domain.HotkeyManager;
import com.lokker.app.ui.AuthActivity;

import com.lokker.app.receiver.ScreenReceiver;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    /** Callback for hotkey recording mode. */
    public interface KeyRecordListener {
        void onKeyRecorded(int keyCode);
    }

    private static volatile KeyRecordListener recordListener;
    private static volatile HotkeyManager activeHotkeyManager;

    public static void startRecording(KeyRecordListener listener) {
        recordListener = listener;
        // Clear the hotkey buffer so partial matches from before recording
        // don't carry over after recording stops.
        HotkeyManager mgr = activeHotkeyManager;
        if (mgr != null) mgr.clearBuffer();
    }

    public static void stopRecording() {
        recordListener = null;
        HotkeyManager mgr = activeHotkeyManager;
        if (mgr != null) mgr.clearBuffer();
    }

    /** The package name currently in the foreground. */
    private String currentForegroundPkg;

    private AppRepository repo;
    private HotkeyManager hotkeyManager;
    private LokkerDatabase db;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> rehideOnClosePoll;
    private BroadcastReceiver screenReceiver;

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility service connected");

        db = LokkerDatabase.getInstance(this);
        repo = AppRepository.getInstance(this);
        hotkeyManager = new HotkeyManager(db.hotkeyMapDao(), db.lokkerAppDao());
        activeHotkeyManager = hotkeyManager;
        executor = Executors.newSingleThreadExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // Ensure we receive key events
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }

        // Register ScreenReceiver dynamically (SCREEN_ON cannot be in manifest)
        screenReceiver = new ScreenReceiver();
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);

        // Try to register TaskStackListener via reflection for richer
        // task-change callbacks (works on Android 9+ with system-level
        // permissions, harmless NoSuchMethod on other devices).
        tryRegisterTaskStackListener();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        activeHotkeyManager = null;
        if (rehideOnClosePoll != null) {
            rehideOnClosePoll.cancel(false);
        }
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
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

        // Ignore system UI noise (status bar, keyboard, overlays, etc.)
        if (newPkg.equals("com.android.systemui")) return;
        if (isInputMethod(newPkg)) return;

        // Detect foreground change
        if (!newPkg.equals(currentForegroundPkg)) {
            String previousPkg = currentForegroundPkg;
            currentForegroundPkg = newPkg;

            // If the user navigated away from a temporarily-unhidden app,
            // re-hide it immediately and scrub it from recents.
            if (previousPkg != null && repo.isPendingRehide(previousPkg)) {
                executor.execute(() -> {
                    repo.rehideApp(previousPkg);
                    removeFromRecents(previousPkg);
                });
            }

            // Check "unhide until closed" apps on foreground changes.
            if (repo.hasPendingRehideOnClose()) {
                executor.execute(this::checkRehideOnClose);
            }
        }
    }

    private boolean isInputMethod(String pkg) {
        try {
            List<android.view.inputmethod.InputMethodInfo> imes =
                    ((android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE)).getInputMethodList();
            for (android.view.inputmethod.InputMethodInfo ime : imes) {
                if (pkg.equals(ime.getPackageName())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }

    // ── Key event handling (hotkeys) ────────────────────────────────────

    /** Threshold for distinguishing a tap from a long press. */
    private static final long LONG_PRESS_MS = 500;

    private volatile ScheduledFuture<?> pendingLongPress;
    private int lastKeyDown = -1;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return super.onKeyEvent(event);

        int keyCode = event.getKeyCode();

        // ── ACTION_UP → cancel long-press timer (it was a short tap) ─────
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (keyCode == lastKeyDown && pendingLongPress != null) {
                pendingLongPress.cancel(false);
                pendingLongPress = null;
                lastKeyDown = -1;
            }
            return super.onKeyEvent(event);
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.onKeyEvent(event);
        }

        // If the same key arrives again (repeat/held), ignore it —
        // let the pending long-press timer continue undisturbed.
        if (keyCode == lastKeyDown && pendingLongPress != null) {
            KeyRecordListener listener = recordListener;
            // Consume repeats during recording, but never consume BACK
            return listener != null && keyCode != KeyEvent.KEYCODE_BACK;
        }

        // Different key: cancel any pending long-press from previous key
        ScheduledFuture<?> pending = pendingLongPress;
        if (pending != null) {
            pending.cancel(false);
            pendingLongPress = null;
        }
        lastKeyDown = keyCode;

        // ── Recording mode ───────────────────────────────────────────────
        KeyRecordListener listener = recordListener;
        if (listener != null) {
            // Let BACK pass through so navigation still works
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                return super.onKeyEvent(event);
            }
            listener.onKeyRecorded(keyCode);
            // Schedule long-press upgrade
            pendingLongPress = scheduler.schedule(() -> {
                Log.d(TAG, "Long press detected (recording) for keyCode=" + keyCode);
                vibrateTick(); // always vibrate during recording to confirm long press
                listener.onKeyRecorded(-keyCode);
            }, LONG_PRESS_MS, TimeUnit.MILLISECONDS);
            return true;
        }

        // ── Normal hotkey mode ───────────────────────────────────────────
        hotkeyManager.onKeyDown(keyCode, SystemClock.elapsedRealtime());
        List<Integer> bufferSnapshot = hotkeyManager.getBuffer();

        executor.execute(() -> {
            try {
                if (isBufferHotkeyPrefix(bufferSnapshot)) {
                    vibrateTick();
                }
                checkHotkeys(bufferSnapshot);
            } catch (Exception e) {
                Log.e(TAG, "Error checking hotkeys", e);
            }
        });

        // Schedule long-press upgrade
        pendingLongPress = scheduler.schedule(() -> {
            Log.d(TAG, "Long press detected (hotkey) for keyCode=" + keyCode);
            hotkeyManager.upgradeLongPress(keyCode);
            List<Integer> snapshot = hotkeyManager.getBuffer();
            executor.execute(() -> {
                try {
                    if (isBufferHotkeyPrefix(snapshot)) {
                        vibrateTick();
                    }
                    checkHotkeys(snapshot);
                } catch (Exception e) {
                    Log.e(TAG, "Error checking hotkeys", e);
                }
            });
        }, LONG_PRESS_MS, TimeUnit.MILLISECONDS);

        return super.onKeyEvent(event);
    }

    /**
     * Check the current buffer against all configured hotkey sequences.
     *
     * @param buffer snapshot of the key-code buffer at the time the key
     *               was pressed.
     */
    private void checkHotkeys(List<Integer> buffer) {
        // If recording started between snapshot and execution, skip matching.
        if (recordListener != null) return;

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

    /**
     * Check if the buffer is a prefix (or full match) of any configured hotkey.
     */
    private boolean isBufferHotkeyPrefix(List<Integer> buffer) {
        if (buffer == null || buffer.isEmpty()) return false;

        HotkeyManager.HotkeyConfig config = hotkeyManager.getHotkeyConfig();

        List<Integer> lokkerHotkey = config.getLokkerHotkey();
        if (lokkerHotkey != null && isPrefix(buffer, lokkerHotkey)) return true;

        for (List<Integer> seq : config.getAppHotkeys().values()) {
            if (seq != null && isPrefix(buffer, seq)) return true;
        }
        return false;
    }

    /** True if buffer ends with a prefix (or full match) of target sequence. */
    private static boolean isPrefix(List<Integer> buffer, List<Integer> target) {
        // Check if the last N elements of buffer match the first N of target
        int len = Math.min(buffer.size(), target.size());
        for (int i = 0; i < len; i++) {
            if (!buffer.get(buffer.size() - len + i).equals(target.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ── App hiding / recents ────────────────────────────────────────────

    /**
     * Check whether any "unhide until closed" apps no longer have a task
     * in the recents list (i.e. the user has closed them) and re-hide them.
     *
     * Also manages a periodic poll: starts a 3-second timer when there are
     * pending apps (to catch task removal when no accessibility events fire),
     * and cancels it when the set is empty.
     */
    /**
     * Must run on {@link #executor} thread only — both accessibility-event
     * calls and the periodic poll funnel through the executor so all access
     * to the poll future and the pending set is single-threaded.
     */
    private void checkRehideOnClose() {
        Set<String> pending = repo.getPendingRehideOnClose();
        if (pending.isEmpty()) {
            stopRehideOnClosePoll();
            return;
        }

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;

        for (String pkg : pending) {
            if (repo.isInRehideOnCloseGrace(pkg)) continue;
            if (!hasTaskInRecents(am, pkg)) {
                repo.rehideClosedApp(pkg);
                Log.d(TAG, "Rehid closed app: " + pkg);
            }
        }

        // Start or stop the poll based on whether apps remain
        if (repo.hasPendingRehideOnClose()) {
            startRehideOnClosePoll();
        } else {
            stopRehideOnClosePoll();
        }
    }

    /** Start a 3-second poll (serialised through {@link #executor}). */
    private void startRehideOnClosePoll() {
        if (rehideOnClosePoll != null) return;
        rehideOnClosePoll = scheduler.scheduleAtFixedRate(
                () -> executor.execute(() -> {
                    try { checkRehideOnClose(); } catch (Exception e) {
                        Log.w(TAG, "rehideOnClose poll error", e);
                    }
                }),
                3, 3, TimeUnit.SECONDS);
        Log.d(TAG, "Started rehideOnClose poll");
    }

    private void stopRehideOnClosePoll() {
        ScheduledFuture<?> poll = rehideOnClosePoll;
        if (poll != null) {
            poll.cancel(false);
            rehideOnClosePoll = null;
            Log.d(TAG, "Stopped rehideOnClose poll");
        }
    }

    /**
     * @return {@code true} if the given package still has at least one task
     *         in the recent-apps list.
     */
    @SuppressWarnings("deprecation")
    private boolean hasTaskInRecents(ActivityManager am, String packageName) {
        try {
            List<ActivityManager.RecentTaskInfo> tasks =
                    am.getRecentTasks(100, ActivityManager.RECENT_WITH_EXCLUDED);
            if (tasks != null) {
                for (ActivityManager.RecentTaskInfo task : tasks) {
                    if (task.baseIntent != null
                            && task.baseIntent.getComponent() != null
                            && packageName.equals(
                                    task.baseIntent.getComponent().getPackageName())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "hasTaskInRecents failed for " + packageName, e);
        }
        return false;
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
            intent.setData(android.net.Uri.parse("lokker://launch/" + targetPackage));
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
                        if ("onTaskMovedToFront".equals(method.getName())) {
                            Log.d(TAG, "TaskStackListener: onTaskMovedToFront");
                        } else if ("onTaskRemoved".equals(method.getName())) {
                            // A task was removed — check if any "unhide until
                            // closed" apps should be re-hidden.
                            Log.d(TAG, "TaskStackListener: onTaskRemoved");
                            executor.execute(this::checkRehideOnClose);
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

    // ── Vibration ─────────────────────────────────────────────────────

    private static final AudioAttributes VIBRATE_ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private void vibrateTick() {
        try {
            Vibrator v;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getApplicationContext()
                        .getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = (vm != null) ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) getApplicationContext()
                        .getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) {
                Log.w(TAG, "No vibrator available");
                return;
            }
            v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE),
                    VIBRATE_ATTRS);
            Log.d(TAG, "Vibrate called with USAGE_ALARM attrs");
        } catch (Exception e) {
            Log.w(TAG, "Vibration failed", e);
        }
    }

    // ── Public API for other components ─────────────────────────────────

    /**
     * @return the package name currently detected in the foreground,
     *         or {@code null} if unknown.
     */
    public String getCurrentForegroundPkg() {
        return currentForegroundPkg;
    }
}
