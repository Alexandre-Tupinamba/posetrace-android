# PoseTrace

**Privacy-first, on-device human pose acquisition for Android with CameraX, MediaPipe and failure-safe local recording.**

PoseTrace is an Android ML systems project that turns a live camera stream into timestamped human-pose data **without persisting camera pixels**. It combines real-time on-device inference, coordinate transforms, acquisition telemetry, raw landmark preservation, bounded asynchronous persistence, and reproducible binary session recording.

[![Android CI](https://github.com/Alexandre-Tupinamba/posetrace-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Alexandre-Tupinamba/posetrace-android/actions/workflows/android-ci.yml)

> **Current milestone:** real-time pose inference and the raw recording pipeline have both been exercised on a physical Android device. The recording foundation remains in review until the final long-session/file-inspection gate is completed.

## What this demonstrates

PoseTrace is deliberately more than a pose-overlay demo. The project focuses on the engineering around an ML model:

- integrating MediaPipe inference into a real Android pipeline;
- keeping CameraX and model timestamps correctly correlated;
- handling asynchronous inference without accumulating unbounded work;
- preserving raw ML outputs separately from presentation-only transforms;
- applying explicit backpressure instead of silently dropping persisted data;
- serializing dense time-series pose data with Protocol Buffers;
- finalizing sessions safely across stop, abort, writer failure, and lifecycle races;
- validating behavior with physical-device measurements, unit tests, Android Lint, and CI;
- enforcing a privacy boundary where camera frames remain transient in memory.

## Architecture

~~~text
CameraX ImageAnalysis
        │
        │ transient camera frame
        ▼
MediaPipe Pose Landmarker
        │
        ├──────────────► CameraX coordinate transform ─► overlay
        │
        └──────────────► RAW PoseFrame
                              │
                              ▼
                       RecordingController
                              │
                       bounded in-memory queue
                              │
                              ▼
                        single writer
                              │
                              ▼
                    length-delimited Protobuf
                              │
                              ▼
                       local session
~~~

The recording branch receives raw MediaPipe landmarks. Preview-only coordinates such as **displayX/displayY** never enter the persisted schema.

## Physical-device results

### Real-time inference baseline

A representative device run on **2026-11-17** produced:

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

These values describe one observed run and are **not** device-independent performance guarantees.

### Recording smoke test

The recording pipeline has also completed a physical-device session with:

| Metric | Observed value |
|---|---:|
| Final state | COMPLETED |
| Frames queued | 118 |
| Frames written | 118 |
| Queue/write convergence | 100% |
| Persisted camera pixels | none by design |

The final long-session and app-storage inspection gate is still required before the recording PR is merged.

## Recording model

A completed session is designed as:

~~~text
files/
└── recordings/
    └── <session-id>/
        ├── pose.pb
        └── session.json
~~~

During acquisition, frames are written to **pose.pb.partial**. The file becomes **pose.pb** only after the queue drains successfully, buffered bytes are flushed, and the file descriptor is synchronized.

Failed or aborted sessions are never silently labeled COMPLETED.

Each Protobuf frame preserves:

- analyzer frame index;
- canonical CameraX timestamp in nanoseconds;
- correlated MediaPipe timestamp in milliseconds;
- measured end-to-end inference latency;
- source dimensions and rotation;
- explicit pose-present state;
- normalized pose landmarks;
- world-coordinate pose landmarks.

A no-pose result remains representable, preserving temporal gaps rather than silently removing them.

See [docs/recording-format.md](docs/recording-format.md).

## Privacy boundary

PoseTrace intentionally separates **camera input** from **persistent ML data**:

~~~text
camera pixels
    │
    ▼
RAM / inference
    │
    ├──► released
    │
    └──► landmarks + timing metadata ─► persistence
~~~

The recorder accepts PoseFrame values only. It has no API for Bitmap, ImageProxy, JPEG, PNG, MP4, MediaStore, or video streams.

Automated tests additionally verify that a completed recording contains only the expected session files.

## Data integrity and failure semantics

PoseTrace favors explicit failure over silent corruption.

The recording session fails when it detects conditions such as:

- bounded queue overflow;
- non-monotonic camera timestamps;
- loss of source-frame metadata needed to correlate a result;
- serialization/disk writer failure.

The controller also protects terminal-state races:

- frames arriving after STOPPING are rejected;
- successful finalization cannot be overwritten by a concurrent abort;
- COMPLETED, ABORTED, and FAILED remain distinguishable.

## Tech stack

- Kotlin
- Jetpack Compose
- CameraX
- MediaPipe Tasks Vision — Pose Landmarker
- Protocol Buffers Lite
- Coroutines / Flow
- Android API 28+
- JUnit
- GitHub Actions

## Automated validation

Every PR runs:

~~~bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
~~~

The recording test suite covers, among other cases:

- Protobuf round-trip;
- preservation of 33 normalized + 33 world landmarks;
- frames with no detected pose;
- valid and invalid recording-state transitions;
- strict camera-timestamp ordering;
- writer failures;
- post-Stop frame rejection;
- stop/finalization versus abort races;
- completed/aborted session-file semantics;
- absence of persisted image/video files in completed sessions.

## Build

Requirements:

- Android Studio with Android SDK 36;
- JDK 17+;
- physical Android device with API 28+;
- internet access during the first build.

~~~bash
git clone https://github.com/Alexandre-Tupinamba/posetrace-android.git
cd posetrace-android
./gradlew assembleDebug
~~~

The build downloads a pinned Google **Pose Landmarker Lite (float16)** model into the app assets when needed. The generated model file is intentionally excluded from Git.

## Repository workflow

The project uses a lightweight GitHub Flow:

~~~text
main
  └── feature/fix branch
          └── Pull Request
                ├── unit tests
                ├── Android Lint
                ├── debug build
                └── physical-device gate when camera/I/O behavior changes
~~~

main is kept in a validated state. Camera and recording changes are not merged solely because CI is green when the behavior still requires hardware validation.

## Documentation

- [docs/specification.md](docs/specification.md) — V1 technical specification
- [docs/technical-spike-results.md](docs/technical-spike-results.md) — physical-device inference baseline
- [docs/recording-format.md](docs/recording-format.md) — recording format and integrity model
- [docs/spike-validation.md](docs/spike-validation.md) — extended device-validation checklist
- [docs/dependency-matrix.md](docs/dependency-matrix.md) — pinned Android/Gradle dependency matrix

## Roadmap

### Recording foundation — in validation
- [x] explicit recording state machine
- [x] raw PoseFrame domain model
- [x] bounded asynchronous writer
- [x] Protocol Buffers schema
- [x] failure-safe session finalization
- [x] physical-device recording smoke test
- [ ] long-session + private-storage inspection gate

### Sessions and replay
- Room session index
- session history/details
- timestamp-based skeleton replay
- session statistics

### Export and analysis
- native PoseTrace package
- JSON export
- tidy CSV export
- landmark trajectories and richer diagnostics

### Later
- display-only One Euro smoothing
- angles, velocity, and acceleration
- model/delegate benchmarks
- labeled dataset acquisition mode

## Project status

PoseTrace is under active development. The current focus is reliability of the acquisition path before adding higher-level analysis features.

The project intentionally keeps ML inference, visualization, and persisted data as separate concerns so future filtering or UI improvements cannot silently mutate the raw acquisition record.
