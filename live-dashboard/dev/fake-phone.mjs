/* ============================================================================
   SixthSense — "fake phone" dashboard feeder  (DEV / DEMO ONLY, not the device)

   Stands in for the Galaxy S25 Ultra so the live dashboard can be seen working
   with NO phone attached. It speaks the exact same wire protocol the on-device
   SceneSocket emits: a WebSocket server on :8080 that broadcasts SceneState JSON
   plus a `frame` (here a synthetic scene image as a data: URL) and real
   normalized `box` coords. The drawn object and the box coords are identical, so
   the dashboard's box overlay should land pixel-exact on the object — that's the
   proof that the real-YOLO-box rendering path is correct.

   Zero dependencies: minimal RFC 6455 server (handshake + text frames) and a
   from-scratch PNG encoder using only Node's built-in zlib. NEVER part of the
   assistive runtime or the airplane-mode demo — it's a bench tool.

   Run:  node live-dashboard/dev/fake-phone.mjs [port]   (default 8080)
   ========================================================================== */
import http from "node:http";
import zlib from "node:zlib";
import { createHash } from "node:crypto";

const PORT = parseInt(process.argv[2] || "8080", 10);
const W = 480, H = 640;            // portrait, like the phone's upright frame
const FPS = 8;

// ----------------------------------------------------------- WS server -------
const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const clients = new Set();

function wsFrame(str) {
  const payload = Buffer.from(str, "utf8");
  const len = payload.length;
  let header;
  if (len < 126) header = Buffer.from([0x81, len]);
  else if (len < 65536) { header = Buffer.alloc(4); header[0] = 0x81; header[1] = 126; header.writeUInt16BE(len, 2); }
  else { header = Buffer.alloc(10); header[0] = 0x81; header[1] = 127; header.writeUInt32BE(0, 2); header.writeUInt32BE(len >>> 0, 6); }
  return Buffer.concat([header, payload]);
}

const server = http.createServer((_req, res) => { res.writeHead(426); res.end("WebSocket only"); });
server.on("upgrade", (req, socket) => {
  const key = req.headers["sec-websocket-key"];
  if (!key) { socket.destroy(); return; }
  const accept = createHash("sha1").update(key + GUID).digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    "Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
  );
  clients.add(socket);
  socket.on("data", () => {});                 // ignore inbound (viz-only client)
  const drop = () => clients.delete(socket);
  socket.on("close", drop);
  socket.on("error", drop);
  socket.write(wsFrame(JSON.stringify(scene(Date.now()))));   // populate on connect
});
server.listen(PORT, () => {
  console.log(`[fake-phone] ws://localhost:${PORT}  (${W}x${H} @ ${FPS}fps) — Ctrl-C to stop`);
  console.log("[fake-phone] point the dashboard at this URL and press Connect");
});

setInterval(() => {
  if (!clients.size) return;
  const msg = wsFrame(JSON.stringify(scene(Date.now())));
  for (const s of clients) { try { s.write(msg); } catch { clients.delete(s); } }
}, Math.round(1000 / FPS));

// ------------------------------------------------ scene + box generation -----
const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

function scene(now) {
  const cyc = (now % 6000) / 6000;                  // 6s loop
  const cxN = 0.5 + 0.32 * Math.sin(cyc * 2 * Math.PI);          // sweep L<->R
  const nearness = clamp(0.5 + 0.45 * Math.sin(cyc * 2 * Math.PI * 1.3), 0, 1); // far<->near
  const bw = 0.14 + nearness * 0.22, bh = 0.20 + nearness * 0.30;
  const x1 = clamp(cxN - bw / 2, 0, 1), x2 = clamp(cxN + bw / 2, 0, 1);
  const y2 = clamp(0.55 + nearness * 0.40, 0, 1), y1 = clamp(y2 - bh, 0, 1);
  const zone = cxN < 1 / 3 ? "left" : cxN < 2 / 3 ? "center" : "right";
  const depth = {
    left: zone === "left" ? nearness : 0.15,
    center: zone === "center" ? nearness : 0.2,
    right: zone === "right" ? nearness : 0.15,
    curbAhead: false, stepDown: false,
  };
  const box = { x1, y1, x2, y2 };
  const png = "data:image/png;base64," + renderPng(box, nearness).toString("base64");
  return {
    ts: now,
    depth,
    objects: [{ label: "chair", zone, nearness, conf: 0.82, box }],
    pathClear: depth.center < 0.55,
    ocr: { present: false, text: "" },
    conf: 0.85,
    frame: png,
    frameRotation: 0,           // synthetic scene is already upright
  };
}

// ---------------------------------------------------- synthetic scene PNG ----
function renderPng(box, nearness) {
  const buf = Buffer.alloc(W * H * 4);
  const set = (x, y, r, g, b) => { if (x < 0 || y < 0 || x >= W || y >= H) return; const i = (y * W + x) * 4; buf[i] = r; buf[i + 1] = g; buf[i + 2] = b; buf[i + 3] = 255; };
  // corridor-ish vertical gradient (wall -> floor)
  for (let y = 0; y < H; y++) {
    const f = y / H, r = (40 + f * 70) | 0, g = (44 + f * 74) | 0, b = (52 + f * 78) | 0;
    for (let x = 0; x < W; x++) set(x, y, r, g, b);
  }
  // the "chair": fill the box region; color warms (red) as it gets nearer, so the
  // scene itself echoes the dashboard's green->red box — pure visual sugar.
  const ox1 = Math.round(box.x1 * W), oy1 = Math.round(box.y1 * H);
  const ox2 = Math.round(box.x2 * W), oy2 = Math.round(box.y2 * H);
  const cr = (110 + nearness * 120) | 0, cg = (96 - nearness * 50) | 0, cb = (70 - nearness * 40) | 0;
  for (let y = oy1; y < oy2; y++) for (let x = ox1; x < ox2; x++) set(x, y, cr, Math.max(cg, 0), Math.max(cb, 0));
  // darker border so the object edge is crisp under the overlay box
  for (let x = ox1; x < ox2; x++) { set(x, oy1, 30, 24, 20); set(x, oy2 - 1, 30, 24, 20); }
  for (let y = oy1; y < oy2; y++) { set(ox1, y, 30, 24, 20); set(ox2 - 1, y, 30, 24, 20); }
  return encodePng(buf, W, H);
}

// ----------------------------------------------- minimal PNG encoder ---------
const CRC = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return t; })();
function crc32(b) { let c = ~0; for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 0xff] ^ (c >>> 8); return ~c >>> 0; }
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, "ascii");
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}
function encodePng(rgba, w, h) {
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6; // 8-bit RGBA
  const stride = w * 4 + 1, raw = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) { raw[y * stride] = 0; rgba.copy(raw, y * stride + 1, y * w * 4, (y + 1) * w * 4); }
  const idat = zlib.deflateSync(raw, { level: 6 });
  return Buffer.concat([sig, chunk("IHDR", ihdr), chunk("IDAT", idat), chunk("IEND", Buffer.alloc(0))]);
}

process.on("SIGINT", () => { console.log("\n[fake-phone] bye"); process.exit(0); });
