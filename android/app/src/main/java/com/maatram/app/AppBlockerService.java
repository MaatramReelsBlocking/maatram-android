package com.maatram.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Maatram Shield v2.
 *
 * Three jobs, in priority order:
 *
 *  1. HARD LOCK (unchanged): while a lock runs, blocked apps get bounced HOME.
 *  2. FRICTION GATE (new): with no lock running, opening a blocked app first
 *     shows FrictionActivity - an escalating toll + adaptive pause + a timed
 *     session. When the chosen time runs out the user is sent HOME.
 *  3. GENTLE REMINDERS (new): while any blocked app is in the foreground the
 *     service counts real usage and floats a small non-blocking bubble over
 *     the app every 10 minutes: "Instagram - 10 min today". Works even when
 *     no lock and no friction is active. Uses TYPE_ACCESSIBILITY_OVERLAY, so
 *     no extra permission is needed.
 */
public class AppBlockerService extends AccessibilityService {

    private static final String TAG = "MaatramShield";

    public static final String PREFS = "MaatramBlocker";
    public static final String KEY_LOCK_END = "lock_end";          // epoch ms
    public static final String KEY_WA_ALLOWED = "wa_allowed";      // boolean
    public static final String KEY_FRICTION_ON = "friction_on";    // boolean, default true
    public static final String KEY_POINTS_CACHE = "points_cache";  // long, last known PTS
    public static final String KEY_RECENT_OPENS = "recent_opens";  // JSON [ts,...] last hour
    public static final String KEY_PEND_EVENTS = "pend_events";    // JSON [...] drained by app
    public static final String KEY_LAST_SESS = "last_sess";        // JSON {app,pkg,ts} awaiting verdict
    public static final String KEY_VERDICTS = "verdicts";          // JSON [{app,worth,ts}] last 5
    private static final String KEY_SESS_PREFIX = "sess_";         // sess_<pkg> = end ms
    private static final String KEY_SESS_APP_PREFIX = "sessapp_";  // sessapp_<pkg> = label

    private static volatile boolean running = false;
    public static volatile String lastBlocked = "";

    public static boolean isRunning() { return running; }

    /** Social apps blocked during Hard Lock and gated by Friction. */
    private static final String[] BLOCKED = {
        "com.instagram.android",
        "com.instagram.lite",
        "com.instagram.barcelona",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.snapchat.android",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.youtube.mango",
        "com.google.android.youtube.tv",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.twitter.android",
        "com.reddit.frontpage"
    };

    /** Blocked during Hard Lock too - unless the user unlocks it in Maatram.
     *  NOT friction-gated: messages must stay reachable. */
    private static final String[] WHATSAPP = {
        "com.whatsapp",
        "com.whatsapp.w4b"
    };

    private static final Map<String, String> NAMES = new HashMap<String, String>();
    static {
        NAMES.put("com.instagram.android", "Instagram");
        NAMES.put("com.instagram.lite", "Instagram");
        NAMES.put("com.instagram.barcelona", "Threads");
        NAMES.put("com.zhiliaoapp.musically", "TikTok");
        NAMES.put("com.ss.android.ugc.trill", "TikTok");
        NAMES.put("com.snapchat.android", "Snapchat");
        NAMES.put("com.google.android.youtube", "YouTube");
        NAMES.put("com.google.android.apps.youtube.music", "YouTube Music");
        NAMES.put("com.google.android.apps.youtube.mango", "YouTube");
        NAMES.put("com.google.android.youtube.tv", "YouTube");
        NAMES.put("com.facebook.katana", "Facebook");
        NAMES.put("com.facebook.lite", "Facebook");
        NAMES.put("com.twitter.android", "X");
        NAMES.put("com.reddit.frontpage", "Reddit");
        NAMES.put("com.whatsapp", "WhatsApp");
        NAMES.put("com.whatsapp.w4b", "WhatsApp");
    }

    public static String labelFor(String pkg) {
        String n = NAMES.get(pkg);
        return n != null ? n : pkg;
    }

    private static boolean isBlocked(String pkg) {
        for (String b : BLOCKED) if (b.equals(pkg)) return true;
        return false;
    }

