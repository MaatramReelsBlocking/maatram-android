package com.maatram.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.view.WindowCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Custom plugins must be registered BEFORE super.onCreate().
        registerPlugin(AppBlockerPlugin.class);
        super.onCreate(savedInstanceState);

        // Google sign-in hand-back (cold start case).
        handleAuthIntent(getIntent());

        // Draw behind the status bar and the gesture bar.
        // The web layout already reserves space via env(safe-area-inset-*).
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // Light icons on our dark background.
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        // -- Fix "page is zoomed in / can't zoom out" on phones --
        // 1) Kill every zoom path in the WebView.
        // 2) textZoom 100: the phone's system Font-Size setting otherwise scales
        //    WebView text (110-150%) - text grows past its boxes and everything
        //    overlaps. Laptops never see this, phones do.
        WebView wv = getBridge().getWebView();
        WebSettings s = wv.getSettings();
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
    }

    /* ============== Google sign-in hand-back ==============
       The WebView cannot finish Google sign-in itself: Google refuses to run
       its account chooser inside an embedded WebView, and the popup we used
       before opened in Chrome, which had no way to hand the account back -
       that is the white screen the user saw. Now the browser finishes on the
       Maatram website and returns here via maatram://auth?idt=<Google id
       token>. We only park the token; the web layer swaps it for a Firebase
       session with signInWithCredential(). */

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthIntent(intent);
    }

    private void handleAuthIntent(Intent intent) {
        if (intent == null) return;
        Uri u = intent.getData();
        if (u == null) return;
        if (!"maatram".equals(u.getScheme())) return;
        String idt = null;
        try { idt = u.getQueryParameter("idt"); } catch (Exception e) { return; }
        if (idt == null || idt.length() < 20) return;
        SharedPreferences p = getSharedPreferences(AppBlockerService.PREFS, Context.MODE_PRIVATE);
        p.edit().putString(AppBlockerPlugin.KEY_AUTH_IDT, idt).commit();
    }
}
