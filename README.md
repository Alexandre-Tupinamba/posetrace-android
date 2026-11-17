# PoseTrace

**Privacy-first, on-device human pose acquisition for Android using CameraX and MediaPipe.**

PoseTrace is an Android project for real-time human pose capture with precise timing and performance telemetry. Camera frames are processed transiently on-device and discarded; the project is designed to persist pose landmarks and acquisition metadata rather than video or photos.

> **Current milestone:** the critical CameraX → MediaPipe → overlay pipeline has been validated on a physical Android device. Session recording, Protobuf persistence, replay, and export are the next milestones.

## Why this project exists

Most pose demos stop at drawing a skeleton over a camera preview. PoseTrace is being built as a reproducible acquisition system: raw landmarks, camera timestamps, inference timing, session metadata, local persistence, and replay without storing camera pixels.

The project intentionally separates **raw pose data** from **presentation-only smoothing**. Any future visual smoothing may improve the overlay, but the acquisition path will preserve the original MediaPipe outputs.

## Validated technical baseline

A representative physical-device run on **2026-11-17** produced:

| Metric | Observed value |
|---|---:|
| Camera analyzer FPS | 30.0 |
| MediaPipe result FPS | 29.5 |
| End-to-end latency | 26.29 ms |
| Analyzer / submitted / results | 3008 / 3008 / 2999 |
| Normalized landmarks | 33 |
| World landmarks | 33 |
| Expired submission estimate | 0 |
| Overlay transform | CameraX validated |

These values describe one observed run and are **not** device-independent performance guarantees. See [`docs/technical-spike-results.md`](docs/technical-spike-results.md).

## Current capabilities

- rear-camera preview with CameraX;
- `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`;
- MediaPipe Pose Landmarker in `LIVE_STREAM` mode;
- single-person pose estimation;
- 33 normalized pose landmarks;
- 33 world-coordinate landmarks;
- CameraX coordinate transformation for correctly aligned overlays;
- nanosecond camera timestamps preserved separately from MediaPipe timestamps;
- monotonic end-to-end inference latency measurement;
- analyzer/submission/result counters kept distinct;
- no image or video persistence.

## Privacy model

The current path is intentionally simple:

```text
Camera frame
    ↓
RAM
    ↓
MediaPipe Pose Landmarker
    ↓
Pose landmarks + telemetry
    ↓
Frame released
```

PoseTrace does **not** write camera frames to JPEG, PNG, MP4, MediaStore, Room, or a backend.

The planned V1 storage layer will persist only:

- raw pose landmarks;
- timestamps;
- acquisition metadata;
- session statistics.

## Architecture direction

```text
CameraX ImageAnalysis
        ↓
PoseLandmarkerEngine
        ↓
RawPoseFrame
        ├──────────────→ display-only smoothing → overlay
        │
        └──────────────→ RecordingController
                              ↓
                        bounded buffer
                              ↓
                        Protobuf writer
                              ↓
                          local session
```

Room will index session-level metadata. Dense frame/landmark data will live in a compact binary recording rather than one database row per landmark.

## Tech stack

- Kotlin
- Jetpack Compose
- CameraX
- MediaPipe Tasks Vision
- Coroutines / Flow
- Android API 28+
- JUnit

Planned V1 additions include Room, Protocol Buffers, Hilt, DataStore, replay, and CSV/JSON export.

## Build

Requirements:

- Android Studio with Android SDK 36;
- JDK 17+;
- physical Android device with API 28+;
- internet access during the first build to download dependencies and the pinned MediaPipe model.

```bash
./gradlew assembleDebug
```

The build downloads Google's pinned **Pose Landmarker Lite (float16)** model into the app assets when it is not already present. The generated `.task` file is intentionally excluded from Git.

## Documentation

- [`docs/specification.md`](docs/specification.md) — V1 technical specification
- [`docs/technical-spike-results.md`](docs/technical-spike-results.md) — physical-device validation record
- [`docs/spike-validation.md`](docs/spike-validation.md) — extended validation checklist
- [`docs/dependency-matrix.md`](docs/dependency-matrix.md) — pinned Android/Gradle dependency matrix

## Roadmap

### v0.2 — Recording foundation
- explicit recording state machine;
- raw `PoseFrame` domain model;
- bounded asynchronous writer;
- Protocol Buffers schema;
- failure-safe session finalization.

### v0.3 — Sessions and replay
- Room session index;
- session history/details;
- timestamp-based skeleton replay;
- session statistics.

### v0.4 — Export and analysis
- native PoseTrace package;
- JSON export;
- tidy CSV export;
- landmark trajectories and richer diagnostics.

### Later
- display-only One Euro smoothing;
- angles, velocity, and acceleration;
- model/delegate benchmarks;
- labeled dataset acquisition mode.

## Project status

PoseTrace is under active development. The current code is deliberately a small, validated baseline before the persistence and application architecture are expanded.
