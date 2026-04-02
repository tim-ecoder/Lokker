package com.lokker.app.domain;

import com.lokker.app.data.db.HotkeyMap;
import com.lokker.app.data.db.HotkeyMapDao;
import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerAppDao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hotkey sequence matching.
 *
 * Maintains a rolling buffer of key-codes with a 1500 ms inactivity timeout.
 * The accessibility service feeds key-down events into {@link #onKeyDown(int)}
 * and then calls {@link #getHotkeyConfig()} to check for matches via
 * {@link #endsWith(List, List)}.
 */
public class HotkeyManager {

    /** Maximum time between consecutive key presses before the buffer resets. */
    private static final long SEQ_TIMEOUT_MS = 1500L;

    /** Encoded key offsets for multi-press gestures. */
    public static final int DOUBLE_PRESS_OFFSET = 10000;
    public static final int TRIPLE_PRESS_OFFSET = 20000;
    /** Hold = key physically down when the next key arrives. */
    public static final int HOLD_OFFSET = 30000;

    private final HotkeyMapDao hotkeyMapDao;
    private final LokkerAppDao lokkerAppDao;

    private final List<Integer> buffer = new ArrayList<>();
    private long lastKeyTime = 0L;

    public HotkeyManager(HotkeyMapDao hotkeyMapDao, LokkerAppDao lokkerAppDao) {
        this.hotkeyMapDao = hotkeyMapDao;
        this.lokkerAppDao = lokkerAppDao;
    }

    // ── Buffer management ───────────────────────────────────────────────

    /**
     * Feed a key-down event into the buffer.  If the elapsed time since the
     * previous key exceeds {@link #SEQ_TIMEOUT_MS} the buffer is cleared
     * first.
     *
     * @param keyCode the {@code KeyEvent} key-code.
     * @param elapsedRealtime value from {@code SystemClock.elapsedRealtime()}.
     */
    public void onKeyDown(int keyCode, long elapsedRealtime) {
        if (elapsedRealtime - lastKeyTime > SEQ_TIMEOUT_MS) {
            buffer.clear();
        }
        lastKeyTime = elapsedRealtime;
        buffer.add(keyCode);
    }

    /**
     * Clear the key-code buffer.  Typically called after a successful match.
     */
    public void clearBuffer() {
        buffer.clear();
    }

    /**
     * Upgrade the last buffer entry from a normal tap to a long-press variant
     * (negated key code) if it matches the given {@code keyCode}.
     */
    public void upgradeLongPress(int keyCode) {
        if (!buffer.isEmpty()) {
            int lastIdx = buffer.size() - 1;
            if (buffer.get(lastIdx) == keyCode) {
                buffer.set(lastIdx, -keyCode);
            }
        }
    }

    /**
     * Upgrade the last buffer entry to a hold variant (key physically down
     * when the next key arrives).  Handles both short press (kc) and
     * long-press (-kc) → hold (kc + HOLD_OFFSET).
     */
    public void upgradeToHold(int keyCode) {
        if (!buffer.isEmpty()) {
            int lastIdx = buffer.size() - 1;
            int last = buffer.get(lastIdx);
            if (last == keyCode || last == -keyCode) {
                buffer.set(lastIdx, keyCode + HOLD_OFFSET);
            }
        }
    }

    /**
     * Upgrade the last buffer entry from a single press to a double-press
     * variant if it matches the given {@code keyCode}.
     */
    public void upgradeDoublePress(int keyCode) {
        if (!buffer.isEmpty()) {
            int lastIdx = buffer.size() - 1;
            if (buffer.get(lastIdx) == keyCode) {
                buffer.set(lastIdx, keyCode + DOUBLE_PRESS_OFFSET);
            }
        }
    }

    /**
     * Upgrade the last buffer entry from a double-press to a triple-press
     * variant if it matches the given {@code keyCode}.
     */
    public void upgradeTriplePress(int keyCode) {
        if (!buffer.isEmpty()) {
            int lastIdx = buffer.size() - 1;
            if (buffer.get(lastIdx) == keyCode + DOUBLE_PRESS_OFFSET) {
                buffer.set(lastIdx, keyCode + TRIPLE_PRESS_OFFSET);
            }
        }
    }

    // ── Static helpers for encoded key codes ────────────────────────────

    /** Extract the raw Android keycode, stripping long/hold/double/triple encoding. */
    public static int baseKeyCode(int code) {
        if (code < 0) return -code;
        if (code >= HOLD_OFFSET) return code - HOLD_OFFSET;
        if (code >= TRIPLE_PRESS_OFFSET) return code - TRIPLE_PRESS_OFFSET;
        if (code >= DOUBLE_PRESS_OFFSET) return code - DOUBLE_PRESS_OFFSET;
        return code;
    }

    public static boolean isLongPress(int code)   { return code < 0; }
    public static boolean isHold(int code)         { return code >= HOLD_OFFSET; }
    public static boolean isDoublePress(int code)  { return code >= DOUBLE_PRESS_OFFSET && code < TRIPLE_PRESS_OFFSET; }
    public static boolean isTriplePress(int code)  { return code >= TRIPLE_PRESS_OFFSET && code < HOLD_OFFSET; }

    /**
     * @return an unmodifiable snapshot of the current key-code buffer.
     */
    public List<Integer> getBuffer() {
        return Collections.unmodifiableList(new ArrayList<>(buffer));
    }

    // ── Sequence matching ───────────────────────────────────────────────

    /**
     * Check whether {@code haystack} ends with the given {@code needle}
     * sequence.
     *
     * @param haystack the buffer of accumulated key-codes.
     * @param needle   the target hotkey sequence.
     * @return {@code true} if the last N elements of {@code haystack} equal
     *         {@code needle}, where N is {@code needle.size()}.
     */
    public static boolean endsWith(List<Integer> haystack, List<Integer> needle) {
        if (needle == null || needle.isEmpty()) return false;
        if (haystack.size() < needle.size()) return false;

        int offset = haystack.size() - needle.size();
        for (int i = 0; i < needle.size(); i++) {
            if (!haystack.get(offset + i).equals(needle.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convenience method: check whether the internal buffer ends with
     * {@code needle}.
     */
    public boolean bufferEndsWith(List<Integer> needle) {
        return endsWith(buffer, needle);
    }

    // ── Configuration retrieval ─────────────────────────────────────────

    /**
     * Load the current hotkey configuration from the database.
     *
     * @return a {@link HotkeyConfig} containing the Lokker hotkey and
     *         per-app hotkeys.
     */
    public HotkeyConfig getHotkeyConfig() {
        // Lokker-level hotkey
        List<Integer> lokkerHotkey = null;
        HotkeyMap map = hotkeyMapDao.get();
        if (map != null) {
            lokkerHotkey = map.lokkerHotkey;
        }

        // Per-app hotkeys
        Map<String, List<Integer>> appHotkeys = new HashMap<>();
        List<LokkerApp> apps = lokkerAppDao.getAll();
        if (apps != null) {
            for (LokkerApp app : apps) {
                if (app.hotkeySequence != null && !app.hotkeySequence.isEmpty()) {
                    appHotkeys.put(app.packageName, app.hotkeySequence);
                }
            }
        }

        return new HotkeyConfig(lokkerHotkey, appHotkeys);
    }

    // ── HotkeyConfig inner class ────────────────────────────────────────

    /**
     * Immutable snapshot of all configured hotkeys.
     */
    public static class HotkeyConfig {

        private final List<Integer> lokkerHotkey;
        private final Map<String, List<Integer>> appHotkeys;

        public HotkeyConfig(List<Integer> lokkerHotkey,
                            Map<String, List<Integer>> appHotkeys) {
            this.lokkerHotkey = lokkerHotkey;
            this.appHotkeys = appHotkeys != null
                    ? Collections.unmodifiableMap(appHotkeys)
                    : Collections.emptyMap();
        }

        /**
         * @return the hotkey sequence that opens Lokker itself, or {@code null}
         *         if not configured.
         */
        public List<Integer> getLokkerHotkey() {
            return lokkerHotkey;
        }

        /**
         * @return an unmodifiable map of package-name to hotkey sequence for
         *         per-app hotkeys.
         */
        public Map<String, List<Integer>> getAppHotkeys() {
            return appHotkeys;
        }
    }
}
