package com.maatram.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Friction Gate screen.
 *
 * Launched by AppBlockerService (own task, over the social app) whenever a
 * blocked app comes to the foreground with no Hard Lock running and Friction
 * mode ON. Hosts www/friction.html in a bare WebView with a small JS bridge.
 * The page is fully offline (no CDN), so the gate works in airplane mode.
 *
 * approve() -> starts a timed session (grace) and reveals the app underneath.
 * deny()    -> sends the user HOME.
 */
public class FrictionActivity extends Activity {

    private static final String TAG = "MaatramGate";

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_APP = "app";

    /** Lets the service know a gate is already on screen (and for which app). */
    public static volatile boolean showing = false;
    public static volatile String showingPkg = "";

    private String pkg = "";
    private String appName = "this app";
    private WebView wv;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showing = true;

        Intent it = getIntent();
        if (it != null) {
            String p = it.getStringExtra(EXTRA_PKG);
            String a = it.getStringExtra(EXTRA_APP);
            if (p != null) pkg = p;
            if (a != null) appName = a;
        }
        showingPkg = pkg;

        wv = new WebView(this);
        wv.setBackgroundColor(Color.parseColor("#04120F"));
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        wv.addJavascriptInterface(new GateBridge(), "MaatramGate");
        setContentView(wv);
        wv.loadUrl("file:///android_asset/public/friction.html");
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        // Re-fronted. Only reload for a DIFFERENT app - reloading for the same
        // one restarted the dwell countdown every time the service re-launched
        // us (v8 bug: the wait never finished).
        String newPkg = pkg;
        String newApp = appName;
        if (it != null) {
            String p = it.getStringExtra(EXTRA_PKG);
            String a = it.getStringExtra(EXTRA_APP);
            if (p != null) newPkg = p;
            if (a != null) newApp = a;
        }
        if (newPkg.equals(pkg) && wv != null) return;   // same app: keep the ring running
        pkg = newPkg;
        appName = newApp;
        showingPkg = pkg;
        if (wv != null) wv.loadUrl("file:///android_asset/public/friction.html");
    }

    @Override
    protected void onDestroy() {
        showing = false;
        showingPkg = "";
        if (wv != null) {
            try { wv.destroy(); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Back = "never mind": same as tapping Leave.
        goHomeAndClose();
    }

    private void goHomeAndClose() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(home);
        } catch (Exception e) {
            Log.w(TAG, "home intent failed", e);
        }
        finishAndRemoveTask();
    }

    /* ============== the JS bridge ============== */
    private class GateBridge {

        private SharedPreferences prefs() {
            return getSharedPreferences(AppBlockerService.PREFS, MODE_PRIVATE);
        }

        /** Everything friction.html needs, in one JSON blob. */
        @JavascriptInterface
        public String getState() {
            SharedPreferences p = prefs();
            JSONObject out = new JSONObject();
            try {
                int opens = AppBlockerService.opensToday(p, pkg);
                out.put("app", appName);
                out.put("pkg", pkg);
                out.put("usageMin", AppBlockerService.usageTodayMs(p, pkg) / 60000L);
                out.put("opensToday", opens);
                out.put("tier", Math.min(opens + 1, 4));
                out.put("toll", AppBlockerService.tollFor(opens));
                out.put("dwellSec", AppBlockerService.dwellSecNow(p));
                long pts = p.getLong(AppBlockerService.KEY_POINTS_CACHE, -1L);
                if (pts >= 0) out.put("points", pts);

                String pend = p.getString(AppBlockerService.KEY_LAST_SESS, "");
                if (pend != null && pend.length() > 2) {
                    out.put("pendingRegret", new JSONObject(pend));
                }
                String v = p.getString(AppBlockerService.KEY_VERDICTS, "[]");
                out.put("verdicts", new JSONArray(v));
            } catch (Exception e) {
                Log.w(TAG, "getState", e);
            }
            return out.toString();
        }

        /** User paid the toll: open a timed session and reveal the app. */
        @JavascriptInterface
        public void approve(final int minutes, final int toll, final String why) {
            final int mins = Math.max(1, Math.min(minutes, 30));
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    SharedPreferences p = prefs();
                    long now = System.currentTimeMillis();
                    AppBlockerService.startSession(p, pkg, appName, now + mins * 60000L);
                    AppBlockerService.recordOpen(p, pkg, now);
                    AppBlockerService.queueEvent(p, "toll", appName, -Math.abs(toll), why, now);
                    // Local points cache goes down too, so "broke" checks stay honest
                    long pts = p.getLong(AppBlockerService.KEY_POINTS_CACHE, -1L);
                    if (pts >= 0) {
                        p.edit().putLong(AppBlockerService.KEY_POINTS_CACHE,
                                Math.max(0, pts - Math.abs(toll))).commit();
                    }
                    Log.i(TAG, "approved " + pkg + " for " + mins + " min, toll " + toll);
                    // The social app is directly underneath this task.
                    finishAndRemoveTask();
                }
            });
        }

        /** User backed out: home screen, no toll. */
        @JavascriptInterface
        public void deny() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    AppBlockerService.queueEvent(prefs(), "walkaway", appName, 0, "", System.currentTimeMillis());
                    goHomeAndClose();
                }
            });
        }

        /** Verdict on the PREVIOUS session (regret receipt). */
        @JavascriptInterface
        public void regret(final boolean worthIt) {
            SharedPreferences p = prefs();
            AppBlockerService.recordVerdict(p, worthIt);
            AppBlockerService.queueEvent(p, "verdict", appName, worthIt ? 1 : 0, "", System.currentTimeMillis());
        }
    }
}
