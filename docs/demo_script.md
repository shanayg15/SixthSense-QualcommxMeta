# SixthSense — 5-minute demo script

**Roles:** Narrator (explains), Walker (wears belt + chest phone, optionally blindfolded),
Operator (watches the dashboard/debug UI, can toggle mock mode, runs the projector).

**Course:** start line → obstacle slightly to one side → center obstacle → clear stretch →
printed **EXIT** sign → fake curb/step marker.

## Timeline

**0:00–0:30 — Hook**
> "Independence shouldn't require an internet connection."

Hold up the phone and **switch it to airplane mode** on stage. Everything from here is on-device.

**0:30–1:00 — Architecture**
> "Camera frames become a compact SceneState on the phone. ExecuTorch runs the models on the
> Snapdragon NPU. The belt is just a dumb actuator. The dashboard only visualizes what the phone
> already computed."

Point at the dashboard so judges see zones/objects/belt updating.

**1:00–3:30 — Live run**
- Walker advances; the belt buzzes **left / center / right** as obstacles appear; **double pulse**
  at the curb marker. Dashboard zones light up in sync.
- Walker asks **"What's ahead of me?"** → spoken on-device answer.
- Walker asks **"Read that sign."** at the EXIT sign → spoken "The sign says: EXIT."

**3:30–4:15 — Why on-device**
> "No upload, no cloud latency, works with no signal, private by design. The Snapdragon NPU is
> what makes continuous local perception practical on battery."

**4:15–5:00 — Close**
Roadmap (more belt zones, richer scene Q&A), sponsor alignment (ExecuTorch/Qualcomm/Copilot),
thanks.

## Operator playbook (failure handling)

- **BLE drops** → tap **Connect Belt** again; the belt re-advertises on disconnect.
- **Live models flaky** → **Mock On** (operator) — the belt/voice/dashboard run on the identical
  SceneState contract, so the story still lands. State this honestly if asked.
- **Dashboard can't reach phone** (Wi-Fi isolation) → it auto-replays `mockFrames.json`; or
  switch to a hotspot/travel router (see [galaxy_s25_mac_setup.md](galaxy_s25_mac_setup.md)).
- **Total failure** → play the pre-recorded backup video.

## Pre-demo checklist

- [ ] Phone charged; app installed (debug build); belt powered and paired once.
- [ ] Airplane mode toggled and re-verified after model load.
- [ ] Course laid out; EXIT sign printed; curb marker placed.
- [ ] Dashboard open on the projector; phone IP entered (or mock replay confirmed).
- [ ] Backup video on the laptop.
- [ ] One dry run completed end-to-end.
