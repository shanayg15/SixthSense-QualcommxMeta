import { createServer } from "node:http";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { existsSync } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { CanvasError, createCanvas, joinSession } from "@github/copilot-sdk/extension";
import {
    buildSnapshot,
    clampInt,
    defaultState,
    formatPacket,
    normalizeTuning,
    packetFromScene,
    patternName,
    readRepoSources,
    readSourceConstantsFromText,
    saveTuningState,
    scenarioByName,
    scenarios,
    updateFirmwareSource,
    updateKotlinSource,
    withAppendedHistory,
} from "./belt-math.mjs";

const execFileAsync = promisify(execFile);
const ACTION_NAMES = [
    "nearThreshold",
    "objectFloor",
    "allClearHum",
    "curbStrength",
    "crowdSmoothingWindow",
    "crowdSaturationCutoff",
    "approachSpeedThreshold",
    "gapNudgeStrength",
];

const SOURCE_FILES = {
    beltMapper: "android/app/src/main/java/com/sixthsense/core/BeltMapper.kt",
    firmware: "firmware/esp32_belt/esp32_belt.ino",
};

const ARTIFACT_RELATIVE = path.join("artifacts", "tuning.json");

let runtimePromise;

function pageHtml() {
    return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Belt Tuning</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: var(--background-color-default, #111318);
      --panel: color-mix(in srgb, var(--bg) 82%, #1b1f27);
      --panel-2: color-mix(in srgb, var(--bg) 72%, #242a35);
      --text: var(--text-color-default, #e7eaf0);
      --muted: var(--text-color-muted, #9da7ba);
      --border: var(--border-color-default, rgba(255,255,255,0.08));
      --accent: var(--true-color-blue, #3b82f6);
      --accent-2: var(--true-color-red, #ef4444);
      --warn: #f59e0b;
      --good: #10b981;
    }
    html, body {
      margin: 0;
      min-height: 100%;
      background: var(--bg);
      color: var(--text);
      font: 14px/1.4 var(--font-sans, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif);
    }
    body {
      padding: 16px;
    }
    .app {
      display: grid;
      gap: 12px;
    }
    .header, .panel, .footer {
      background: linear-gradient(180deg, color-mix(in srgb, var(--panel) 94%, white 6%), var(--panel));
      border: 1px solid var(--border);
      border-radius: 14px;
      box-shadow: 0 12px 32px rgba(0,0,0,.22);
    }
    .header {
      padding: 14px 16px;
      display: flex;
      justify-content: space-between;
      gap: 12px;
      align-items: start;
    }
    h1 {
      margin: 0 0 4px;
      font-size: 18px;
      font-weight: 700;
    }
    .subtle, .meta {
      color: var(--muted);
      font-size: 12px;
    }
    .grid {
      display: grid;
      grid-template-columns: 1.1fr .9fr;
      gap: 12px;
    }
    .panel {
      padding: 14px;
    }
    .panel h2 {
      margin: 0 0 10px;
      font-size: 13px;
      text-transform: uppercase;
      letter-spacing: .08em;
      color: var(--muted);
    }
    .slider {
      display: grid;
      gap: 6px;
      margin: 0 0 12px;
    }
    .slider label {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      font-size: 13px;
    }
    .slider input[type="range"] {
      width: 100%;
      accent-color: var(--accent);
    }
    .value {
      font-variant-numeric: tabular-nums;
      color: var(--muted);
    }
    .meters {
      display: grid;
      gap: 12px;
    }
    .meterRow {
      display: grid;
      gap: 6px;
    }
    .meterLabel {
      display: flex;
      justify-content: space-between;
      font-size: 13px;
    }
    .bar {
      position: relative;
      height: 14px;
      border-radius: 999px;
      overflow: hidden;
      background: var(--panel-2);
      border: 1px solid var(--border);
    }
    .fill {
      position: absolute;
      inset: 0 auto 0 0;
      width: 0%;
      border-radius: inherit;
      background: linear-gradient(90deg, var(--accent), #8b5cf6);
      transition: width .2s ease;
    }
    .fill.center { background: linear-gradient(90deg, #22c55e, #84cc16); }
    .fill.right { background: linear-gradient(90deg, #f59e0b, #fb7185); }
    .pattern {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 6px 10px;
      border-radius: 999px;
      background: var(--panel-2);
      border: 1px solid var(--border);
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: .06em;
      font-size: 11px;
    }
    .pattern.approach { color: #ffb347; }
    .pattern.double { color: #ff7a7a; }
    .pattern.caution { color: #ffd166; }
    .pattern.steady { color: var(--good); }
    .row {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    button {
      border: 1px solid var(--border);
      background: var(--panel-2);
      color: var(--text);
      border-radius: 10px;
      padding: 8px 10px;
      font: inherit;
      cursor: pointer;
    }
    button.primary {
      background: linear-gradient(180deg, color-mix(in srgb, var(--accent) 25%, var(--panel-2)), var(--panel-2));
      border-color: color-mix(in srgb, var(--accent) 45%, var(--border));
    }
    .footer {
      display: grid;
      gap: 6px;
      padding: 12px 14px;
      font-variant-numeric: tabular-nums;
    }
    code {
      font-family: var(--font-mono, SFMono-Regular, Consolas, monospace);
    }
  </style>
</head>
<body data-color-mode="dark" data-theme-tone="dark">
  <main class="app">
    <div class="header">
      <div>
        <h1>Belt Tuning</h1>
        <div class="subtle">Crowd-aware haptics tuning for the SixthSense belt.</div>
      </div>
      <div class="meta" id="status">Loading…</div>
    </div>

    <div class="grid">
      <section class="panel">
        <h2>Tuning constants</h2>
        <div id="sliders"></div>
      </section>

      <section class="panel">
        <h2>Live belt readout</h2>
        <div class="meters">
          <div class="meterRow">
            <div class="meterLabel"><span>Left</span><span id="leftValue">0</span></div>
            <div class="bar"><div class="fill left" id="leftBar"></div></div>
          </div>
          <div class="meterRow">
            <div class="meterLabel"><span>Center</span><span id="centerValue">0</span></div>
            <div class="bar"><div class="fill center" id="centerBar"></div></div>
          </div>
          <div class="meterRow">
            <div class="meterLabel"><span>Right</span><span id="rightValue">0</span></div>
            <div class="bar"><div class="fill right" id="rightBar"></div></div>
          </div>
        </div>

        <div style="height:12px"></div>
        <div class="row" id="patternRow"></div>
        <div style="height:12px"></div>
        <div class="meta" id="packetLine"></div>
        <div class="meta" id="sceneLine"></div>
      </section>
    </div>

    <section class="panel">
      <h2>Test scenes</h2>
      <div class="row" id="scenarioButtons"></div>
    </section>

    <section class="panel">
      <h2>Actions</h2>
      <div class="row">
        <button class="primary" id="writeBackBtn">Write back</button>
        <button id="resetBtn">Reset to source</button>
      </div>
    </section>

    <section class="footer">
      <div><strong>Current packet:</strong> <code id="packetCode">[0, 0, 0, steady]</code></div>
      <div><strong>Source:</strong> <code id="sourceCode"></code></div>
    </section>
  </main>

  <script type="module">
    const slidersEl = document.getElementById("sliders");
    const scenarioButtonsEl = document.getElementById("scenarioButtons");
    const statusEl = document.getElementById("status");
    const packetCodeEl = document.getElementById("packetCode");
    const sourceCodeEl = document.getElementById("sourceCode");
    const packetLineEl = document.getElementById("packetLine");
    const sceneLineEl = document.getElementById("sceneLine");
    const patternRowEl = document.getElementById("patternRow");
    const patternLabels = ["steady", "caution", "double", "approach"];

    const bars = {
      left: document.getElementById("leftBar"),
      center: document.getElementById("centerBar"),
      right: document.getElementById("rightBar"),
    };
    const values = {
      left: document.getElementById("leftValue"),
      center: document.getElementById("centerValue"),
      right: document.getElementById("rightValue"),
    };

    const CONSTANTS = [
      { key: "nearThreshold", label: "Depth near-threshold", min: 0, max: 1, step: 0.01 },
      { key: "objectFloor", label: "Object floor", min: 0, max: 1, step: 0.01 },
      { key: "allClearHum", label: "All-clear hum", min: 0, max: 255, step: 1 },
      { key: "curbStrength", label: "Curb strength", min: 0, max: 255, step: 1 },
      { key: "crowdSmoothingWindow", label: "Crowd smoothing window", min: 1, max: 30, step: 1 },
      { key: "crowdSaturationCutoff", label: "Crowd saturation cutoff", min: 0, max: 1, step: 0.01 },
      { key: "approachSpeedThreshold", label: "Approach threshold", min: 0, max: 1, step: 0.01 },
      { key: "gapNudgeStrength", label: "Gap nudge strength", min: 0, max: 1, step: 0.01 },
    ];

    let state = null;
    let loading = false;

    async function api(path, body, method = "POST") {
      const res = await fetch(path, {
        method,
        headers: body ? { "Content-Type": "application/json" } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      });
      if (!res.ok) throw new Error(await res.text());
      return await res.json();
    }

    function renderControls() {
      slidersEl.innerHTML = "";
      for (const item of CONSTANTS) {
        const wrap = document.createElement("div");
        wrap.className = "slider";
        wrap.innerHTML = \`
          <label><span>\${item.label}</span><span class="value" id="\${item.key}Value"></span></label>
          <input id="\${item.key}" type="range" min="\${item.min}" max="\${item.max}" step="\${item.step}">
        \`;
        slidersEl.appendChild(wrap);
        const input = wrap.querySelector("input");
        const valueEl = wrap.querySelector("label .value");
        input.addEventListener("input", async () => {
          valueEl.textContent = input.value;
        });
        input.addEventListener("change", async () => {
          await api("/api/set-constant", { name: item.key, value: Number(input.value) });
        });
      }
    }

    function renderScenarios() {
      scenarioButtonsEl.innerHTML = "";
      const items = [
        ["single-person-closing", "Single person closing"],
        ["static-crowd", "Static crowd"],
        ["crowd-with-one-approacher", "Crowd with one approacher"],
        ["clear-path", "Clear path"],
      ];
      for (const [name, label] of items) {
        const button = document.createElement("button");
        button.textContent = label;
        button.addEventListener("click", async () => {
          await api("/api/play-scenario", { scenario: name });
        });
        scenarioButtonsEl.appendChild(button);
      }
    }

    function renderPattern(packet) {
      const pattern = packet[3] ?? 0;
      const label = patternLabels[pattern] || "steady";
      patternRowEl.innerHTML = "";
      const chip = document.createElement("span");
      chip.className = \`pattern \${label}\`;
      chip.textContent = label;
      patternRowEl.appendChild(chip);
      if (label === "approach") {
        const pulse = document.createElement("span");
        pulse.className = "meta";
        pulse.textContent = "Escalating cadence";
        patternRowEl.appendChild(pulse);
      }
    }

    function renderState(next) {
      state = next;
      statusEl.textContent = next.playback?.running ? \`Playing \${next.activeScenario}\` : "Ready";
      sourceCodeEl.textContent = \`Kotlin \${next.source?.kotlinPath ?? ""} • firmware \${next.source?.firmwarePath ?? ""}\`;
      for (const item of CONSTANTS) {
        const input = document.getElementById(item.key);
        const valueEl = document.getElementById(\`\${item.key}Value\`);
        if (!input || !valueEl) continue;
        input.value = next.tuning[item.key];
        valueEl.textContent = next.tuning[item.key];
      }
      const packet = next.livePacket ?? [0, 0, 0, 0];
      const [l, c, r] = packet;
      const max = 255;
      values.left.textContent = l;
      values.center.textContent = c;
      values.right.textContent = r;
      bars.left.style.width = \`\${(l / max) * 100}%\`;
      bars.center.style.width = \`\${(c / max) * 100}%\`;
      bars.right.style.width = \`\${(r / max) * 100}%\`;
      packetCodeEl.textContent = \`[\${l}, \${c}, \${r}, \${patternLabels[packet[3] ?? 0] || "steady"}]\`;
      packetLineEl.textContent = \`Pattern: \${patternLabels[packet[3] ?? 0] || "steady"} • crowd score \${(next.liveMeta?.crowdScore ?? 0).toFixed(2)} • closing \${(next.liveMeta?.closingSpeed ?? 0).toFixed(2)}\`;
      sceneLineEl.textContent = \`Scenario: \${next.activeScenario} • \${next.sceneSummary?.objects?.length ?? 0} tracked objects\`;
      renderPattern(packet);
    }

    async function refresh() {
      const res = await fetch("/state");
      renderState(await res.json());
    }

    renderControls();
    renderScenarios();
    document.getElementById("writeBackBtn").addEventListener("click", async () => {
      await api("/api/write-back", {});
    });
    document.getElementById("resetBtn").addEventListener("click", async () => {
      await api("/api/reset", {});
    });

    const source = new EventSource("/events");
    source.addEventListener("state", (event) => renderState(JSON.parse(event.data)));
    source.addEventListener("ready", () => refresh());
    source.addEventListener("error", () => {
      statusEl.textContent = "Reconnecting…";
    });

    refresh();
  </script>
</body>
</html>`;
}

function safeJsonParse(text, fallback = null) {
    try {
        return JSON.parse(text);
    } catch {
        return fallback;
    }
}

function resolveAdbPath() {
    const candidates = [];
    if (process.env.ANDROID_HOME) {
        candidates.push(path.join(process.env.ANDROID_HOME, "platform-tools", "adb"));
    }
    candidates.push(path.join(process.env.HOME ?? "", "Library", "Android", "sdk", "platform-tools", "adb"));
    candidates.push("adb");
    for (const candidate of candidates) {
        try {
            if (candidate === "adb") return candidate;
            if (existsSync(candidate)) return candidate;
        } catch {
            // ignore and keep searching
        }
    }
    return "adb";
}

async function runAdb(args) {
    const adb = resolveAdbPath();
    try {
        return await execFileAsync(adb, args, { timeout: 120000 });
    } catch (error) {
        const message = error?.message || String(error);
        throw new Error(`adb failed: ${message}`);
    }
}

async function sendPacket(packet) {
    const [l, c, r, p] = packet.map((n, i) => i === 3 ? clampInt(n, 0, 3) : clampInt(n, 0, 255));
    await runAdb([
        "shell", "am", "broadcast",
        "-a", "com.sixthsense.DEBUG_BELT",
        "--ei", "l", String(l),
        "--ei", "c", String(c),
        "--ei", "r", String(r),
        "--ei", "p", String(p),
    ]);
    return [l, c, r, p];
}

async function initRuntime() {
    const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
    const workspaceRoot = session.workspacePath ?? process.cwd();
    const artifactPath = path.join(workspaceRoot, ARTIFACT_RELATIVE);
    const { kotlinText, firmwareText } = await readRepoSources(repoRoot);
    const source = readSourceConstantsFromText(kotlinText, firmwareText);
    const state = await (async () => {
        try {
            const raw = await readFile(artifactPath, "utf8");
            const loaded = safeJsonParse(raw, {});
            const merged = defaultState(source);
            merged.tuning = normalizeTuning(loaded.tuning ?? loaded, source);
            merged.activeScenario = loaded.activeScenario ?? merged.activeScenario;
            merged.manualPacket = loaded.manualPacket ?? merged.manualPacket;
            merged.livePacket = loaded.livePacket ?? merged.livePacket;
            merged.playback = loaded.playback ?? merged.playback;
            merged.history = loaded.history ?? merged.history;
            merged.source = {
                kotlinPath: SOURCE_FILES.beltMapper,
                firmwarePath: SOURCE_FILES.firmware,
                ...source,
            };
            return merged;
        } catch {
            const merged = defaultState(source);
            merged.source = {
                kotlinPath: SOURCE_FILES.beltMapper,
                firmwarePath: SOURCE_FILES.firmware,
                ...source,
            };
            return merged;
        }
    })();

    return {
        repoRoot,
        artifactPath,
        source,
        state,
        serverEntries: new Map(),
    };
}

function stateSnapshot(runtime) {
    const liveMeta = runtime.state.liveMeta ?? { crowdScore: 0, closingSpeed: 0 };
    return {
        ...buildSnapshot(runtime.state),
        source: {
            kotlinPath: SOURCE_FILES.beltMapper,
            firmwarePath: SOURCE_FILES.firmware,
            ...runtime.source,
        },
        liveMeta,
        sceneSummary: runtime.state.sceneSummary ?? { objects: [] },
    };
}

function emitState(runtime) {
    const payload = JSON.stringify(stateSnapshot(runtime));
    for (const { clients } of runtime.serverEntries.values()) {
        for (const res of clients) {
            res.write(`event: state\ndata: ${payload}\n\n`);
        }
    }
}

async function persist(runtime) {
    await saveTuningState(runtime.artifactPath, runtime.state);
}

function currentSceneForState(runtime) {
    const scenario = scenarioByName(runtime.state.activeScenario);
    const frame = scenario.frames[Math.min(runtime.state.playback?.step ?? 0, scenario.frames.length - 1)];
    return { scenario, frame };
}

function updateLivePacket(runtime, packet, meta = {}) {
    runtime.state.livePacket = packet;
    runtime.state.liveMeta = meta;
    runtime.state.updatedAt = new Date().toISOString();
    runtime.state.sceneSummary = meta.summary ?? runtime.state.sceneSummary ?? { objects: [] };
}

async function setConstant(runtime, name, value) {
    if (!ACTION_NAMES.includes(name)) {
        throw new CanvasError("invalid_constant", `Unknown constant: ${name}`);
    }
    const next = normalizeTuning({ ...runtime.state.tuning, [name]: value }, runtime.state.tuning);
    runtime.state.tuning = next;
    runtime.state.manualPacket = runtime.state.manualPacket ?? [0, 0, 0, 0];
    const scene = currentSceneForState(runtime).frame;
    const preview = packetFromScene(scene, next, runtime.state.history);
    updateLivePacket(runtime, preview.packet, preview);
    await persist(runtime);
    emitState(runtime);
    return { tuning: next, livePacket: runtime.state.livePacket, pattern: patternName(preview.packet[3]) };
}

async function runScenario(runtime, scenarioName, pushToBelt = true) {
    const scenario = scenarioByName(scenarioName);
    runtime.state.activeScenario = scenario.name;
    runtime.state.playback = { running: true, step: 0, scenario: scenario.name };
    runtime.state.history = [];
    emitState(runtime);

    for (let step = 0; step < scenario.frames.length; step++) {
        runtime.state.playback = { running: true, step, scenario: scenario.name };
        const frame = scenario.frames[step];
        const preview = packetFromScene(frame, runtime.state.tuning, runtime.state.history);
        runtime.state.history = withAppendedHistory(runtime.state.history, frame);
        updateLivePacket(runtime, preview.packet, preview);
        if (pushToBelt) {
            await sendPacket(preview.packet);
        }
        emitState(runtime);
        await new Promise((resolve) => setTimeout(resolve, 220));
    }

    runtime.state.playback = { running: false, step: scenario.frames.length - 1, scenario: scenario.name };
    await persist(runtime);
    emitState(runtime);
    return { scenario: scenario.name, packet: runtime.state.livePacket, pattern: patternName(runtime.state.livePacket[3]) };
}

async function writeBack(runtime) {
    const kotlinPath = path.join(runtime.repoRoot, SOURCE_FILES.beltMapper);
    const firmwarePath = path.join(runtime.repoRoot, SOURCE_FILES.firmware);
    const [kotlinText, firmwareText] = await Promise.all([
        readFile(kotlinPath, "utf8"),
        readFile(firmwarePath, "utf8"),
    ]);
    const updatedKotlin = updateKotlinSource(kotlinText, runtime.state.tuning);
    const updatedFirmware = updateFirmwareSource(firmwareText, runtime.state.tuning);
    await Promise.all([
        mkdir(path.dirname(kotlinPath), { recursive: true }),
        mkdir(path.dirname(firmwarePath), { recursive: true }),
    ]);
    await Promise.all([
        writeFile(kotlinPath, updatedKotlin, "utf8"),
        writeFile(firmwarePath, updatedFirmware, "utf8"),
    ]);
    runtime.source = readSourceConstantsFromText(updatedKotlin, updatedFirmware);
    runtime.state.source = {
        kotlinPath: SOURCE_FILES.beltMapper,
        firmwarePath: SOURCE_FILES.firmware,
        ...runtime.source,
    };
    await persist(runtime);
    emitState(runtime);
    return {
        kotlinPath: SOURCE_FILES.beltMapper,
        firmwarePath: SOURCE_FILES.firmware,
        tuning: runtime.state.tuning,
    };
}

async function createServerEntry(runtime, instanceId) {
    const clients = new Set();
    const server = createServer(async (req, res) => {
        const url = new URL(req.url, "http://127.0.0.1");
        if (req.method === "GET" && url.pathname === "/") {
            res.setHeader("Content-Type", "text/html; charset=utf-8");
            res.end(pageHtml());
            return;
        }
        if (req.method === "GET" && url.pathname === "/state") {
            res.setHeader("Content-Type", "application/json; charset=utf-8");
            res.end(JSON.stringify(stateSnapshot(runtime)));
            return;
        }
        if (req.method === "GET" && url.pathname === "/events") {
            res.writeHead(200, {
                "Content-Type": "text/event-stream",
                "Cache-Control": "no-cache",
                Connection: "keep-alive",
            });
            res.write(`event: ready\ndata: {}\n\n`);
            clients.add(res);
            req.on("close", () => clients.delete(res));
            return;
        }
        if (req.method === "POST" && url.pathname === "/api/set-constant") {
            const body = await readBody(req);
            const result = await setConstant(runtime, body.name, body.value);
            res.setHeader("Content-Type", "application/json; charset=utf-8");
            res.end(JSON.stringify(result));
            return;
        }
        if (req.method === "POST" && url.pathname === "/api/play-scenario") {
            const body = await readBody(req);
            const result = await runScenario(runtime, body.scenario);
            res.setHeader("Content-Type", "application/json; charset=utf-8");
            res.end(JSON.stringify(result));
            return;
        }
        if (req.method === "POST" && url.pathname === "/api/write-back") {
            const result = await writeBack(runtime);
            res.setHeader("Content-Type", "application/json; charset=utf-8");
            res.end(JSON.stringify(result));
            return;
        }
        if (req.method === "POST" && url.pathname === "/api/reset") {
            runtime.state = defaultState(runtime.source);
            runtime.state.source = {
                kotlinPath: SOURCE_FILES.beltMapper,
                firmwarePath: SOURCE_FILES.firmware,
                ...runtime.source,
            };
            await persist(runtime);
            emitState(runtime);
            res.setHeader("Content-Type", "application/json; charset=utf-8");
            res.end(JSON.stringify(stateSnapshot(runtime)));
            return;
        }
        if (req.method === "POST" && url.pathname === "/api/send-test-packet") {
            const body = await readBody(req);
            const packet = await sendPacket([body.left, body.center, body.right, body.pattern]);
            runtime.state.manualPacket = packet;
            runtime.state.activeScenario = "manual";
            updateLivePacket(runtime, packet, {
                crowdScore: 0,
                closingSpeed: 0,
                summary: { objects: [], pathClear: false, conf: 1 },
            });
            await persist(runtime);
            emitState(runtime);
            res.setHeader("Content-Type", "application/json; charset=utf-8");
            res.end(JSON.stringify({ packet, pattern: patternName(packet[3]) }));
            return;
        }
        res.statusCode = 404;
        res.end("Not found");
    });

    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    const port = typeof address === "object" && address ? address.port : 0;
    runtime.serverEntries.set(instanceId, { server, clients, url: `http://127.0.0.1:${port}/` });
    return runtime.serverEntries.get(instanceId);
}

function readBody(req) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        req.on("data", (chunk) => chunks.push(chunk));
        req.on("end", () => resolve(safeJsonParse(Buffer.concat(chunks).toString("utf8"), {})));
        req.on("error", reject);
    });
}

function closeServer(runtime, instanceId) {
    const entry = runtime.serverEntries.get(instanceId);
    if (!entry) return;
    runtime.serverEntries.delete(instanceId);
    for (const res of entry.clients) {
        try { res.end(); } catch { /* noop */ }
    }
    entry.server.close(() => {});
}

async function getRuntime() {
    if (!runtimePromise) {
        runtimePromise = initRuntime();
    }
    return runtimePromise;
}

const session = await joinSession({
    canvases: [
        createCanvas({
            id: "belt-tuning",
            displayName: "Belt Tuning",
            description: "Tune the SixthSense belt, preview crowd-aware haptics, and write tuned constants back to source.",
            actions: [
                {
                    name: "get_constants",
                    description: "Read the current belt constants and firmware cadence from the repo.",
                    handler: async () => {
                        const runtime = await getRuntime();
                        return {
                            source: runtime.source,
                            tuning: runtime.state.tuning,
                            scenarios: scenarios.map((s) => ({ name: s.name, label: s.label })),
                        };
                    },
                },
                {
                    name: "set_constant",
                    description: "Change a tuning constant in the session state.",
                    inputSchema: {
                        type: "object",
                        additionalProperties: false,
                        required: ["name", "value"],
                        properties: {
                            name: { type: "string", enum: ACTION_NAMES },
                            value: { type: "number" },
                        },
                    },
                    handler: async (ctx) => {
                        const runtime = await getRuntime();
                        return await setConstant(runtime, ctx.input.name, ctx.input.value);
                    },
                },
                {
                    name: "send_test_packet",
                    description: "Send a raw 4-byte packet to the belt over the existing debug BLE path.",
                    inputSchema: {
                        type: "object",
                        additionalProperties: false,
                        required: ["left", "center", "right", "pattern"],
                        properties: {
                            left: { type: "integer", minimum: 0, maximum: 255 },
                            center: { type: "integer", minimum: 0, maximum: 255 },
                            right: { type: "integer", minimum: 0, maximum: 255 },
                            pattern: { type: "integer", minimum: 0, maximum: 3 },
                        },
                    },
                    handler: async (ctx) => {
                        const runtime = await getRuntime();
                        const packet = await sendPacket([ctx.input.left, ctx.input.center, ctx.input.right, ctx.input.pattern]);
                        runtime.state.manualPacket = packet;
                        runtime.state.activeScenario = "manual";
                        updateLivePacket(runtime, packet, {
                            crowdScore: 0,
                            closingSpeed: 0,
                            summary: { objects: [], pathClear: false, conf: 1 },
                        });
                        await persist(runtime);
                        emitState(runtime);
                        return { packet, pattern: patternName(packet[3]), formatted: formatPacket(packet) };
                    },
                },
                {
                    name: "play_scenario",
                    description: "Play one of the scripted belt scenes.",
                    inputSchema: {
                        type: "object",
                        additionalProperties: false,
                        required: ["scenario"],
                        properties: {
                            scenario: {
                                type: "string",
                                enum: scenarios.map((s) => s.name),
                            },
                        },
                    },
                    handler: async (ctx) => {
                        const runtime = await getRuntime();
                        const result = await runScenario(runtime, ctx.input.scenario, true);
                        return result;
                    },
                },
                {
                    name: "write_back",
                    description: "Save the tuned constants back into the Kotlin and firmware sources.",
                    handler: async () => {
                        const runtime = await getRuntime();
                        return await writeBack(runtime);
                    },
                },
            ],
            open: async (ctx) => {
                const runtime = await getRuntime();
                let entry = runtime.serverEntries.get(ctx.instanceId);
                if (!entry) {
                    entry = await createServerEntry(runtime, ctx.instanceId);
                }
                return {
                    title: "Belt Tuning",
                    status: `Ready • ${patternName(runtime.state.livePacket[3] ?? 0)}`,
                    url: entry.url,
                };
            },
            onClose: async (ctx) => {
                const runtime = await getRuntime();
                closeServer(runtime, ctx.instanceId);
            },
        }),
    ],
});

export { session };
