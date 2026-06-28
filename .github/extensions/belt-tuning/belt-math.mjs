import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";

const DEFAULTS = {
    nearThreshold: 0.55,
    objectFloor: 0.45,
    lowConf: 0.4,
    allClearHum: 30,
    curbStrength: 180,
    cautionCenter: 80,
    patternSteady: 0,
    patternPulse: 1,
    patternDouble: 2,
    patternApproach: 3,
    crowdSmoothingWindow: 5,
    crowdSaturationCutoff: 0.82,
    approachSpeedThreshold: 0.12,
    gapNudgeStrength: 0.35,
};

const TUNING_KEYS = [
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

export function patternName(pattern) {
    switch (Number(pattern) || 0) {
        case 1: return "caution";
        case 2: return "double";
        case 3: return "approach";
        default: return "steady";
    }
}

export function formatPacket(packet) {
    const p = packet.map((n, i) => (i === 3 ? patternName(n) : clampInt(n, 0, 255)));
    return `[${p[0]}, ${p[1]}, ${p[2]}, ${p[3]}]`;
}

export function clampInt(value, min, max) {
    const n = Number(value);
    if (!Number.isFinite(n)) return min;
    return Math.max(min, Math.min(max, Math.round(n)));
}

export function clampFloat(value, min, max, digits = 3) {
    const n = Number(value);
    if (!Number.isFinite(n)) return min;
    const clamped = Math.max(min, Math.min(max, n));
    const scale = 10 ** digits;
    return Math.round(clamped * scale) / scale;
}

export function readSourceConstantsFromText(kotlinText, firmwareText) {
    const parse = (re, fallback) => {
        const m = kotlinText.match(re);
        return m ? Number(m[1]) : fallback;
    };
    const parseFw = (re, fallback) => {
        const m = firmwareText.match(re);
        return m ? Number(m[1]) : fallback;
    };

    return {
        ...DEFAULTS,
        nearThreshold: parse(/const val NEAR_THRESHOLD = ([0-9.]+)f/, DEFAULTS.nearThreshold),
        objectFloor: parse(/const val OBJECT_NEAR_THRESHOLD = ([0-9.]+)f/, DEFAULTS.objectFloor),
        lowConf: parse(/const val LOW_CONF = ([0-9.]+)f/, DEFAULTS.lowConf),
        allClearHum: parse(/const val CLEAR_HUM = ([0-9]+)/, DEFAULTS.allClearHum),
        curbStrength: parse(/const val CURB_CENTER_MIN = ([0-9]+)/, DEFAULTS.curbStrength),
        cautionCenter: parse(/const val CAUTION_CENTER = ([0-9]+)/, DEFAULTS.cautionCenter),
        patternSteady: parse(/const val PATTERN_STEADY = ([0-9]+)/, DEFAULTS.patternSteady),
        patternPulse: parse(/const val PATTERN_PULSE = ([0-9]+)/, DEFAULTS.patternPulse),
        patternDouble: parse(/const val PATTERN_DOUBLE = ([0-9]+)/, DEFAULTS.patternDouble),
        patternApproach: parse(/const val PATTERN_APPROACH = ([0-9]+)/, DEFAULTS.patternApproach),
        crowdSmoothingWindow: parse(/const val CROWD_SMOOTHING_WINDOW = ([0-9]+)/, DEFAULTS.crowdSmoothingWindow),
        crowdSaturationCutoff: parse(/const val CROWD_SATURATION_CUTOFF = ([0-9.]+)f/, DEFAULTS.crowdSaturationCutoff),
        approachSpeedThreshold: parse(/const val APPROACH_SPEED_THRESHOLD = ([0-9.]+)f/, DEFAULTS.approachSpeedThreshold),
        gapNudgeStrength: parse(/const val GAP_NUDGE_STRENGTH = ([0-9.]+)f/, DEFAULTS.gapNudgeStrength),
        firmware: {
            pulseOn: parseFw(/const uint32_t PULSE_ON\s*=\s*([0-9]+)/, 300),
            pulseOff: parseFw(/const uint32_t PULSE_OFF\s*=\s*([0-9]+)/, 300),
            dblOn: parseFw(/const uint32_t DBL_ON\s*=\s*([0-9]+)/, 120),
            dblGap: parseFw(/const uint32_t DBL_GAP\s*=\s*([0-9]+)/, 120),
            dblPause: parseFw(/const uint32_t DBL_PAUSE\s*=\s*([0-9]+)/, 400),
            approOn1: parseFw(/const uint32_t APPR_ON_1\s*=\s*([0-9]+)/, 80),
            approGap1: parseFw(/const uint32_t APPR_GAP_1\s*=\s*([0-9]+)/, 180),
            approOn2: parseFw(/const uint32_t APPR_ON_2\s*=\s*([0-9]+)/, 100),
            approGap2: parseFw(/const uint32_t APPR_GAP_2\s*=\s*([0-9]+)/, 120),
            approOn3: parseFw(/const uint32_t APPR_ON_3\s*=\s*([0-9]+)/, 120),
            approGap3: parseFw(/const uint32_t APPR_GAP_3\s*=\s*([0-9]+)/, 80),
            approOn4: parseFw(/const uint32_t APPR_ON_4\s*=\s*([0-9]+)/, 150),
            approPause: parseFw(/const uint32_t APPR_PAUSE\s*=\s*([0-9]+)/, 280),
        },
    };
}

export function normalizeTuning(tuning, base = DEFAULTS) {
    const fallback = {
        ...DEFAULTS,
        ...pickTuningFields(base),
        lowConf: base?.lowConf ?? DEFAULTS.lowConf,
        cautionCenter: base?.cautionCenter ?? DEFAULTS.cautionCenter,
        patternSteady: base?.patternSteady ?? DEFAULTS.patternSteady,
        patternPulse: base?.patternPulse ?? DEFAULTS.patternPulse,
        patternDouble: base?.patternDouble ?? DEFAULTS.patternDouble,
        patternApproach: base?.patternApproach ?? DEFAULTS.patternApproach,
    };
    return {
        ...fallback,
        nearThreshold: clampFloat(tuning.nearThreshold ?? fallback.nearThreshold, 0, 1),
        objectFloor: clampFloat(tuning.objectFloor ?? fallback.objectFloor, 0, 1),
        allClearHum: clampInt(tuning.allClearHum ?? fallback.allClearHum, 0, 255),
        curbStrength: clampInt(tuning.curbStrength ?? fallback.curbStrength, 0, 255),
        crowdSmoothingWindow: clampInt(tuning.crowdSmoothingWindow ?? fallback.crowdSmoothingWindow, 1, 30),
        crowdSaturationCutoff: clampFloat(tuning.crowdSaturationCutoff ?? fallback.crowdSaturationCutoff, 0, 1),
        approachSpeedThreshold: clampFloat(tuning.approachSpeedThreshold ?? fallback.approachSpeedThreshold, 0, 1),
        gapNudgeStrength: clampFloat(tuning.gapNudgeStrength ?? fallback.gapNudgeStrength, 0, 1),
    };
}

export function defaultTuningFromSources(source) {
    return normalizeTuning(pickTuningFields(source));
}

export function pickTuningFields(source) {
    const out = {};
    for (const key of TUNING_KEYS) out[key] = source?.[key];
    return out;
}

export async function loadTuningState(filePath, source) {
    try {
        const raw = await readFile(filePath, "utf8");
        const parsed = JSON.parse(raw);
        return {
            ...defaultState(source),
            ...parsed,
            tuning: normalizeTuning(parsed.tuning ?? parsed, source),
        };
    } catch {
        return defaultState(source);
    }
}

export function defaultState(source) {
    return {
        version: 1,
        updatedAt: new Date().toISOString(),
        source,
        tuning: defaultTuningFromSources(source),
        activeScenario: "clear-path",
        manualPacket: [0, 0, 0, 0],
        livePacket: [0, 0, 0, 0],
        history: [],
        playback: { running: false, step: 0, scenario: null },
    };
}

export async function saveTuningState(filePath, state) {
    await mkdir(path.dirname(filePath), { recursive: true });
    await writeFile(filePath, JSON.stringify({
        version: state.version ?? 1,
        updatedAt: new Date().toISOString(),
        tuning: state.tuning,
        activeScenario: state.activeScenario,
        manualPacket: state.manualPacket,
        livePacket: state.livePacket,
        playback: state.playback,
    }, null, 2) + "\n", "utf8");
}

function round1(v) {
    return Math.round(v * 10) / 10;
}

function zoneIntensity(v, threshold) {
    const value = Number(v) || 0;
    if (value < threshold) return 0;
    return clampInt(((value - threshold) / (1 - threshold)) * 255, 0, 255);
}

function objectIntensity(nearness, floor) {
    const value = Number(nearness) || 0;
    if (value < floor) return 0;
    return Math.max(90, clampInt(((value - floor) / (1 - floor)) * 255, 0, 255));
}

export function summarizeScene(scene) {
    const activeObjects = scene.objects.filter((o) => (o.nearness ?? 0) >= 0.45).length;
    return {
        left: round1(scene.depth.left ?? 0),
        center: round1(scene.depth.center ?? 0),
        right: round1(scene.depth.right ?? 0),
        pathClear: !!scene.pathClear,
        conf: Number(scene.conf ?? 0),
        activeObjects,
        curbAhead: !!scene.depth.curbAhead,
        stepDown: !!scene.depth.stepDown,
        objects: scene.objects.map((o) => ({
            label: o.label,
            zone: o.zone,
            nearness: Number(o.nearness ?? 0),
            conf: Number(o.conf ?? 0),
        })),
    };
}

export function packetFromScene(scene, tuning, history = []) {
    const t = normalizeTuning(tuning);
    let left = zoneIntensity(scene.depth.left, t.nearThreshold);
    let center = zoneIntensity(scene.depth.center, t.nearThreshold);
    let right = zoneIntensity(scene.depth.right, t.nearThreshold);
    let pattern = t.patternSteady;

    for (const o of scene.objects ?? []) {
        const zone = (o.zone || "center").toLowerCase();
        const intensity = objectIntensity(o.nearness ?? 0, t.objectFloor);
        if (!intensity) continue;
        if (zone === "left") left = Math.max(left, intensity);
        else if (zone === "right") right = Math.max(right, intensity);
        else center = Math.max(center, intensity);
    }

    if (scene.depth.curbAhead || scene.depth.stepDown) {
        pattern = t.patternDouble;
        center = Math.max(center, t.curbStrength);
    }

    if ((scene.conf ?? 0) < t.lowConf) {
        pattern = t.patternPulse;
        center = Math.max(center, t.cautionCenter);
        left = 0;
        right = 0;
    }

    const recent = history.slice(-Math.max(1, t.crowdSmoothingWindow));
    const smoothingSamples = recent.length ? recent : [scene];
    const avg = ["left", "center", "right"].map((zone) => {
        const sum = smoothingSamples.reduce((acc, s) => acc + zoneIntensity(s.depth[zone], t.nearThreshold), 0);
        return sum / smoothingSamples.length;
    });
    const prev = recent.at(-1) ?? scene;
    const prevAvg = ["left", "center", "right"].map((zone) => zoneIntensity(prev.depth[zone], t.nearThreshold));
    const closing = avg.map((value, i) => value - prevAvg[i]);
    const strongestClosing = Math.max(...closing);

    const crowdScore = Math.min(1, (scene.objects?.length ?? 0) / Math.max(1, t.crowdSmoothingWindow));
    const denseCrowd = crowdScore >= t.crowdSaturationCutoff;
    if (denseCrowd) {
        const scale = 1 - (crowdScore - t.crowdSaturationCutoff) * 0.45;
        left = clampInt(left * scale, 0, 255);
        center = clampInt(center * scale, 0, 255);
        right = clampInt(right * scale, 0, 255);
    }

    if (pattern === t.patternSteady && strongestClosing >= t.approachSpeedThreshold * 255) {
        pattern = t.patternApproach;
    }

    const values = [left, center, right];
    const maxValue = Math.max(...values);
    const minIndex = values.indexOf(Math.min(...values));
    const activeZones = values.filter((v) => v > 0).length;
    if (activeZones >= 2 && maxValue > 0) {
        values[minIndex] = Math.max(values[minIndex], clampInt(maxValue * t.gapNudgeStrength, 0, 255));
    }

    if (values.every((v) => v === 0) && scene.pathClear && pattern === t.patternSteady) {
        values[0] = values[1] = values[2] = t.allClearHum;
    }

    return {
        packet: [values[0], values[1], values[2], pattern],
        patternName: patternName(pattern),
        crowdScore,
        closingSpeed: strongestClosing / 255,
        denseCrowd,
        summary: summarizeScene(scene),
    };
}

function sceneFrame(base, overrides = {}) {
    return {
        ts: Date.now(),
        depth: {
            left: 0,
            center: 0,
            right: 0,
            curbAhead: false,
            stepDown: false,
            ...(base.depth ?? {}),
            ...(overrides.depth ?? {}),
        },
        objects: [...(base.objects ?? []), ...(overrides.objects ?? [])],
        pathClear: overrides.pathClear ?? base.pathClear ?? false,
        ocr: overrides.ocr ?? base.ocr ?? { present: false, text: "" },
        conf: overrides.conf ?? base.conf ?? 0.9,
    };
}

const baseScenes = {
    clearPath: {
        name: "clear-path",
        label: "Clear path",
        frames: [sceneFrame({ pathClear: true, conf: 0.97 }, {
            depth: { left: 0.08, center: 0.08, right: 0.08 },
        })],
    },
    singlePersonClosing: {
        name: "single-person-closing",
        label: "Single person closing",
        frames: [
            sceneFrame({ pathClear: false, conf: 0.92 }, {
                depth: { center: 0.12 },
                objects: [{ label: "person", zone: "center", nearness: 0.18, conf: 0.92 }],
            }),
            sceneFrame({ pathClear: false, conf: 0.93 }, {
                depth: { center: 0.22 },
                objects: [{ label: "person", zone: "center", nearness: 0.35, conf: 0.93 }],
            }),
            sceneFrame({ pathClear: false, conf: 0.94 }, {
                depth: { center: 0.38 },
                objects: [{ label: "person", zone: "center", nearness: 0.55, conf: 0.95 }],
            }),
            sceneFrame({ pathClear: false, conf: 0.95 }, {
                depth: { center: 0.56 },
                objects: [{ label: "person", zone: "center", nearness: 0.73, conf: 0.96 }],
            }),
            sceneFrame({ pathClear: false, conf: 0.96 }, {
                depth: { center: 0.72 },
                objects: [{ label: "person", zone: "center", nearness: 0.9, conf: 0.97 }],
            }),
        ],
    },
    staticCrowd: {
        name: "static-crowd",
        label: "Static crowd",
        frames: [
            sceneFrame({ pathClear: false, conf: 0.9 }, {
                depth: { left: 0.54, center: 0.58, right: 0.56 },
                objects: [
                    { label: "person", zone: "left", nearness: 0.62, conf: 0.9 },
                    { label: "person", zone: "center", nearness: 0.66, conf: 0.92 },
                    { label: "person", zone: "right", nearness: 0.64, conf: 0.91 },
                ],
            }),
            sceneFrame({ pathClear: false, conf: 0.9 }, {
                depth: { left: 0.55, center: 0.58, right: 0.55 },
                objects: [
                    { label: "person", zone: "left", nearness: 0.63, conf: 0.9 },
                    { label: "person", zone: "center", nearness: 0.66, conf: 0.92 },
                    { label: "person", zone: "right", nearness: 0.64, conf: 0.91 },
                ],
            }),
        ],
    },
    crowdWithApproacher: {
        name: "crowd-with-one-approacher",
        label: "Crowd with one approacher",
        frames: [
            sceneFrame({ pathClear: false, conf: 0.9 }, {
                depth: { left: 0.58, center: 0.42, right: 0.54 },
                objects: [
                    { label: "person", zone: "left", nearness: 0.63, conf: 0.88 },
                    { label: "person", zone: "right", nearness: 0.68, conf: 0.9 },
                    { label: "person", zone: "center", nearness: 0.28, conf: 0.85 },
                ],
            }),
            sceneFrame({ pathClear: false, conf: 0.9 }, {
                depth: { left: 0.58, center: 0.48, right: 0.54 },
                objects: [
                    { label: "person", zone: "left", nearness: 0.63, conf: 0.88 },
                    { label: "person", zone: "right", nearness: 0.68, conf: 0.9 },
                    { label: "person", zone: "center", nearness: 0.5, conf: 0.86 },
                ],
            }),
            sceneFrame({ pathClear: false, conf: 0.91 }, {
                depth: { left: 0.59, center: 0.62, right: 0.56 },
                objects: [
                    { label: "person", zone: "left", nearness: 0.64, conf: 0.88 },
                    { label: "person", zone: "right", nearness: 0.68, conf: 0.9 },
                    { label: "person", zone: "center", nearness: 0.78, conf: 0.94 },
                ],
            }),
        ],
    },
};

export const scenarios = Object.values(baseScenes);

export function scenarioByName(name) {
    return scenarios.find((s) => s.name === name) ?? baseScenes.clearPath;
}

export function nextScenarioFrame(scenarioName, step, tuning, history = []) {
    const scenario = scenarioByName(scenarioName);
    const frame = scenario.frames[Math.min(step, scenario.frames.length - 1)];
    const preview = packetFromScene(frame, tuning, history);
    return { scenario, frame, preview };
}

export function withAppendedHistory(history, frame, limit = 20) {
    return [...history.slice(-(limit - 1)), frame];
}

export function updateKotlinSource(kotlinText, tuning) {
    const replacements = [
        ["NEAR_THRESHOLD", `${tuning.nearThreshold.toFixed(2)}f`],
        ["OBJECT_NEAR_THRESHOLD", `${tuning.objectFloor.toFixed(2)}f`],
        ["CLEAR_HUM", `${tuning.allClearHum}`],
        ["CURB_CENTER_MIN", `${tuning.curbStrength}`],
        ["PATTERN_APPROACH", "3"],
        ["CROWD_SMOOTHING_WINDOW", `${tuning.crowdSmoothingWindow}`],
        ["CROWD_SATURATION_CUTOFF", `${tuning.crowdSaturationCutoff.toFixed(2)}f`],
        ["APPROACH_SPEED_THRESHOLD", `${tuning.approachSpeedThreshold.toFixed(2)}f`],
        ["GAP_NUDGE_STRENGTH", `${tuning.gapNudgeStrength.toFixed(2)}f`],
    ];
    let text = kotlinText;
    for (const [name, value] of replacements) {
        const re = new RegExp(`(const val ${name} = )[^\\n]+`, "g");
        text = text.replace(re, `$1${value}`);
    }
    return text;
}

export function updateFirmwareSource(firmwareText, tuning) {
    let text = firmwareText;
    const replacements = [
        ["APPR_ON_1", "80"],
        ["APPR_GAP_1", "180"],
        ["APPR_ON_2", "100"],
        ["APPR_GAP_2", "120"],
        ["APPR_ON_3", "120"],
        ["APPR_GAP_3", "80"],
        ["APPR_ON_4", "150"],
        ["APPR_PAUSE", "280"],
    ];
    for (const [name, value] of replacements) {
        const re = new RegExp(`(const uint32_t ${name}\\s*=\\s*)[^;]+`, "g");
        text = text.replace(re, `$1${value}`);
    }
    return text;
}

export async function readRepoSources(repoRoot) {
    const [kotlinText, firmwareText] = await Promise.all([
        readFile(path.join(repoRoot, SOURCE_FILES.beltMapper), "utf8"),
        readFile(path.join(repoRoot, SOURCE_FILES.firmware), "utf8"),
    ]);
    return { kotlinText, firmwareText };
}

export function buildSnapshot(state) {
    return {
        version: state.version,
        updatedAt: state.updatedAt,
        tuning: state.tuning,
        activeScenario: state.activeScenario,
        manualPacket: state.manualPacket,
        livePacket: state.livePacket,
        playback: state.playback,
        source: state.source,
    };
}
