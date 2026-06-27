# Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Vibration motors arrive after the event** (ordered modules ETA July 2–3) | High | High | Buy local/same-day ERM coin motors now; phone vibration as last resort |
| **BLE drops** mid-demo | Med/High | Med | Mock mode, **Connect Belt** re-scan, belt re-advertises on disconnect, test in nRF Connect first |
| **NPU/QNN export issues** on Mac | Med | High | Use Qualcomm AI Hub for `.pte` export; ask mentors; XNNPACK/CPU fallback per model |
| **CV too slow** (latency hurts guidance) | Med | High | Depth-only first; lower resolution; smaller models; cap analysis FPS |
| **OCR unstable** | Med | Low/Med | ML Kit on-device fallback; rely on a known printed EXIT sign for the demo |
| **Wi-Fi isolation** blocks phone↔laptop | Med | Low/Med | USB-first workflow; hotspot/travel router; dashboard mock replay |
| **Sunday integration hell** | High | High | Contract-first (`SceneState`), feature freeze ~11:00, keep mock mode working |
| **Device collected overnight** | Unknown | Med | All overnight work must be laptop/mock/firmware/dashboard based |
| **Hardware arrival delay** (any belt part) | Med | High | Pre-test on a personal Android phone; keep a spare ESP32; backup video |
| **Chest harness angle poor** | Med | Med | Test camera angle early; tape/adjust the clamp |
| **Llama too slow** for voice | Med | Med | Deterministic rule-based answers from SceneState (already implemented) |

## Standing mitigations baked into the repo

- **Mock mode** emits the exact `SceneState` contract, so belt + voice + dashboard demo even if
  live perception breaks.
- **Rule-based VoiceAgent** works with no model loaded.
- **Dashboard** falls back to `mockFrames.json` with no phone.
- **USB-first** dev/control path needs no conference network.
- **Airplane-mode** demo proves the on-device thesis and removes network as a failure mode.
