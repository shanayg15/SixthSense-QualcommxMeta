/* ============================================================================
   SixthSense — Live Vision Dashboard (vanilla JS, no framework)

   A real, deployable client for the on-device navigation copilot. It connects to
   the phone over WebSocket and renders EXACTLY what the device sends:
     - the Galaxy S25 Ultra camera frame (scene.frame, base64 JPEG)
     - the live SceneState (depth zones, objects, OCR, path, confidence, belt)
     - optional voice events the device emits (scene.voice)

   No synthetic scenes, no webcam stand-in, no mock data — when there is no
   device, the dashboard says so. Visualization only; it runs no AI.
   ========================================================================== */
(function () {
  "use strict";

  // ----------------------------------------------------------- constants ----
  var RECONNECT_MS = 2000;
  var STALE_MS = 4000;             // no frame for this long => "signal lost"
  var VOICE_HOLD_MS = 6000;        // how long a device voice event stays "active"
  var LS_URL = "sixthsense.deviceUrl";
  // Demo-safe default: with `adb forward tcp:8080 tcp:8080` (USB, works in
  // airplane mode) the phone's WebSocket is reachable at localhost. For a Wi-Fi /
  // hotspot demo, override with the phone's LAN IP, e.g. ws://192.168.1.50:8080.
  var DEFAULT_URL = "ws://localhost:8080";

  // Object-box colors mirror the phone's DetectionOverlayView thresholds so the
  // dashboard turns a box red at exactly the nearness the belt starts buzzing.
  var BOX_RED = 0.70, BOX_YELLOW = 0.45;

  // BeltMapper.kt constants (device computes belt; we only fall back if omitted).
  var NEAR = 0.55, LOW_CONF = 0.40, CLEAR_HUM = 30, CURB_CENTER_MIN = 180, CAUTION_CENTER = 80;

  var C = {
    clear: "#2e844a", watch: "#0176d3", near: "#dd7a01", close: "#ba0517",
    ink: "#181818", body: "#3e3e3c", meta: "#706e6b", border: "#dddbda", borderStrong: "#c9c7c5",
    blue: "#0176d3", blueDark: "#014486", blueBright: "#1b96ff", blueTint: "#eaf5fe",
    navy: "#032d60", white: "#ffffff", green: "#2e844a", red: "#ba0517", amber: "#dd7a01"
  };
  var FONT = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';

  var reduceMotion = false;
  (function () {
    try {
      var mq = window.matchMedia("(prefers-reduced-motion: reduce)");
      reduceMotion = mq.matches;
      var on = function (e) { reduceMotion = e.matches; };
      if (mq.addEventListener) mq.addEventListener("change", on); else if (mq.addListener) mq.addListener(on);
    } catch (e) {}
  })();

  // The technologies actually being integrated (docs/model_export_plan.md).
  var TECH = [
    { key: "depth",   name: "Depth-Anything-V2", rt: "ExecuTorch · QNN/NPU", role: "Monocular depth → per-zone nearness" },
    { key: "yolo",    name: "YOLOv11n / v8n",    rt: "ExecuTorch · QNN/NPU", role: "Obstacle detection & localisation" },
    { key: "ocr",     name: "TrOCR",             rt: "ExecuTorch (ML Kit fb)", role: "Reads signs / text on demand" },
    { key: "whisper", name: "Whisper base/small",rt: "ExecuTorch",          role: "Speech → text (push-to-talk)" },
    { key: "llama",   name: "Llama 3.2 1B",      rt: "ExecuTorch",          role: "Voice-agent reasoning" },
    { key: "tts",     name: "Android TextToSpeech", rt: "On-device",        role: "Speaks the agent's answer" },
    { key: "npu",     name: "ExecuTorch runtime",rt: "Snapdragon SM8750",   role: "On-device inference · Hexagon v79 NPU" },
    { key: "belt",    name: "BLE haptic belt",   rt: "Nordic UART · ESP32", role: "Steers via L/C/R vibration" }
  ];

  // -------------------------------------------------------------- state -----
  var state = {
    scene: null, status: "offline",        // offline | connecting | live | reconnecting
    url: DEFAULT_URL, wantConnected: false,
    frameTimes: [], fps: 0, ms: 0, lastFrameAt: 0,
    voiceAt: 0, voice: null, paused: false,
    layers: { detection: true, depth: true, ocr: true, path: true, hud: true },
    frameImg: null, frameReady: false, frameRotation: 0
  };
  var ws = null, reconnectTimer = null;

  // -------------------------------------------------------------- utils -----
  function $(s, r) { return (r || document).querySelector(s); }
  function $all(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }
  function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }
  function pct(v) { return Math.round(clamp(v, 0, 1) * 100); }
  function num(v, d) { return (typeof v === "number" && isFinite(v)) ? v : d; }
  function perf() { return (window.performance && performance.now) ? performance.now() : Date.now(); }
  function heatKey(v) { return v >= 0.75 ? "close" : v >= NEAR ? "near" : v >= 0.40 ? "watch" : "clear"; }
  function heatColor(v) { return C[heatKey(v)]; }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]; }); }
  function rgba(hex, a) { var h = hex.replace("#", ""); return "rgba(" + parseInt(h.substr(0, 2), 16) + "," + parseInt(h.substr(2, 2), 16) + "," + parseInt(h.substr(4, 2), 16) + "," + a + ")"; }
  function live() { return state.status === "live"; }
  function fresh() { return live() && state.lastFrameAt && (perf() - state.lastFrameAt) < STALE_MS; }
  // Real YOLO box from the device: normalized [0,1] xyxy of the upright frame, or
  // null. Validated so a malformed box falls back to the synthetic placement.
  function normBox(b) {
    if (!b || typeof b !== "object") return null;
    var x1 = num(b.x1, NaN), y1 = num(b.y1, NaN), x2 = num(b.x2, NaN), y2 = num(b.y2, NaN);
    if (!isFinite(x1) || !isFinite(y1) || !isFinite(x2) || !isFinite(y2)) return null;
    if (x2 <= x1 || y2 <= y1) return null;
    return { x1: clamp(x1, 0, 1), y1: clamp(y1, 0, 1), x2: clamp(x2, 0, 1), y2: clamp(y2, 0, 1) };
  }
  // Box outline color: matches DetectionOverlayView.kt (red ⇒ "too close" ⇒ belt buzzes).
  function boxColorFor(n) { return n >= BOX_RED ? C.red : n >= BOX_YELLOW ? C.amber : C.green; }

  // ---------------------------------------------- BeltMapper.kt fallback ----
  var NEAR_F = Math.fround(NEAR);
  function intensity(v) {
    if (v < NEAR) return 0;
    var f = Math.fround, a = f(f(v) - NEAR_F), b = f(f(1) - NEAR_F);
    return clamp(Math.trunc(f(f(a / b) * f(255))), 0, 255);
  }
  function mapToBelt(s) {
    var d = s.depth, l = intensity(d.left), c = intensity(d.center), r = intensity(d.right), p = 0;
    if (d.curbAhead || d.stepDown) { p = 2; c = Math.max(c, CURB_CENTER_MIN); }
    if (s.conf < LOW_CONF) { p = 1; c = Math.max(c, CAUTION_CENTER); l = 0; r = 0; }
    if (l === 0 && c === 0 && r === 0 && p === 0 && s.pathClear) { l = c = r = CLEAR_HUM; }
    return [l, c, r, p];
  }
  function beltOf(s) { return (Array.isArray(s.belt) && s.belt.length === 4) ? s.belt : mapToBelt(s); }
  function patternLabel(p) { return p === 2 ? "double pulse — curb / step ahead" : p === 1 ? "single pulse — low-confidence caution" : "steady"; }
  function patternGate(p, t) {
    if (p === 1) return (t % 600) < 300;
    if (p === 2) { var x = t % 760; return x < 120 || (x >= 240 && x < 360); }
    return true;
  }

  // ----------------------------------------------------- normalization ------
  function normalize(raw) {
    var d = raw.depth || {};
    var s = {
      ts: num(raw.ts, 0),
      depth: { left: num(d.left, 0), center: num(d.center, 0), right: num(d.right, 0), curbAhead: !!d.curbAhead, stepDown: !!d.stepDown },
      objects: Array.isArray(raw.objects) ? raw.objects.map(function (o) {
        return { label: String(o.label || "object"), zone: String(o.zone || "center"), nearness: num(o.nearness, 0), conf: num(o.conf, 0), box: normBox(o.box) };
      }) : [],
      pathClear: !!raw.pathClear,
      ocr: { present: !!(raw.ocr && raw.ocr.present), text: (raw.ocr && raw.ocr.text) ? String(raw.ocr.text) : "" },
      conf: num(raw.conf, 0),
      belt: (Array.isArray(raw.belt) && raw.belt.length === 4) ? raw.belt.map(function (n) { return num(n, 0) | 0; }) : null
    };
    if (!s.belt) s.belt = mapToBelt(s);
    // device camera frame (base64 JPEG or data: URL)
    if (typeof raw.frame === "string" && raw.frame) {
      var url = raw.frame.indexOf("data:") === 0 ? raw.frame : ("data:image/jpeg;base64," + raw.frame);
      if (!state.frameImg) { state.frameImg = new Image(); state.frameImg.onload = function () { state.frameReady = true; }; }
      state.frameImg.src = url;
      state.frameRotation = num(raw.frameRotation, 0);
      state.lastFrameAt = perf();
    }
    // optional device voice event { question, intent, answer }
    if (raw.voice && (raw.voice.answer || raw.voice.question)) {
      state.voice = { question: String(raw.voice.question || ""), intent: String(raw.voice.intent || ""), answer: String(raw.voice.answer || "") };
      state.voiceAt = perf();
    }
    return s;
  }

  // ------------------------------------------------------- connection -------
  function setStatus(st) { state.status = st; renderStatus(); }
  function clearReconnect() { if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; } }
  // Drop per-session metrics + the last camera frame so a reconnect / device
  // switch never shows stale fps or another device's frame under a live label.
  function resetSession() { state.frameTimes = []; state.fps = 0; state.ms = 0; state.lastFrameAt = 0; state.frameReady = false; state.frameImg = null; state.frameRotation = 0; }

  function connect() {
    state.wantConnected = true;
    state.url = ($("#url-input").value || "").trim() || DEFAULT_URL;
    try { localStorage.setItem(LS_URL, state.url); } catch (e) {}
    openSocket();
  }
  function disconnect() {
    state.wantConnected = false; clearReconnect();
    if (ws) { try { ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null; ws.close(); } catch (e) {} ws = null; }
    resetSession();
    setStatus("offline");
    syncConnectButton();
  }
  function openSocket() {
    clearReconnect();
    if (ws) { try { ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null; ws.close(); } catch (e) {} ws = null; }
    resetSession();
    setStatus("connecting");
    var url = state.url;
    if (location.protocol === "https:" && url.indexOf("ws://") === 0) showMixedWarning(true); else showMixedWarning(false);
    try { ws = new WebSocket(url); } catch (e) { scheduleReconnect(); return; }
    ws.onopen = function () { setStatus("live"); syncConnectButton(); };
    ws.onmessage = function (ev) { if (typeof ev.data !== "string") return; try { ingest(JSON.parse(ev.data)); } catch (e) {} };
    ws.onerror = function () { /* close handler drives reconnect */ };
    ws.onclose = function () { ws = null; if (state.wantConnected) { setStatus("reconnecting"); scheduleReconnect(); } else setStatus("offline"); };
  }
  function scheduleReconnect() { clearReconnect(); if (state.wantConnected) reconnectTimer = setTimeout(openSocket, RECONNECT_MS); }

  function ingest(raw) {
    // fps = the CAMERA frame rate (count only messages that carry a frame), not the
    // total message rate — scene-only updates (boxes/depth/belt) arrive more often.
    var hadFrame = typeof raw.frame === "string" && raw.frame.length > 0;
    state.scene = normalize(raw);
    if (hadFrame) {
      var now = perf();
      state.frameTimes.push(now); if (state.frameTimes.length > 12) state.frameTimes.shift();
      if (state.frameTimes.length > 1) {
        var span = state.frameTimes[state.frameTimes.length - 1] - state.frameTimes[0];
        state.ms = span / (state.frameTimes.length - 1);
        state.fps = state.ms > 0 ? 1000 / state.ms : 0;
      }
    }
    render();
  }

  // -------------------------------------------------------- DOM binding -----
  function setBind(k, v) { $all('[data-bind="' + k + '"]').forEach(function (el) { el.textContent = v; }); }
  function syncConnectButton() {
    var b = $("#btn-connect");
    if (state.wantConnected) { b.textContent = "Disconnect"; b.classList.remove("btn--primary"); }
    else { b.textContent = "Connect"; b.classList.add("btn--primary"); }
  }
  function showMixedWarning(on) { var w = $("#mixed-warn"); if (w) w.style.display = on ? "inline" : "none"; }

  function renderStatus() {
    var st = state.status, cls = st === "live" ? (fresh() ? "live" : "stale") : st === "connecting" || st === "reconnecting" ? "connecting" : "offline";
    var pill = $("#conn-pill"); pill.className = "pill pill--" + cls;
    setBind("mode", st === "live" ? (fresh() ? "LIVE" : "SIGNAL LOST") : st.toUpperCase());
    setBind("fps", state.fps.toFixed(1));
    setBind("ms", Math.round(state.ms));
  }

  function render() {
    var s = state.scene;
    renderStatus();
    var has = live() && !!s;
    setBind("conf", has ? String(pct(s.conf)) : "—");
    setBind("ocr", has && s.ocr.present && s.ocr.text ? '"' + s.ocr.text + '"' : "—");
    setBind("curb", has ? String(s.depth.curbAhead) : "—");
    setBind("step", has ? String(s.depth.stepDown) : "—");
    setBind("objCount", has ? String(s.objects.length) : "—");
    setBind("camRes", camRes());
    setBind("pathClear", has ? (s.pathClear ? "CLEAR" : "BLOCKED") : "—");
    var pk = $('[data-kpi="path"]'); if (pk) pk.className = "kpi " + (!has ? "" : s.pathClear ? "kpi--ok" : "kpi--bad");
    if (has) { var cl = s.conf >= 0.7 ? "ok" : s.conf >= 0.4 ? "warn" : "bad"; var ck = $('[data-kpi="conf"]'); if (ck) ck.className = "kpi " + (cl === "ok" ? "kpi--ok" : cl === "warn" ? "kpi--warn" : "kpi--bad"); }
    else { var ck2 = $('[data-kpi="conf"]'); if (ck2) ck2.className = "kpi"; }

    renderZones(s, has);
    renderBelt(s, has);
    renderObjects(s, has);
    renderVoice();
    if (!state.paused) { var pre = $("#raw-json"); if (pre) pre.textContent = has ? JSON.stringify(stripFrame(s), null, 2) : "// waiting for device…"; }
  }
  function stripFrame(s) { return s; } // frame is held on state, not in the scene object
  function camRes() {
    if (state.frameReady && state.frameImg && state.frameImg.naturalWidth) {
      var w = state.frameImg.naturalWidth, h = state.frameImg.naturalHeight, r = ((state.frameRotation % 360) + 360) % 360;
      return (r === 90 || r === 270) ? (h + "×" + w) : (w + "×" + h);
    }
    return "—";
  }

  function renderZones(s, has) {
    var map = has ? { left: s.depth.left, center: s.depth.center, right: s.depth.right } : { left: null, center: null, right: null };
    $all(".zone").forEach(function (z) {
      var v = map[z.getAttribute("data-zone")];
      if (v == null) { z.className = "zone"; var f0 = $(".zone__fill", z); if (f0) f0.style.height = "0%"; var v0 = $(".zone__val", z); if (v0) v0.textContent = "—"; var t0 = $(".zone__tag", z); if (t0) { t0.textContent = ""; t0.className = "zone__tag"; } return; }
      var k = heatKey(v); z.className = "zone is-" + k;
      var fill = $(".zone__fill", z); if (fill) fill.style.height = pct(v) + "%";
      var val = $(".zone__val", z); if (val) val.textContent = v.toFixed(2);
      var tag = $(".zone__tag", z); if (tag) { tag.textContent = k === "close" ? "CLOSE" : k === "near" ? "NEAR" : k === "watch" ? "WATCH" : "CLEAR"; tag.className = "zone__tag is-" + k; }
    });
  }
  function renderBelt(s, has) {
    var p = has ? beltOf(s) : [0, 0, 0, 0];
    $all("[data-belt-packet]").forEach(function (w) {
      $all(".byte", w).forEach(function (b) { var i = +b.getAttribute("data-byte"); b.innerHTML = (has ? p[i] : "—") + "<small>" + (i === 0 ? "LEFT" : i === 1 ? "CENTER" : i === 2 ? "RIGHT" : "PATTERN") + "</small>"; });
    });
    setBind("beltPattern", has ? "Pattern " + p[3] + " · " + patternLabel(p[3]) : "awaiting device");
  }
  function renderObjects(s, has) {
    var tb = $("#objects-tbody"), empty = $("#objects-empty"); if (!tb) return;
    tb.innerHTML = "";
    if (!has || !s.objects.length) { if (empty) { empty.style.display = "block"; empty.textContent = has ? "No objects in frame." : "Waiting for device…"; } return; }
    if (empty) empty.style.display = "none";
    s.objects.forEach(function (o) {
      var tr = document.createElement("tr");
      tr.innerHTML = '<td><b>' + esc(o.label) + '</b></td><td><span class="tag tag--' + esc(o.zone) + '">' + esc(o.zone) + '</span></td><td class="mono">' + o.nearness.toFixed(2) + '</td><td class="mono">' + pct(o.conf) + '%</td>';
      tb.appendChild(tr);
    });
  }
  function renderVoice() {
    var on = state.voice && (perf() - state.voiceAt) < VOICE_HOLD_MS;
    $("#voice-q").textContent = on ? (state.voice.question || "—") : "—";
    $("#voice-intent").textContent = on && state.voice.intent ? state.voice.intent : "—";
    $("#voice-answer").textContent = on ? (state.voice.answer || "—") : "No voice events from the device yet.";
  }

  // --------------------------------------------------- technology panel -----
  function buildTech() {
    var host = $("#tech-list"); if (!host) return;
    host.innerHTML = TECH.map(function (m) {
      return '<div class="tech" data-tech="' + m.key + '"><span class="tech__dot"></span>' +
        '<div class="tech__main"><div class="tech__name">' + m.name + ' <span class="tech__rt">' + m.rt + '</span></div>' +
        '<div class="tech__role">' + m.role + '</div><div class="tech__out" data-techout>—</div>' +
        '<div class="tech__bar"><i></i></div></div><span class="tech__state">STANDBY</span></div>';
    }).join("");
  }
  function refreshTech() {
    var s = state.scene, has = live() && !!s, fr = fresh();
    var voiceOn = has && state.voice && (perf() - state.voiceAt) < VOICE_HOLD_MS;
    var p = has ? beltOf(s) : [0, 0, 0, 0], beltLive = p[0] > 0 || p[1] > 0 || p[2] > 0;
    var act = {
      depth:   { on: has && fr, out: has ? "L " + s.depth.left.toFixed(2) + " · C " + s.depth.center.toFixed(2) + " · R " + s.depth.right.toFixed(2) : "—" },
      yolo:    { on: has && s.objects.length > 0, out: !has ? "—" : s.objects.length ? s.objects.map(function (o) { return o.label + "·" + o.zone; }).join(", ") : "no objects in frame" },
      ocr:     { on: has && s.ocr.present, out: !has ? "—" : s.ocr.present ? '"' + s.ocr.text + '"' : "idle — on demand" },
      whisper: { on: voiceOn, out: voiceOn ? '"' + state.voice.question + '"' : (has ? "push-to-talk ready" : "—") },
      llama:   { on: voiceOn, out: voiceOn ? state.voice.answer : (has ? "awaiting a question" : "—") },
      tts:     { on: voiceOn, out: voiceOn ? "speaking answer" : (has ? "ready" : "—") },
      npu:     { on: has && fr, out: has ? "Snapdragon 8 Elite · " + state.fps.toFixed(1) + " fps" : "—" },
      belt:    { on: beltLive, out: has ? "[" + p.join(", ") + "] · " + (p[3] === 2 ? "double" : p[3] === 1 ? "pulse" : "steady") : "—" }
    };
    $all(".tech").forEach(function (el) {
      var a = act[el.getAttribute("data-tech")]; if (!a) return;
      el.classList.toggle("is-active", !!a.on);
      var st = $(".tech__state", el); if (st) st.textContent = a.on ? "ACTIVE" : "STANDBY";
      var out = $("[data-techout]", el); if (out) out.textContent = a.out;
    });
  }

  // ------------------------------------------------------- canvas helpers ---
  function label(ctx, x, y, text, color, size, bg) {
    ctx.font = "700 " + (size || 12) + "px " + FONT;
    if (bg) { var w = ctx.measureText(text).width; ctx.fillStyle = bg; ctx.fillRect(x - 3, y - (size || 12), w + 6, (size || 12) + 5); }
    ctx.fillStyle = color; ctx.textBaseline = "alphabetic"; ctx.fillText(text, x, y);
  }
  function bracket(ctx, x, y, s, dx, dy, color) { ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(x + dx * s, y); ctx.lineTo(x, y); ctx.lineTo(x, y + dy * s); ctx.stroke(); }
  function objRect(zone, nearness, W, H) {
    var idx = zone === "left" ? 0 : zone === "right" ? 2 : 1, colW = W / 3, cx = (idx + 0.5) * colW, n = clamp(nearness, 0, 1);
    var size = 0.10 * W + n * 0.18 * W, feet = H * 0.50 + n * (H * 0.42);
    return { x: cx - size / 2, y: feet - size, w: size, h: size, cx: cx, feet: feet };
  }
  // Cover-fit the device frame, applying the device's rotationDegrees (so the
  // phone can send an unrotated JPEG and we orient it here — no double JPEG
  // encode on the phone).
  function drawFrame(ctx, img, W, H, deg) {
    var iw = img.naturalWidth || img.width, ih = img.naturalHeight || img.height; if (!iw || !ih) return false;
    var rot = ((deg % 360) + 360) % 360;
    var ew = (rot === 90 || rot === 270) ? ih : iw, eh = (rot === 90 || rot === 270) ? iw : ih;
    var scale = Math.max(W / ew, H / eh), dw = iw * scale, dh = ih * scale;
    ctx.save(); ctx.translate(W / 2, H / 2); ctx.rotate(rot * Math.PI / 180);
    ctx.drawImage(img, -dw / 2, -dh / 2, dw, dh); ctx.restore();
    return true;
  }
  // Returns fn(nx, ny) -> {x, y}: maps a normalized [0,1] point of the device frame
  // to canvas pixels using the EXACT same cover-fit + rotation as drawFrame(), so a
  // box drawn through it lands pixel-accurate on the object. null if no frame yet.
  function frameMapper(W, H) {
    if (!(state.frameReady && state.frameImg)) return null;
    var img = state.frameImg, iw = img.naturalWidth || img.width, ih = img.naturalHeight || img.height;
    if (!iw || !ih) return null;
    var deg = ((state.frameRotation % 360) + 360) % 360;
    var ew = (deg === 90 || deg === 270) ? ih : iw, eh = (deg === 90 || deg === 270) ? iw : ih;
    var scale = Math.max(W / ew, H / eh), dw = iw * scale, dh = ih * scale;
    var rad = deg * Math.PI / 180, cos = Math.cos(rad), sin = Math.sin(rad);
    return function (nx, ny) {
      var lx = -dw / 2 + nx * dw, ly = -dh / 2 + ny * dh;
      return { x: W / 2 + lx * cos - ly * sin, y: H / 2 + lx * sin + ly * cos };
    };
  }
  // Map a normalized box -> axis-aligned canvas rect (corners stay axis-aligned for
  // the 0/90/180/270 rotations the device sends).
  function boxRect(map, b) {
    var p1 = map(b.x1, b.y1), p2 = map(b.x2, b.y2);
    var x = Math.min(p1.x, p2.x), y = Math.min(p1.y, p2.y);
    return { x: x, y: y, w: Math.abs(p2.x - p1.x), h: Math.abs(p2.y - p1.y) };
  }
  function centerMsg(ctx, W, H, big, small, color) {
    ctx.textAlign = "center";
    ctx.font = "700 22px " + FONT; ctx.fillStyle = color || "#9fb0c7"; ctx.fillText(big, W / 2, H / 2 - 4);
    if (small) { ctx.font = "13px " + FONT; ctx.fillStyle = "#74839a"; ctx.fillText(small, W / 2, H / 2 + 22); }
    ctx.textAlign = "left";
  }

  // --------------------------------------------------------- camera draw ----
  function drawCamera(ctx, W, H, s, t) {
    ctx.clearRect(0, 0, W, H); ctx.fillStyle = "#0b0f14"; ctx.fillRect(0, 0, W, H);
    if (state.status !== "live") {
      centerMsg(ctx, W, H, state.status === "connecting" || state.status === "reconnecting" ? "CONNECTING TO DEVICE…" : "NOT CONNECTED", state.status === "offline" ? "Set the device address and press Connect" : state.url);
      ctx.canvas.setAttribute("aria-label", "Camera: " + state.status);
      return;
    }
    var drew = state.frameReady && state.frameImg ? drawFrame(ctx, state.frameImg, W, H, state.frameRotation) : false;
    if (!drew) centerMsg(ctx, W, H, "WAITING FOR CAMERA FRAME…", "Connected — device has not sent a frame yet");
    if (!s) return;
    var L = state.layers, colW = W / 3;

    if (L.depth) {
      [["LEFT", s.depth.left], ["CENTER", s.depth.center], ["RIGHT", s.depth.right]].forEach(function (z, i) {
        var v = z[1], a = 0.05 + clamp(v, 0, 1) * 0.30, fh = H * (0.22 + clamp(v, 0, 1) * 0.55);
        ctx.fillStyle = rgba(heatColor(v), a); ctx.fillRect(i * colW + 2, H - fh, colW - 4, fh);
        label(ctx, i * colW + 8, 20, z[0] + " " + v.toFixed(2), heatColor(v), 12, "rgba(0,0,0,0.35)");
      });
      ctx.strokeStyle = "rgba(27,150,255,0.28)"; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(colW, 0); ctx.lineTo(colW, H); ctx.moveTo(2 * colW, 0); ctx.lineTo(2 * colW, H); ctx.stroke();
    }
    if (L.detection) {
      var map = drew ? frameMapper(W, H) : null;
      s.objects.forEach(function (o) {
        // Real YOLO box on the live frame when available; synthetic placement only
        // as a fallback (mock data / before the first frame arrives).
        var r = (o.box && map) ? boxRect(map, o.box) : objRect(o.zone, o.nearness, W, H);
        var col = boxColorFor(o.nearness), close = o.nearness >= BOX_RED;
        if (close) { ctx.fillStyle = rgba(col, 0.16); ctx.fillRect(r.x, r.y, r.w, r.h); }
        ctx.strokeStyle = col; ctx.lineWidth = close ? 3 : 2; ctx.strokeRect(r.x, r.y, r.w, r.h);
        var tag = o.label + " · " + o.nearness.toFixed(2) + " · " + pct(o.conf) + "%" + (close ? "  ⚠ CLOSE" : "");
        var ly = r.y > 16 ? r.y - 5 : r.y + r.h + 14;   // keep the label on-screen
        label(ctx, r.x, ly, tag, close ? C.white : col, 12, close ? rgba(C.red, 0.92) : "rgba(255,255,255,0.92)");
      });
    }
    if (L.ocr && s.ocr.present && s.ocr.text) {
      var sign = s.objects.filter(function (o) { return /sign/i.test(o.label); })[0];
      var ox = sign ? objRect(sign.zone, sign.nearness, W, H).cx - 40 : W / 2 - 40;
      label(ctx, ox, H * 0.30, "TEXT: " + s.ocr.text, C.blueDark, 14, C.blueTint);
    }
    if (L.hud) {
      bracket(ctx, 10, 10, 18, 1, 1, C.blueBright); bracket(ctx, W - 10, 10, 18, -1, 1, C.blueBright);
      bracket(ctx, 10, H - 10, 18, 1, -1, C.blueBright); bracket(ctx, W - 10, H - 10, 18, -1, -1, C.blueBright);
      var rx = W / 2, ry = H * 0.42;
      ctx.strokeStyle = "rgba(27,150,255,0.55)"; ctx.lineWidth = 1.5; ctx.strokeRect(rx - 12, ry - 12, 24, 24);
      ctx.beginPath(); ctx.moveTo(rx - 20, ry); ctx.lineTo(rx - 14, ry); ctx.moveTo(rx + 14, ry); ctx.lineTo(rx + 20, ry); ctx.moveTo(rx, ry - 20); ctx.lineTo(rx, ry - 14); ctx.moveTo(rx, ry + 14); ctx.lineTo(rx, ry + 20); ctx.stroke();
      if (!reduceMotion && fresh()) {
        var sy = ((t / 2400) % 1) * H;
        ctx.strokeStyle = "rgba(27,150,255,0.55)"; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(0, sy); ctx.lineTo(W, sy); ctx.stroke();
      }
      var tags = ["DEPTH"]; if (s.objects.length) tags.push("YOLO"); if (s.ocr.present) tags.push("OCR"); tags.push("NPU");
      label(ctx, 26, 20, (fresh() ? "● REC  " : "○ STALE  ") + tags.join("  "), C.white, 12, "rgba(0,0,0,0.35)");
      var info = "CONF " + pct(s.conf) + "%   " + camRes() + "   " + state.fps.toFixed(1) + " fps";
      ctx.font = "700 12px " + FONT; label(ctx, W - ctx.measureText(info).width - 14, 20, info, C.white, 12, "rgba(0,0,0,0.35)");
    }
    if (L.path) {
      var b = banner(s);
      ctx.font = "700 16px " + FONT; var bw = ctx.measureText(b.text).width + 26, bx = W / 2 - bw / 2, by = H - 42;
      ctx.fillStyle = b.bg; ctx.fillRect(bx, by, bw, 28); ctx.strokeStyle = b.fg; ctx.lineWidth = 1; ctx.strokeRect(bx, by, bw, 28);
      label(ctx, bx + 13, by + 19, b.text, b.fg, 16);
      ctx.canvas.setAttribute("aria-label", "Camera: " + b.text + ", confidence " + pct(s.conf) + " percent");
    }
  }
  function banner(s) {
    var d = s.depth;
    if (s.conf < 0.4) return { text: "LOW CONFIDENCE — CAUTION", fg: C.amber, bg: "#fbf0e6" };
    if (d.curbAhead || d.stepDown) return { text: (d.stepDown ? "STEP DOWN" : "CURB") + " — SLOW DOWN", fg: C.red, bg: "#feebec" };
    if (d.center >= NEAR) return { text: "OBSTACLE AHEAD — STEER L / R", fg: C.red, bg: "#feebec" };
    if (d.left >= NEAR && d.right >= NEAR) return { text: "OBSTACLES BOTH SIDES — GO SLOW", fg: C.red, bg: "#feebec" };
    if (d.left >= NEAR) return { text: "OBSTACLE LEFT — KEEP RIGHT", fg: C.amber, bg: "#fbf0e6" };
    if (d.right >= NEAR) return { text: "OBSTACLE RIGHT — KEEP LEFT", fg: C.amber, bg: "#fbf0e6" };
    if (s.pathClear) return { text: "PATH CLEAR", fg: C.green, bg: "#ebf7ee" };
    return { text: "PROCEED CAREFULLY", fg: C.amber, bg: "#fbf0e6" };
  }

  // ----------------------------------------------------------- belt viz -----
  function drawBelt(ctx, W, H, s, t) {
    ctx.clearRect(0, 0, W, H); ctx.fillStyle = C.white; ctx.fillRect(0, 0, W, H);
    var has = live() && !!s, p = has ? beltOf(s) : [0, 0, 0, 0], pat = p[3];
    var names = ["LEFT", "CENTER", "RIGHT"], pad = 14, gap = 14, cellW = (W - pad * 2 - gap * 2) / 3, top = 16, cellH = H - top - 46;
    var on = has && fresh() && patternGate(pat, t);
    for (var i = 0; i < 3; i++) {
      var m = p[i] | 0, x = pad + i * (cellW + gap), active = on && m > 0, jitter = (active && !reduceMotion) ? Math.sin(t / 24 + i) * 2.2 : 0;
      ctx.strokeStyle = C.borderStrong; ctx.lineWidth = 1; ctx.strokeRect(x, top, cellW, cellH);
      var col = m === 0 ? C.border : m < 128 ? C.watch : m < 200 ? C.amber : C.red, fh = (m / 255) * (cellH - 6);
      ctx.fillStyle = active ? col : rgba(col, 0.28); ctx.fillRect(x + 3, top + cellH - 3 - fh + jitter, cellW - 6, fh);
      if (active) { ctx.strokeStyle = col; ctx.lineWidth = 3; ctx.strokeRect(x - 2, top - 2, cellW + 4, cellH + 4); }
      label(ctx, x + 8, top + 16, names[i], C.meta, 11);
      ctx.font = "700 24px " + FONT; ctx.fillStyle = has ? C.ink : C.meta; var mt = has ? String(m) : "—";
      ctx.fillText(mt, x + cellW / 2 - ctx.measureText(mt).width / 2, top + cellH - 12);
    }
    label(ctx, pad, H - 16, has ? "pattern " + pat + " · " + (pat === 2 ? "double pulse" : pat === 1 ? "single pulse" : "steady") : "awaiting device", C.body, 12);
    ctx.canvas.setAttribute("aria-label", has ? "Haptic belt: left " + p[0] + ", center " + p[1] + ", right " + p[2] + ", " + patternLabel(pat) : "Haptic belt: awaiting device");
  }

  // -------------------------------------------------------- flow diagram ----
  function drawFlow(ctx, W, H, s, t) {
    ctx.clearRect(0, 0, W, H); ctx.fillStyle = C.white; ctx.fillRect(0, 0, W, H);
    var has = live() && !!s, fr = fresh();
    var voiceOn = has && state.voice && (perf() - state.voiceAt) < VOICE_HOLD_MS;
    var p = has ? beltOf(s) : [0, 0, 0, 0], beltLive = p[0] > 0 || p[1] > 0 || p[2] > 0;
    var camX = W * 0.10, visX = W * 0.37, scnX = W * 0.62, outX = W * 0.88, midY = H * 0.5;
    var subY = [midY - H * 0.27, midY, midY + H * 0.27], nodeW = Math.min(110, W * 0.18), nodeH = 32;
    function edge(x1, y1, x2, y2, active, phase) {
      ctx.strokeStyle = active ? "rgba(1,118,211,0.55)" : "rgba(0,0,0,0.12)"; ctx.lineWidth = active ? 2 : 1;
      ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
      if (active) { var f = reduceMotion ? 0.5 : ((t / 1400 + (phase || 0)) % 1); ctx.fillStyle = C.blueBright; ctx.fillRect(x1 + (x2 - x1) * f - 3, y1 + (y2 - y1) * f - 3, 6, 6); }
    }
    function node(cx, cy, title, sub, active) {
      var w = nodeW, h = sub ? nodeH + 12 : nodeH, x = cx - w / 2, y = cy - h / 2;
      ctx.fillStyle = active ? C.blueTint : C.white; ctx.fillRect(x, y, w, h);
      ctx.strokeStyle = active ? C.blue : C.borderStrong; ctx.lineWidth = active ? 2 : 1; ctx.strokeRect(x, y, w, h);
      ctx.font = "700 12px " + FONT; ctx.fillStyle = active ? C.blueDark : C.ink; ctx.fillText(title, cx - ctx.measureText(title).width / 2, cy + (sub ? -2 : 4));
      if (sub) { ctx.font = "10px " + FONT; ctx.fillStyle = C.meta; ctx.fillText(sub, cx - ctx.measureText(sub).width / 2, cy + 13); }
    }
    edge(camX + nodeW / 2, midY, visX - nodeW / 2, midY, has && fr, 0);
    edge(visX + nodeW / 2, midY, scnX - nodeW / 2, midY, has && fr, 0.3);
    edge(scnX + nodeW / 2, midY, outX - nodeW / 2, subY[0], beltLive, 0.1);
    edge(scnX + nodeW / 2, midY, outX - nodeW / 2, subY[1], voiceOn, 0.5);
    edge(scnX + nodeW / 2, midY, outX - nodeW / 2, subY[2], has, 0.7);
    label(ctx, visX - nodeW / 2, subY[0] - nodeH, "ExecuTorch · NPU", C.meta, 10);
    node(camX, midY, "S25 Camera", "frames", has && fr);
    node(visX, subY[0], "Depth", "nearness", has && fr);
    node(visX, subY[1], "YOLO", "objects", has && s && s.objects.length > 0);
    node(visX, subY[2], "OCR", "on demand", has && s && s.ocr.present);
    node(scnX, midY, "SceneState", "contract", has);
    node(outX, subY[0], "Belt", "BLE", beltLive);
    node(outX, subY[1], "Voice", "agent", voiceOn);
    node(outX, subY[2], "Dashboard", "this view", has);
  }

  // ---------------------------------------------------- animation loop ------
  var CANVASES = [];
  function tick() {
    var now = perf();
    try {
      renderStatus(); refreshTech();
      for (var i = 0; i < CANVASES.length; i++) {
        var c = CANVASES[i]; if (c.el.offsetParent === null) continue;
        var ctx = c.el.getContext("2d"); if (!ctx) continue;
        try { c.fn(ctx, c.el.width, c.el.height, state.scene, now); } catch (e) {}
      }
    } catch (e) {}
    requestAnimationFrame(tick);
  }

  // ------------------------------------------------------------ wiring ------
  function init() {
    buildTech();
    [["cv-camera", drawCamera], ["cv-belt", drawBelt], ["cv-flow", drawFlow]].forEach(function (p) {
      var el = document.getElementById(p[0]); if (el) CANVASES.push({ el: el, fn: p[1] });
    });

    var saved; try { saved = localStorage.getItem(LS_URL); } catch (e) {}
    state.url = saved || DEFAULT_URL;
    $("#url-input").value = state.url;

    $("#layerbar").addEventListener("click", function (e) {
      var b = e.target.closest(".layer-toggle"); if (!b) return;
      var k = b.getAttribute("data-layer"); state.layers[k] = !state.layers[k];
      b.setAttribute("aria-pressed", state.layers[k] ? "true" : "false");
    });

    document.addEventListener("click", function (e) {
      var head = e.target.closest(".card__head"); if (!head) return;
      var collapsed = head.parentElement.classList.toggle("card--collapsed");
      head.setAttribute("aria-expanded", String(!collapsed));
    });
    function setAll(c) { $all("[data-card]").forEach(function (card) { card.classList.toggle("card--collapsed", c); var h = card.querySelector(".card__head"); if (h) h.setAttribute("aria-expanded", String(!c)); }); }
    $("#btn-collapse-all").addEventListener("click", function () { setAll(true); });
    $("#btn-expand-all").addEventListener("click", function () { setAll(false); });

    $("#btn-connect").addEventListener("click", function () { if (state.wantConnected) disconnect(); else connect(); });
    $("#url-input").addEventListener("keydown", function (e) { if (e.key === "Enter") connect(); });

    $("#btn-raw-pause").addEventListener("click", function () { state.paused = !state.paused; this.textContent = state.paused ? "Resume" : "Pause"; });
    $("#btn-raw-copy").addEventListener("click", function () {
      var txt = $("#raw-json").textContent;
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(txt).then(copied, function () { legacyCopy(txt); copied(); });
      else { legacyCopy(txt); copied(); }
    });
    function legacyCopy(txt) { try { var ta = document.createElement("textarea"); ta.value = txt; ta.style.position = "fixed"; ta.style.opacity = "0"; document.body.appendChild(ta); ta.select(); document.execCommand("copy"); document.body.removeChild(ta); } catch (e) {} }
    function copied() { var b = $("#btn-raw-copy"), o = b.textContent; b.textContent = "Copied"; setTimeout(function () { b.textContent = o; }, 1200); }

    syncConnectButton();
    render();
    connect();                  // auto-connect to the saved/default device on load
    requestAnimationFrame(tick);
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
