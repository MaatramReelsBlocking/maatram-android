package com.maatram.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JS-facing bridge for the Hard Lock app blocker.
 *
 * WHY "until" IS A STRING
 * Epoch milliseconds (~1.75e12) are larger than Integer.MAX_VALUE, so org.json
 * parses them as a Long. Capacitor's PluginCall.getDouble() only handles
 * Double / Float / Integer and returns null for Long - so setLock() was being
 * rejected and the lock silently never armed. We take the timestamp as a
 * string and parse it ourselves. getLongArg() also accepts a raw number of
 * any type as a fallback.
 */
@CapacitorPlugin(name = "AppBlocker")
public class AppBlockerPlugin extends Plugin {

    /** Where MainActivity parks the Google id token from maatram://auth. */
    public static final String KEY_AUTH_IDT = "auth_idt";

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(AppBlockerService.PREFS, Context.MODE_PRIVATE);
    }

    /** Reads a millisecond timestamp no matter how JS / org.json typed it. */
    private long getLongArg(PluginCall call, String key) {
        Object raw = call.getData().opt(key);
        if (raw == null) return 0L;
        if (raw instanceof Number) return ((Number) raw).longValue();
        try { return Long.parseLong(raw.toString().trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

    @PluginMethod
    public void setLock(PluginCall call) {
        long until = getLongArg(call, "until");
        if (until <= 0) { call.reject("until (epoch ms, as a string) required"); return; }
        boolean wa = Boolean.TRUE.equals(call.getBoolean("whatsappAllowed", false));

        prefs().edit()
            .putLong(AppBlockerService.KEY_LOCK_END, until)
            .putBoolean(AppBlockerService.KEY_WA_ALLOWED, wa)
            .commit();   // commit, not apply - the service may read it immediately

        JSObject out = new JSObject();
        out.put("until", String.valueOf(until));
        out.put("msLeft", String.valueOf(until - System.currentTimeMillis()));
        call.resolve(out);
    }

    @PluginMethod
    public void clearLock(PluginCall call) {
        prefs().edit()
            .putLong(AppBlockerService.KEY_LOCK_END, 0L)
            .putBoolean(AppBlockerService.KEY_WA_ALLOWED, false)
            .commit();
        call.resolve();
    }

    @PluginMethod
    public void setWhatsappAllowed(PluginCall call) {
        boolean allowed = Boolean.TRUE.equals(call.getBoolean("allowed", false));
        prefs().edit().putBoolean(AppBlockerService.KEY_WA_ALLOWED, allowed).commit();
        call.resolve();
    }

    private boolean serviceEnabled() {
        String pkg = getContext().getPackageName();
        String a = pkg + "/" + AppBlockerService.class.getName();   // fully qualified
        String b = pkg + "/.AppBlockerService";                     // short form, some OEMs
        String enabled = Settings.Secure.getString(
            getContext().getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        for (String s : enabled.split(":")) {
            s = s.trim();
            if (s.equalsIgnoreCase(a) || s.equalsIgnoreCase(b)) return true;
        }
        return false;
    }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject out = new JSObject();
        out.put("enabled", serviceEnabled());
        call.resolve(out);
    }

    /** Everything the UI needs to show the true native state - makes bugs visible. */
    @PluginMethod
    public void getStatus(PluginCall call) {
        SharedPreferences p = prefs();
        long end = p.getLong(AppBlockerService.KEY_LOCK_END, 0L);
        JSObject out = new JSObject();
        out.put("enabled", serviceEnabled());
        out.put("serviceRunning", AppBlockerService.isRunning());
        out.put("lockEnd", String.valueOf(end));
        out.put("locked", end > System.currentTimeMillis());
        out.put("secondsLeft", String.valueOf(Math.max(0, (end - System.currentTimeMillis()) / 1000)));
        out.put("whatsappAllowed", p.getBoolean(AppBlockerService.KEY_WA_ALLOWED, false));
        out.put("lastBlocked", AppBlockerService.lastBlocked);
        call.resolve(out);
    }

    /* ============== Friction Gate (v7) ============== */

    @PluginMethod
    public void setFrictionEnabled(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        prefs().edit().putBoolean(AppBlockerService.KEY_FRICTION_ON, on).commit();
        JSObject out = new JSObject();
        out.put("on", on);
        call.resolve(out);
    }

    /** The web app pushes its last-known PTS balance so the native gate can
     *  refuse entry when the user cannot afford the toll. Sent as a STRING
     *  for the same Long-vs-getDouble reason as setLock. */
    @PluginMethod
    public void syncPoints(PluginCall call) {
        long pts = getLongArg(call, "points");
        if (pts < 0) pts = 0;
        prefs().edit().putLong(AppBlockerService.KEY_POINTS_CACHE, pts).commit();
        call.resolve();
    }

    /** Returns queued native gate events (tolls, verdicts, walkaways) and
     *  clears the queue. The web side turns tolls into real PTS writes. */
    @PluginMethod
    public void drainEvents(PluginCall call) {
        SharedPreferences p = prefs();
        String raw = p.getString(AppBlockerService.KEY_PEND_EVENTS, "[]");
        p.edit().putString(AppBlockerService.KEY_PEND_EVENTS, "[]").commit();
        JSObject out = new JSObject();
        out.put("events", raw);   // JSON string; JS parses it
        call.resolve(out);
    }

    /** Friction + usage snapshot for the UI (App Gate card, Stats page). */
    @PluginMethod
    public void getFrictionState(PluginCall call) {
        SharedPreferences p = prefs();
        JSObject out = new JSObject();
        out.put("frictionOn", p.getBoolean(AppBlockerService.KEY_FRICTION_ON, true));
        out.put("usageToday", AppBlockerService.usageToday(p).toString());
        out.put("opensToday", AppBlockerService.opensTodayAll(p).toString());
        out.put("verdicts", p.getString(AppBlockerService.KEY_VERDICTS, "[]"));
        out.put("dwellSec", AppBlockerService.dwellSecNow(p));
        out.put("pointsCache", String.valueOf(p.getLong(AppBlockerService.KEY_POINTS_CACHE, -1L)));
        call.resolve(out);
    }

    /* ============== Google sign-in helpers (v10) ============== */

    /** Opens a URL in the real browser. Google blocks its account chooser
     *  inside embedded WebViews, so sign-in must leave the app. */
    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url", "");
        if (url == null || url.trim().length() == 0) { call.reject("url required"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("no browser available");
        }
    }

    /** Returns the Google id token handed back by the browser, once, then
     *  clears it. Empty string means nothing is waiting. */
    @PluginMethod
    public void consumeAuthToken(PluginCall call) {
        SharedPreferences p = prefs();
        String t = p.getString(KEY_AUTH_IDT, "");
        if (t == null) t = "";
        if (t.length() > 0) p.edit().remove(KEY_AUTH_IDT).commit();
        JSObject out = new JSObject();
        out.put("idToken", t);
        call.resolve(out);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }
}