    private static boolean isWhatsapp(String pkg) {
        for (String w : WHATSAPP) if (w.equals(pkg)) return true;
        return false;
    }

    /* ============== shared state helpers (also used by FrictionActivity + plugin) ============== */

    public static String dayKey() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static JSONObject jsonPref(SharedPreferences p, String key) {
        try { return new JSONObject(p.getString(key, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static JSONArray jsonArrPref(SharedPreferences p, String key) {
        try { return new JSONArray(p.getString(key, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    /** ms spent in pkg today. */
    public static long usageTodayMs(SharedPreferences p, String pkg) {
        return jsonPref(p, "usage_" + dayKey()).optLong(pkg, 0L);
    }

    /** Full usage map for today: {pkg: ms}. */
    public static JSONObject usageToday(SharedPreferences p) {
        return jsonPref(p, "usage_" + dayKey());
    }

    private static void addUsage(SharedPreferences p, String pkg, long ms) {
        String key = "usage_" + dayKey();
        JSONObject o = jsonPref(p, key);
        try { o.put(pkg, o.optLong(pkg, 0L) + ms); } catch (Exception ignored) { }
        p.edit().putString(key, o.toString()).apply();
    }

    /** Approved gate entries into pkg today. */
    public static int opensToday(SharedPreferences p, String pkg) {
        return jsonPref(p, "opens_" + dayKey()).optInt(pkg, 0);
    }

    public static JSONObject opensTodayAll(SharedPreferences p) {
        return jsonPref(p, "opens_" + dayKey());
    }

    /** Escalating toll: -5, -15, -30, -50 by opens already made today. */
    public static int tollFor(int opensSoFar) {
        int[] t = {5, 15, 30, 50};
        return t[Math.min(Math.max(opensSoFar, 0), 3)];
    }

    /** Adaptive pause: 8s, doubled per approved open in the last hour (cap 32s). */
    public static int dwellSecNow(SharedPreferences p) {
        long now = System.currentTimeMillis();
        JSONArray a = jsonArrPref(p, KEY_RECENT_OPENS);
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            if (now - a.optLong(i, 0L) < 3600000L) n++;
        }
        return 8 << Math.min(n, 2);
    }

    /** Counts an approved entry (toll tier + dwell escalation feed off this). */
    public static void recordOpen(SharedPreferences p, String pkg, long now) {
        String key = "opens_" + dayKey();
        JSONObject o = jsonPref(p, key);
        try { o.put(pkg, o.optInt(pkg, 0) + 1); } catch (Exception ignored) { }
        JSONArray a = jsonArrPref(p, KEY_RECENT_OPENS);
        JSONArray keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            long ts = a.optLong(i, 0L);
            if (now - ts < 3600000L) keep.put(ts);
        }
        keep.put(now);
        p.edit().putString(key, o.toString())
                .putString(KEY_RECENT_OPENS, keep.toString())
                .commit();
    }

    /** Opens a timed session: the app is allowed until endMs. */
    public static void startSession(SharedPreferences p, String pkg, String app, long endMs) {
        p.edit().putLong(KEY_SESS_PREFIX + pkg, endMs)
                .putString(KEY_SESS_APP_PREFIX + pkg, app)
                .commit();
    }

    public static long sessionEnd(SharedPreferences p, String pkg) {
        return p.getLong(KEY_SESS_PREFIX + pkg, 0L);
    }

    private static void endSession(SharedPreferences p, String pkg, boolean askVerdict) {
        String app = p.getString(KEY_SESS_APP_PREFIX + pkg, labelFor(pkg));
        SharedPreferences.Editor e = p.edit()
            .remove(KEY_SESS_PREFIX + pkg)
            .remove(KEY_SESS_APP_PREFIX + pkg);
        if (askVerdict) {
            try {
                JSONObject last = new JSONObject();
                last.put("app", app);
                last.put("pkg", pkg);
                last.put("ts", System.currentTimeMillis());
                e.putString(KEY_LAST_SESS, last.toString());
            } catch (Exception ignored) { }
        }
        e.commit();
    }

    /** Verdict on the pending session; keeps the last 5. */
    public static void recordVerdict(SharedPreferences p, boolean worthIt) {
        String pend = p.getString(KEY_LAST_SESS, "");
        String app = "";
        try { if (pend.length() > 2) app = new JSONObject(pend).optString("app", ""); }
        catch (Exception ignored) { }
        JSONArray v = jsonArrPref(p, KEY_VERDICTS);
        try {
            JSONObject o = new JSONObject();
            o.put("app", app);
            o.put("worth", worthIt);
            o.put("ts", System.currentTimeMillis());
            v.put(o);
        } catch (Exception ignored) { }
        while (v.length() > 5) v.remove(0);
        p.edit().putString(KEY_VERDICTS, v.toString())
                .remove(KEY_LAST_SESS)
                .commit();
    }

    /** Queues an event for the main app to drain (tolls become real PTS there). */
    public static void queueEvent(SharedPreferences p, String type, String app, int val, String why, long ts) {
        JSONArray a = jsonArrPref(p, KEY_PEND_EVENTS);
        try {
            JSONObject o = new JSONObject();
            o.put("t", type);
            o.put("app", app);
            o.put("val", val);
            if (why != null && why.length() > 0) o.put("why", why);
            o.put("ts", ts);
            a.put(o);
        } catch (Exception ignored) { }
        while (a.length() > 100) a.remove(0);
        p.edit().putString(KEY_PEND_EVENTS, a.toString()).commit();
    }

    /* ============== service lifecycle ============== */

    private long lastToast = 0;
    private long lastSessToast = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Foreground tracking. */
    private String fgPkg = "";
    private long fgSince = 0L;
    private long lastGateLaunch = 0L;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            ui.postDelayed(this, 15000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50;
        info.packageNames = null;
        setServiceInfo(info);

        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 15000);
        Log.i(TAG, "Shield v2 connected");
        toast("Maatram Shield active");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        ui.removeCallbacks(ticker);
        Log.i(TAG, "Shield disconnected");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacks(ticker);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
         && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        CharSequence cs = event.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();

        // Some overlays (keyboard, system UI) fire events without a real app switch.
        if (pkg.equals("com.android.systemui") || pkg.startsWith("com.google.android.inputmethod")) return;

        trackForeground(pkg);

        if (pkg.equals(getPackageName())) return;   // never bounce ourselves

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();

        /* -- 1. HARD LOCK: absolute, unchanged -- */
        long lockEnd = p.getLong(KEY_LOCK_END, 0L);
        if (now < lockEnd) {
            boolean block = isBlocked(pkg)
                || (isWhatsapp(pkg) && !p.getBoolean(KEY_WA_ALLOWED, false));
            if (block) {
                lastBlocked = pkg;
                Log.i(TAG, "HardLock blocking " + pkg);
                bounceHome();
                if (now - lastToast > 2500) {
                    lastToast = now;
                    final long minLeft = Math.max(1, (lockEnd - now) / 60000);
                    toast("Hard Lock active - blocked by Maatram (" + minLeft + " min left)");
                }
            }
            return;
        }

        /* -- 2. FRICTION GATE: no lock, blocked app, no live session -- */
        if (!isBlocked(pkg)) return;                          // WhatsApp is never gated
        if (!p.getBoolean(KEY_FRICTION_ON, true)) return;
        long sEnd = sessionEnd(p, pkg);
        if (now < sEnd) {
            // Approved session running: the gate-free window is intentional -
            // say so, or it looks like the gate silently broke (v8 report).
            if (now - lastSessToast > 10000) {
                lastSessToast = now;
                long minLeft = Math.max(1, (sEnd - now + 59999) / 60000);
                toast(labelFor(pkg) + " session - " + minLeft + " min left (Maatram)");
            }
            return;
        }
        if (now - lastGateLaunch < 1500) return;              // event-storm guard
        // (if a gate is already up, singleTop just brings it back to front)
        lastGateLaunch = now;

        // A stale session key (expired while phone slept) gets a verdict slot.
        if (sessionEnd(p, pkg) > 0) endSession(p, pkg, true);

        lastBlocked = pkg;
        Log.i(TAG, "Friction gating " + pkg);
        try {
            Intent gate = new Intent(this, FrictionActivity.class);
            gate.putExtra(FrictionActivity.EXTRA_PKG, pkg);
            gate.putExtra(FrictionActivity.EXTRA_APP, labelFor(pkg));
            gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(gate);
        } catch (Exception e) {
            Log.w(TAG, "gate launch failed", e);
        }
    }

    @Override
    public void onInterrupt() { }

    /* ============== usage tracking + timed sessions + reminders ============== */

    private void trackForeground(String pkg) {
        if (pkg.equals(fgPkg)) return;
        long now = System.currentTimeMillis();
        // close the previous stint
        if (fgSince > 0 && isBlocked(fgPkg)) {
            long ms = now - fgSince;
            if (ms > 0 && ms < 6 * 3600000L) {   // sanity cap: ignore clock jumps
                addUsage(getSharedPreferences(PREFS, MODE_PRIVATE), fgPkg, ms);
            }
        }
        fgPkg = pkg;
        fgSince = now;
    }

    /** Every 15s: accumulate live usage, fire reminders, enforce session ends. */
    private void tick() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();

        /* live usage for the app currently in front (only while screen is on) */
        boolean screenOn = true;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) screenOn = pm.isInteractive();
        } catch (Exception ignored) { }
        if (!screenOn) { fgSince = now; }
        if (screenOn && fgSince > 0 && isBlocked(fgPkg)) {
            long ms = now - fgSince;
            if (ms > 0 && ms < 6 * 3600000L) addUsage(p, fgPkg, ms);
            fgSince = now;

            /* gentle reminder every 10 full minutes of today's usage */
            long totalMin = usageTodayMs(p, fgPkg) / 60000L;
            long threshold = (totalMin / 10) * 10;
            if (threshold >= 10) {
                String rk = "remind_" + dayKey();
                JSONObject r = jsonPref(p, rk);
                if (r.optLong(fgPkg, 0L) < threshold) {
                    try { r.put(fgPkg, threshold); } catch (Exception ignored) { }
                    p.edit().putString(rk, r.toString()).apply();
                    showReminder(labelFor(fgPkg) + " - " + threshold + " min today");
                }
            }
        }

        /* expire any finished sessions */
        Map<String, ?> all = p.getAll();
        Iterator<String> it = all.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (!k.startsWith(KEY_SESS_PREFIX)) continue;
            Object v = all.get(k);
            if (!(v instanceof Long)) continue;
            long end = (Long) v;
            if (end <= 0 || now < end) continue;
            String pkg = k.substring(KEY_SESS_PREFIX.length());
            String app = labelFor(pkg);
            endSession(p, pkg, true);
            Log.i(TAG, "session over for " + pkg);
            if (pkg.equals(fgPkg)) {
                bounceHome();
                toast("Maatram: your " + app + " time is up");
            }
        }
    }

    private void bounceHome() {
        boolean done = performGlobalAction(GLOBAL_ACTION_HOME);
        if (!done) {
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(home);
            } catch (Exception e) {
                Log.w(TAG, "home intent failed", e);
            }
        }
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(AppBlockerService.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /* -- the floating usage bubble - TYPE_ACCESSIBILITY_OVERLAY needs no permission -- */
    private void showReminder(final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm == null) { toast(text); return; }

                    final TextView tv = new TextView(AppBlockerService.this);
                    tv.setText("\uD83C\uDF31 " + text);
                    tv.setTextColor(Color.parseColor("#04120F"));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
                    int padH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18, getResources().getDisplayMetrics());
                    int padV = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 11, getResources().getDisplayMetrics());
                    tv.setPadding(padH, padV, padH, padV);

                    GradientDrawable bg = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#2FE38F"), Color.parseColor("#37B6FF")});
                    bg.setCornerRadius(999f);
                    tv.setBackground(bg);

                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    lp.y = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56, getResources().getDisplayMetrics());

                    wm.addView(tv, lp);
                    ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            try { wm.removeView(tv); } catch (Exception ignored) { }
                        }
                    }, 4500);
                } catch (Exception e) {
                    Log.w(TAG, "reminder overlay failed, falling back to toast", e);
                    toast(text);
                }
            }
        });
    }
}
