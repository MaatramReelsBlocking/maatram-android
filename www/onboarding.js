/* ══ Maatram · first-time onboarding ══
   Shown once per device on the app pages (timers, app-gate, study-room, stats, leaderboard).
   Stored only in this browser: localStorage key maatram_onboard_v1 = { state, goal, at }.
   Nothing is written to Firebase, so guests, sign-in and firestore.rules are not affected.

   Reopen it any time:  timers.html?tour=1   (or window.maatramOnboard.open())

   Blocking is never reported as active from here. The only thing that can confirm
   Shield is running is the native bridge (Capacitor plugin getStatus). When the bridge is
   missing, the step says it could not verify and points to the setup guide. */
(function () {
  'use strict';
  var KEY = 'maatram_onboard_v1';

  function read() { try { return JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { return null; } }
  function save(state, goal) {
    try { localStorage.setItem(KEY, JSON.stringify({ state: state, goal: goal || null, at: Date.now() })); } catch (e) {}
  }
  function seen() { var s = read(); return !!(s && (s.state === 'done' || s.state === 'skipped')); }

  var GOALS = [
    { k: 'study',  label: 'Study without distractions', hint: 'Focus timer and Study Rooms' },
    { k: 'scroll', label: 'Stop doomscrolling',         hint: 'App Gate and Hard Lock' },
    { k: 'sleep',  label: 'Sleep better',               hint: 'Hard Lock before bed' },
    { k: 'routine', label: 'Build a daily routine',     hint: 'Timers and streaks' }
  ];

  var CSS =
    '#mob{position:fixed;inset:0;z-index:2147483000;margin:auto;padding:0;border:0;background:transparent;' +
    'width:100%;max-width:100%;height:100%;max-height:100%;color:#e6f0ed;font:400 15px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}' +
    '#mob::backdrop{background:rgba(4,7,8,.86);backdrop-filter:blur(6px)}' +
    '#mob .card{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:min(440px,calc(100vw - 32px));' +
    'max-height:calc(100dvh - 32px);overflow:auto;background:#0d1417;border:1px solid rgba(255,255,255,.1);border-radius:20px;' +
    'padding:24px 22px 20px;box-shadow:0 24px 64px rgba(0,0,0,.6);animation:mobIn .45s cubic-bezier(.2,.8,.2,1)}' +
    '@keyframes mobIn{from{opacity:0;transform:translate(-50%,-44%) scale(.97)}to{opacity:1;transform:translate(-50%,-50%) scale(1)}}' +
    '#mob .dots{display:flex;gap:6px;justify-content:center;margin:0 0 16px}' +
    '#mob .dots i{width:8px;height:8px;border-radius:50%;background:rgba(255,255,255,.18);transition:background .3s,transform .3s}' +
    '#mob .dots i.on{background:#2fe38f;transform:scale(1.25)}' +
    '#mob .logo{font-weight:800;letter-spacing:.14em;text-align:center;margin:0 0 6px;background:linear-gradient(90deg,#2fe38f,#38b6ff);' +
    '-webkit-background-clip:text;background-clip:text;color:transparent;font-size:14px}' +
    '#mob h2{margin:0 0 8px;font-size:22px;line-height:1.25;font-weight:700;color:#fff;text-align:center}' +
    '#mob p{margin:0 0 12px;color:#a9bcb8}' +
    '#mob .feat{display:grid;gap:10px;margin:0 0 6px}' +
    '#mob .feat div{padding:12px 14px;border-radius:14px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.08)}' +
    '#mob .feat b{display:block;color:#fff;margin-bottom:2px}' +
    '#mob .goals{display:grid;gap:8px;margin:8px 0 0}' +
    '#mob .goal{display:flex;align-items:center;gap:12px;min-height:52px;padding:10px 14px;border-radius:14px;cursor:pointer;' +
    'background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.12);color:#e6f0ed;font:inherit;text-align:left;transition:border-color .2s,background .2s}' +
    '#mob .goal[aria-checked="true"]{border-color:#2fe38f;background:rgba(47,227,143,.1)}' +
    '#mob .goal small{display:block;color:#8fa6a1;font-size:12px}' +
    '#mob .note{font-size:13px;padding:12px 14px;border-radius:12px;background:rgba(255,209,102,.08);border:1px solid rgba(255,209,102,.3);color:#ffe2a0;margin:12px 0 0}' +
    '#mob .act{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:18px}' +
    '#mob .act .wide{grid-column:1/-1}' +
    '#mob button.b{min-height:48px;border-radius:999px;font:inherit;font-weight:700;cursor:pointer;border:1px solid rgba(255,255,255,.14);' +
    'background:transparent;color:#e6f0ed}' +
    '#mob button.b:focus-visible,#mob .goal:focus-visible{outline:2px solid #38b6ff;outline-offset:2px}' +
    '#mob button.p{background:linear-gradient(90deg,#2fe38f,#38b6ff);color:#04120f;border:0}' +
    '#mob a.lnk{color:#2fe38f}' +
    '@media (prefers-reduced-motion:reduce){#mob .card{animation:none}}';

  function injectCSS() {
    if (document.getElementById('mobCSS')) return;
    var s = document.createElement('style'); s.id = 'mobCSS'; s.textContent = CSS; document.head.appendChild(s);
  }

  /* ── Shield check. Real contract (android AppBlockerPlugin.getStatus):
     { enabled: boolean  -- Maatram Shield is switched on in Accessibility settings,
       serviceRunning: boolean -- the AccessibilityService is connected right now }
     Verified only when BOTH are literally true. Anything else is "unable to verify". ── */
  var UNABLE = 'Unable to verify Shield status. Open Settings > Accessibility, check that Maatram Shield is on, and follow the setup guide.';
  function shieldCheck() {
    return new Promise(function (resolve) {
      var C = window.Capacitor, plug = C && C.Plugins && C.Plugins.AppBlocker;
      if (!C || !C.isNativePlatform || !C.isNativePlatform()) {
        return resolve({ native: false, verified: false,
          text: 'Shield can only be checked inside the Maatram Android app. This website cannot confirm that blocking works.' });
      }
      if (!plug || typeof plug.getStatus !== 'function') return resolve({ native: true, verified: false, text: UNABLE });
      var done = false, t = setTimeout(function () {
        if (!done) { done = true; resolve({ native: true, verified: false, text: UNABLE }); }
      }, 3000);
      plug.getStatus().then(function (r) {
        if (done) return; done = true; clearTimeout(t);
        if (r && r.enabled === true && r.serviceRunning === true) {
          return resolve({ native: true, verified: true, text: 'Shield is on and running. Blocking starts when you start a Hard Lock.' });
        }
        if (r && r.enabled === false) {
          return resolve({ native: true, verified: false, text: 'Shield is off. Follow the steps in the setup guide to turn it on.' });
        }
        if (r && r.enabled === true && r.serviceRunning === false) {
          return resolve({ native: true, verified: false, text: 'Shield is turned on but not running yet. Restart Maatram, then check again.' });
        }
        resolve({ native: true, verified: false, text: UNABLE });
      }).catch(function () {
        if (!done) { done = true; clearTimeout(t); resolve({ native: true, verified: false, text: UNABLE }); }
      });
    });
  }

  var STEP_COUNT = 5, step = 0, box = null, goal = null;

  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }

  function dotsHtml() {
    var d = ''; for (var i = 0; i < STEP_COUNT; i++) d += '<i class="' + (i === step ? 'on' : '') + '"></i>';
    return '<div class="dots" aria-hidden="true">' + d + '</div>';
  }

  function body() {
    if (step === 0) return '<div class="logo">MAATRAM</div><h2>Welcome</h2>' +
      '<p>Maatram helps students spend less time on their phones and more time on what matters. It is free, and the code is open on GitHub.</p>' +
      '<p>This takes about a minute. You can skip it at any time.</p>';
    if (step === 1) return '<h2>Three tools</h2><div class="feat">' +
      '<div><b>Focus Timer</b>Pomodoro 25/5 and Deep Work 90/15. Finishing a session earns points.</div>' +
      '<div><b>App Gate</b>A short pause before a social app opens. Opening one costs points, so you think before you scroll.</div>' +
      '<div><b>Hard Lock</b>Blocks the apps you choose for a set time. On Android it needs Maatram Shield. On the web, the Chrome extension blocks sites.</div></div>';
    if (step === 2) return '<h2>What is your goal?</h2><p>We will point you to the right tool first. You can change this later.</p>' +
      '<div class="goals" role="radiogroup" aria-label="Focus goal">' + GOALS.map(function (g) {
        var on = goal === g.k;
        return '<button type="button" class="goal" role="radio" aria-checked="' + on + '" data-k="' + g.k + '">' +
          '<span><b>' + esc(g.label) + '</b><small>' + esc(g.hint) + '</small></span></button>';
      }).join('') + '</div>';
    if (step === 3) return '<h2>Android: Maatram Shield</h2>' +
      '<p>Blocking on Android needs the Accessibility permission for Maatram Shield. Android 13 and later hide it behind "Restricted settings" until you allow it.</p>' +
      '<div class="note" id="mobShield">Checking Shield…</div>' +
      '<p style="margin-top:12px"><a class="lnk" href="shield-setup.html">Open the Shield setup guide</a>.</p>';
    return '<h2>You are set</h2><p>' + (goal ? 'Start with: <b>' + esc(GOALS.filter(function (g) { return g.k === goal; })[0].hint) + '</b>.' : 'Pick a tool from the menu whenever you are ready.') + '</p>' +
      '<p>Replay this tour any time from the Account page.</p>';
  }

  function render() {
    if (!box) return;
    var last = step === STEP_COUNT - 1, first = step === 0;
    box.querySelector('.card').innerHTML = dotsHtml() + body() +
      '<div class="act">' +
      (first ? '<button type="button" class="b wide" data-a="skip">Skip for now</button>'
             : '<button type="button" class="b" data-a="back">Back</button><button type="button" class="b" data-a="skip">Skip</button>') +
      '<button type="button" class="b p wide" data-a="next">' + (last ? 'Start' : (first ? 'Get started' : 'Next')) + '</button></div>';
    if (step === 2) box.querySelectorAll('.goal').forEach(function (b) {
      b.onclick = function () { goal = b.getAttribute('data-k'); render(); };
    });
    if (step === 3) shieldCheck().then(function (r) {
      var el = document.getElementById('mobShield');
      if (el) el.textContent = r.text;
    });
    var first2 = box.querySelector('[data-a="next"]'); if (first2) first2.focus();
  }

  function close(state) {
    save(state, goal);
    if (box) { try { box.close(); } catch (e) {} box.remove(); box = null; }
    step = 0;
  }

  function open() {
    if (box) return;
    injectCSS();
    step = 0; goal = (read() || {}).goal || null;
    box = document.createElement('dialog'); box.id = 'mob';
    box.setAttribute('aria-label', 'Welcome to Maatram');
    box.innerHTML = '<div class="card"></div>';
    box.addEventListener('cancel', function (e) { e.preventDefault(); close('skipped'); });
    box.addEventListener('click', function (e) {
      var a = e.target.closest && e.target.closest('[data-a]'); if (!a) return;
      var act = a.getAttribute('data-a');
      if (act === 'skip') return close('skipped');
      if (act === 'back') { step = Math.max(0, step - 1); return render(); }
      if (act === 'next') {
        if (step === STEP_COUNT - 1) return close('done');
        step++; save('in-progress', goal); return render();
      }
    });
    document.body.appendChild(box);
    if (box.showModal) box.showModal(); else box.setAttribute('open', '');
    render();
  }

  /* Wait for gate.js's veil to lift so the tour appears after the page is visible. */
  function whenReady(fn) {
    var tries = 0;
    (function poll() {
      var veiled = document.documentElement.classList.contains('mgate');
      if (!veiled || tries > 80) return fn();
      tries++; setTimeout(poll, 100);
    })();
  }

  window.maatramOnboard = { open: open, reset: function () { try { localStorage.removeItem(KEY); } catch (e) {} }, state: function () { return read(); } };

  var params = new URLSearchParams(location.search);
  if (params.get('tour') === '1') {
    if (history.replaceState) history.replaceState(null, '', location.pathname + location.hash);
    window.maatramOnboard.reset();
    whenReady(open);
  } else if (!seen()) {
    whenReady(open);
  }
})();
