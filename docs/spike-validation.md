# Technical Spike — Physical Device Validation

Use this checklist before moving PoseTrace from the spike to the V1 foundation.

## Test device

Record:

- Manufacturer / model:
- Android version / API:
- Device orientation tested:
- Build type: debug
- Pose model: Lite (float16)
- Delegate: CPU

## 1. Startup

- [ ] App installs and launches.
- [ ] Camera permission explanation is shown when permission is missing.
- [ ] Rear camera opens after permission is granted.
- [ ] `MediaPipe: ready` is displayed.
- [ ] No `.jpg`, `.png`, `.mp4`, or other camera capture file is created by the app.

## 2. Pose output

Stand fully in frame for at least 30 seconds.

- [ ] Skeleton follows the body.
- [ ] Overlay matches the live preview in portrait.
- [ ] Overlay matches the live preview in landscape.
- [ ] `Landmarks normalized / world` normally reports `33 / 33` while a pose is detected.
- [ ] Leaving the frame changes `Pose: detected` to no pose without crashing.
- [ ] Returning to frame resumes detection.

## 3. Timing and counters

Observe telemetry for at least 60 seconds.

- [ ] Camera timestamp increases.
- [ ] Analyzer frame count only increases.
- [ ] Submitted count only increases.
- [ ] Result count only increases.
- [ ] `result count <= submitted count` after startup transients.
- [ ] Analyzer FPS remains plausible and non-negative.
- [ ] Result FPS remains plausible and non-negative.
- [ ] End-to-end latency remains non-negative.

Record a representative snapshot:

```text
Analyzer FPS:
Result FPS:
End-to-end latency:
Analyzer / submitted / results:
Landmarks normalized / world:
Expired submissions (estimate):
Camera timestamp:
```

## 4. Sustained run

Run continuously for 5 minutes with normal movement.

- [ ] Preview remains responsive.
- [ ] Skeleton remains responsive.
- [ ] No crash or ANR.
- [ ] No obvious progressive multi-second lag.
- [ ] Result count continues increasing until the end.
- [ ] Device does not show runaway memory behavior.

If Android Studio Profiler is available, record approximate memory at:

```text
00:30:
02:30:
05:00:
```

A stable plateau or normal GC sawtooth is expected; monotonic unbounded growth is not.

## 5. Rotation

- [ ] Start in portrait and verify alignment.
- [ ] Rotate to landscape and verify alignment after Activity recreation.
- [ ] Rotate back to portrait and verify alignment.
- [ ] No crash during rotation.

## 6. Background / foreground

- [ ] Send app to background.
- [ ] Return to app.
- [ ] Camera resumes according to lifecycle.
- [ ] MediaPipe resumes without duplicate analyzers or visibly doubled counters/FPS.

## 7. Evidence to keep

For the spike record, save:

1. one screenshot with pose overlay and telemetry;
2. the representative telemetry snapshot above;
3. device model and Android version;
4. any Logcat error beginning with `PoseTraceEngine` or `PoseTraceCamera`.

Do **not** add captured camera images to the repository unless deliberately created outside PoseTrace for documentation with informed consent.

## Acceptance

The spike passes when:

- CameraX + MediaPipe stays responsive on a physical device;
- normalized and world landmark outputs are available;
- camera timestamps/counters behave coherently;
- overlay alignment survives orientation changes;
- a 5-minute run has no crash, lockup, or obvious unbounded memory growth;
- PoseTrace itself persists no camera pixels.

Only after this passes should Room, Protocol Buffers, recording sessions, replay, and export be implemented.
