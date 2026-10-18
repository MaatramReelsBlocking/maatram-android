/* ══ Maatram — native shell glue (Capacitor) ══
   Loaded on every page. Does nothing in a plain browser. */
(function () {
  var C = window.Capacitor;
  if (!C || !C.isNativePlatform || !C.isNativePlatform()) return;

  var PLAT = C.getPlatform ? C.getPlatform() : 'android';
  var IOS = PLAT === 'ios';
  document.documentElement.classList.add('native', PLAT);
  var P = C.Plugins || {};

  /* ── status bar: transparent, light icons ── */
  try {
    if (P.StatusBar) {
      if (!IOS) P.StatusBar.setOverlaysWebView({ overlay: true });   // iOS always draws under it
      P.StatusBar.setStyle({ style: 'DARK' });   // DARK style = light icons
    }
  } catch (e) {}

  /* ── splash: dismiss once the page has actually painted ── */
  try {
    if (P.SplashScreen) requestAnimationFrame(function () {
      setTimeout(function () { P.SplashScreen.hide(); }, 120);
    });
  } catch (e) {}

  /* ── hardware back button ──
     Bottom sheet open  -> close it.
     Power-up open      -> close it.
     Feature menu open  -> back to home screen.
     Home page          -> exit the app.
     Anywhere else      -> go to the previous page. */
  try {
    if (P.App) P.App.addListener('backButton', function (e) {
      var sheet = document.getElementById('mtSheet');
      if (sheet && sheet.classList.contains('on')) {
        window.mtCloseSheet && window.mtCloseSheet();
        return;
      }
      var pu = document.getElementById('pu');
      if (pu && pu.classList.contains('on')) {
        var skip = document.getElementById('pu-skip');
        if (skip) { skip.click(); return; }
      }
      var menu = document.getElementById('menu');
      if (menu && menu.classList.contains('show')) {
        var back = document.getElementById('menuBack');
        if (back) { back.click(); return; }
      }
      var onHome = /(^|\/)index\.html$/.test(location.pathname) || /\/$/.test(location.pathname);
      if (onHome) { P.App.exitApp(); return; }
      if (e && e.canGoBack) window.history.back();
      else location.href = 'index.html';
    });
  } catch (e) {}

  /* ── haptics: route navigator.vibrate through the native plugin,
        which is far more reliable than the WebView implementation ── */
  try {
    if (P.Haptics) {
      var orig = navigator.vibrate ? navigator.vibrate.bind(navigator) : null;
      navigator.vibrate = function (pattern) {
        try {
          var total = Array.isArray(pattern)
            ? pattern.reduce(function (a, b) { return a + b; }, 0)
            : pattern;
          if (total > 200)      P.Haptics.notification({ type: 'SUCCESS' });
          else if (total > 25)  P.Haptics.impact({ style: 'MEDIUM' });
          else                  P.Haptics.impact({ style: 'LIGHT' });
        } catch (_) { if (orig) orig(pattern); }
        return true;
      };
    }
  } catch (e) {}

  /* ── keyboard: hide the tab bar while typing ── */
  try {
    if (P.Keyboard) {
      var tabs = function () { return document.getElementById('mtabs'); };
      P.Keyboard.addListener('keyboardWillShow', function () {
        var t = tabs(); if (t) t.style.display = 'none';
      });
      P.Keyboard.addListener('keyboardWillHide', function () {
        var t = tabs(); if (t) t.style.display = '';
      });
    }
  } catch (e) {}
  /* ══════════════ Google sign-in (native) ══════════════
     Google refuses to show its account chooser inside an embedded WebView,
     so the old signInWithPopup() escaped into Chrome and had no way back —
     that was the white screen. New flow:

       app  -> opens https://<site>/auth-bridge.html in the real browser
       page -> user picks their Google account there
       page -> returns to  maatram://auth?idt=<Google id token>
       app  -> signInWithCredential(idToken)  => normal Firebase session

     Lives in native.js (not login.html) so a token still lands even if the
     deep link cold-starts the app on the home page. */
  var MT_SITE   = 'https://maatram-website.vercel.app';
  var MT_BRIDGE = MT_SITE + '/auth-bridge.html';
  var MT_RETURN = 'maatram://auth';
  var FBC = {
    apiKey: "AIzaSyAjiAm61IkH3wB1tjwOyGRrXAuRMKQyCcQ",
    authDomain: "maatram-859f4.firebaseapp.com",
    projectId: "maatram-859f4",
    storageBucket: "maatram-859f4.firebasestorage.app",
    messagingSenderId: "770970784123",
    appId: "1:770970784123:web:7c73c74ddb2179b69dedde"
  };
  var mtBusy = false;

  function mtNote(msg, bad) {
    var el = document.getElementById('status');
    if (el) { el.className = bad ? 'err' : ''; el.textContent = msg; return; }
    try { console.log('[Maatram auth] ' + msg); } catch (e) {}
  }

  async function mtSignInWithIdToken(idToken) {
    if (mtBusy) return false;
    mtBusy = true;
    try {
      mtNote('Finishing sign-in\u2026');
      var mods = await Promise.all([
        import('https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js'),
        import('https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js')
      ]);
      var A = mods[0], U = mods[1];
      var app = A.getApps().length ? A.getApp() : A.initializeApp(FBC);
      var auth = U.getAuth(app);
      await U.signInWithCredential(auth, U.GoogleAuthProvider.credential(idToken));
      mtNote('Signed in \u2713');
      document.dispatchEvent(new CustomEvent('maatram-signed-in'));
      return true;
    } catch (e) {
      mtNote('Sign-in failed: ' + (e && (e.code || e.message) || e), true);
      try { console.warn('[Maatram auth]', e); } catch (_) {}
      return false;
    } finally { mtBusy = false; }
  }

  /* Picks up a token parked by MainActivity, if any. */
  async function mtConsume() {
    try {
      var ab = (window.Capacitor && window.Capacitor.Plugins || {}).AppBlocker;
      if (!ab || !ab.consumeAuthToken) return false;
      var r = await ab.consumeAuthToken();
      var idt = r && r.idToken;
      if (!idt || idt.length < 20) return false;
      return await mtSignInWithIdToken(idt);
    } catch (e) { return false; }
  }

  async function mtStartSignIn() {
    var ab = (window.Capacitor && window.Capacitor.Plugins || {}).AppBlocker;
    var url = MT_BRIDGE + '?rt=' + encodeURIComponent(MT_RETURN) + '&t=' + Date.now();
    mtNote('Opening Google sign-in in your browser\u2026');
    try {
      if (ab && ab.openUrl) await ab.openUrl({ url: url });
      else window.open(url, '_blank');
    } catch (e) {
      mtNote('Could not open the browser. Open ' + MT_BRIDGE + ' manually.', true);
      return;
    }
    /* Poll while the user is away; the deep link brings the app forward and
       fires visibilitychange, but poll anyway for slow OEM resumes. */
    var tries = 0;
    var iv = setInterval(async function () {
      tries++;
      if (tries > 150 || await mtConsume()) clearInterval(iv);
    }, 1200);
  }

  /* iOS: no native plugin. Safari hands back maatram://auth?idt=...; read it
     straight from the URL the app was opened with (running or cold start). */
  function mtFromUrl(u) {
    try {
      var idt = new URL(u).searchParams.get('idt');
      if (idt && idt.length >= 20) mtSignInWithIdToken(idt);
    } catch (e) {}
  }
  if (IOS && P.App) {
    P.App.addListener('appUrlOpen', function (ev) { if (ev && ev.url) mtFromUrl(ev.url); });
    try { P.App.getLaunchUrl().then(function (r) { if (r && r.url) mtFromUrl(r.url); }); } catch (e) {}
  }

  window.MaatramAuth = {
    start:    mtStartSignIn,
    consume:  mtConsume,
    useToken: mtSignInWithIdToken,
    bridge:   MT_BRIDGE
  };

  mtConsume();
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) mtConsume();
  });
  try { if (P.App) P.App.addListener('appStateChange', function (st) { if (st && st.isActive) mtConsume(); }); } catch (e) {}
  try { if (P.App) P.App.addListener('appUrlOpen', function () { setTimeout(mtConsume, 60); }); } catch (e) {}
})();
