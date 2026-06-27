import { useEffect, useMemo, useRef, useState } from "react";
import mockFrames from "./mockFrames.json";

// SceneState shape mirrors the Android `SceneState` (Gson JSON).
type DepthZones = {
  left: number;
  center: number;
  right: number;
  curbAhead?: boolean;
  stepDown?: boolean;
};
type DetectedObj = { label: string; zone: string; nearness: number; conf: number };
type Ocr = { present: boolean; text: string };
type SceneState = {
  ts: number;
  depth: DepthZones;
  objects: DetectedObj[];
  pathClear: boolean;
  ocr: Ocr;
  conf: number;
  belt: number[];
};

type Mode = "live" | "mock" | "connecting";

const frames = mockFrames as SceneState[];

export default function App() {
  const [phoneIp, setPhoneIp] = useState("192.168.1.50");
  const [connectKey, setConnectKey] = useState(0); // bump to (re)connect
  const [mode, setMode] = useState<Mode>("connecting");
  const [scene, setScene] = useState<SceneState>(frames[0]);
  const mockTimer = useRef<number | null>(null);

  useEffect(() => {
    let ws: WebSocket | null = null;
    let cancelled = false;
    setMode("connecting");

    const startMock = () => {
      if (cancelled) return;
      setMode("mock");
      let i = 0;
      stopMock();
      mockTimer.current = window.setInterval(() => {
        i = (i + 1) % frames.length;
        setScene({ ...frames[i], ts: Date.now() });
      }, 1500);
    };

    const stopMock = () => {
      if (mockTimer.current !== null) {
        window.clearInterval(mockTimer.current);
        mockTimer.current = null;
      }
    };

    try {
      ws = new WebSocket(`ws://${phoneIp}:8080`);
      const failTimer = window.setTimeout(() => {
        if (ws && ws.readyState !== WebSocket.OPEN) {
          ws.close();
          startMock();
        }
      }, 2500);

      ws.onopen = () => {
        window.clearTimeout(failTimer);
        stopMock();
        if (!cancelled) setMode("live");
      };
      ws.onmessage = (ev) => {
        try {
          setScene(JSON.parse(ev.data) as SceneState);
        } catch {
          /* ignore malformed frame */
        }
      };
      ws.onerror = () => {
        window.clearTimeout(failTimer);
        startMock();
      };
      ws.onclose = () => {
        if (!cancelled && mode === "live") startMock();
      };
    } catch {
      startMock();
    }

    return () => {
      cancelled = true;
      stopMock();
      ws?.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connectKey]);

  const belt = scene.belt ?? [];
  const patternLabel = useMemo(() => {
    switch (belt[3]) {
      case 1: return "single / caution pulse";
      case 2: return "double pulse (curb)";
      default: return "steady";
    }
  }, [belt]);

  return (
    <div className="app">
      <header>
        <h1>SixthSense</h1>
        <span className={`mode mode-${mode}`}>{mode.toUpperCase()}</span>
      </header>
      <p className="subtitle">Visualization only — no AI runs in this dashboard.</p>

      <div className="connbar">
        <label>
          Phone IP{" "}
          <input value={phoneIp} onChange={(e) => setPhoneIp(e.target.value)} />
        </label>
        <button onClick={() => setConnectKey((k) => k + 1)}>Connect ws://{phoneIp}:8080</button>
      </div>

      <section className="zones">
        <Zone name="LEFT" value={scene.depth.left} />
        <Zone name="CENTER" value={scene.depth.center} curb={scene.depth.curbAhead} />
        <Zone name="RIGHT" value={scene.depth.right} />
      </section>

      <section className="grid">
        <Card title="Path clear">
          <span className={scene.pathClear ? "ok" : "warn"}>
            {scene.pathClear ? "CLEAR" : "BLOCKED"}
          </span>
        </Card>
        <Card title="Confidence">{(scene.conf * 100).toFixed(0)}%</Card>
        <Card title="OCR">{scene.ocr?.present ? `"${scene.ocr.text}"` : "—"}</Card>
        <Card title="Belt packet">
          [{belt.join(", ")}]<br />
          <small>{patternLabel}</small>
        </Card>
      </section>

      <section>
        <h2>Objects</h2>
        {scene.objects.length === 0 ? (
          <p className="muted">none</p>
        ) : (
          <ul className="objects">
            {scene.objects.map((o, i) => (
              <li key={i}>
                <strong>{o.label}</strong> · {o.zone} · near {o.nearness.toFixed(2)} · conf{" "}
                {(o.conf * 100).toFixed(0)}%
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function Zone({ name, value, curb, }: { name: string; value: number; curb?: boolean }) {
  const pct = Math.round(Math.min(1, Math.max(0, value)) * 100);
  const hot = value >= 0.55;
  return (
    <div className={`zone ${hot ? "zone-hot" : ""}`}>
      <div className="zone-name">{name}</div>
      <div className="meter">
        <div className="meter-fill" style={{ height: `${pct}%` }} />
      </div>
      <div className="zone-val">{value.toFixed(2)}</div>
      {curb ? <div className="zone-curb">CURB</div> : null}
    </div>
  );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="card">
      <div className="card-title">{title}</div>
      <div className="card-body">{children}</div>
    </div>
  );
}
