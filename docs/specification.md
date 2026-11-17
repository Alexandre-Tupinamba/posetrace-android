# PoseTrace — V1 Technical Specification

Status: Draft locked for implementation  
Schema version: 1  
Platform: Android  
Primary language: Kotlin

## 1. Product definition

PoseTrace is a privacy-first Android application for real-time human pose acquisition.

The rear camera is used only as transient sensor input. Frames are processed on-device with MediaPipe Pose Landmarker and are never persisted by V1. Only pose landmarks, timing information, acquisition metadata, and derived session statistics are stored locally.

### V1 goal
Capture a single person's pose over time with enough temporal and spatial metadata to:
- inspect movement in real time;
- persist the pose sequence locally;
- replay the skeleton without the original video;
- inspect capture/inference performance;
- export pose data for analysis.

### V1 non-goals
- video recording;
- image/photo persistence;
- cloud synchronization;
- accounts/authentication;
- backend/server;
- multi-person capture;
- exercise classification;
- medical diagnosis;
- automatic biomechanical conclusions;
- face/identity recognition;
- LLM integration.

## 2. Privacy model

Core rule: no camera image is persisted.

Pipeline:

Camera frame -> RAM -> MediaPipe -> pose result -> frame released

Persisted:
- pose landmarks;
- timestamps;
- camera/acquisition metadata;
- inference timing;
- session statistics.

Not persisted:
- MP4;
- JPEG;
- PNG;
- raw YUV/RGBA frame files;
- camera-derived thumbnails.

## 3. Technology baseline

- Kotlin
- Jetpack Compose
- CameraX Preview
- CameraX ImageAnalysis
- MediaPipe Tasks Vision — Pose Landmarker
- Coroutines / Flow
- Room for session index and aggregate metadata
- Protocol Buffers for dense pose data
- DataStore for app settings
- Hilt for dependency injection
- JUnit
- Compose UI tests where useful

Initial decisions:
- `minSdk = 28`
- rear-facing camera only
- one pose (`numPoses = 1`)
- segmentation masks disabled
- MediaPipe `RunningMode.LIVE_STREAM`
- CameraX `STRATEGY_KEEP_ONLY_LATEST`

## 4. Pose model

For every detected pose, preserve the 33 landmarks returned by MediaPipe.

### Normalized values
- `x`
- `y`
- `z`
- `visibility`
- `presence`

### World values
- `worldX`
- `worldY`
- `worldZ`
- `worldVisibility`
- `worldPresence`

Landmarks are serialized in canonical MediaPipe index order `0..32`. Names are mapped in the application layer instead of repeated in every binary frame.

## 5. Timing model

Timing is first-class data.

### Camera timestamp
For every frame delivered to the analyzer:

`cameraTimestampNs: Long`

Use the CameraX `ImageInfo` timestamp as the canonical monotonic frame timestamp.

Do not use wall-clock time to calculate frame intervals.

### Session origin
When recording starts:

`recordingStartCameraTimestampNs`

For each result:

`elapsedNs = cameraTimestampNs - recordingStartCameraTimestampNs`

### Wall clock
Use wall-clock time only for:
- `startedAtEpochMs`
- `endedAtEpochMs`

### MediaPipe timestamp
`detectAsync` receives a monotonically increasing millisecond timestamp.

Derive it from the camera timestamp while preserving `cameraTimestampNs` separately as the canonical value.

### Inference latency
Record:
- `inferenceSubmitNs`
- `resultReceivedNs`

Then:

`endToEndInferenceNs = resultReceivedNs - inferenceSubmitNs`

Call this **end-to-end inference latency**, not pure neural-network execution time.

## 6. Pipeline counters and frame identity

Track separately:
- `analyzerFrameCount`
- `mediaPipeSubmittedCount`
- `mediaPipeResultCount`
- `poseDetectedCount`
- `poseMissingCount`
- `persistedPoseFrameCount`

Every analyzer frame receives a monotonically increasing:

`analyzerFrameIndex`

### Dropped/skipped frames

V1 must not claim an exact global `droppedFrames` count.

Frames can disappear before or inside our observable pipeline:
1. CameraX `KEEP_ONLY_LATEST` may discard images before our analyzer sees them.
2. MediaPipe `LIVE_STREAM` may omit results when it cannot keep up.

Expose measured counters and, separately, a timestamp-gap-based `estimatedSkippedIntervals` diagnostic. Estimates must be labeled as estimates.

## 7. Backpressure policy

CameraX:
`ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`

MediaPipe:
`RunningMode.LIVE_STREAM`

Rules:
- every `ImageProxy` must be closed, including error paths;
- no persistent I/O on the camera analyzer thread;
- prioritize current low-latency pose results over processing stale queued frames.

## 8. Data model

### Session

```text
Session
- id: UUID
- schemaVersion: Int
- state: SessionState
- startedAtEpochMs: Long
- endedAtEpochMs: Long?
- recordingStartCameraTimestampNs: Long?
- durationNs: Long?
- modelVariant: PoseModelVariant
- modelAssetVersion: String
- delegate: InferenceDelegate
- cameraLensFacing: LensFacing
- sourceWidth: Int
- sourceHeight: Int
- appVersionName: String
- appVersionCode: Long
- androidApiLevel: Int
- deviceManufacturer: String
- deviceModel: String
- dataFileRelativePath: String
- dataFileSizeBytes: Long?
- analyzerFrameCount: Long
- mediaPipeSubmittedCount: Long
- mediaPipeResultCount: Long
- poseDetectedCount: Long
- poseMissingCount: Long
- persistedPoseFrameCount: Long
- recordingError: String?
```

### PoseFrame

```text
PoseFrame
- analyzerFrameIndex: Long
- cameraTimestampNs: Long
- elapsedNs: Long
- deltaFromPreviousPersistedResultNs: Long?
- mediaPipeTimestampMs: Long
- inferenceSubmitNs: Long
- resultReceivedNs: Long
- endToEndInferenceNs: Long
- sourceWidth: Int
- sourceHeight: Int
- rotationDegrees: Int
- poseDetected: Boolean
- landmarks: repeated PoseLandmark
```

Important V1 decision: persist a `PoseFrame` result even when no pose is detected. In that case:
- `poseDetected = false`
- `landmarks = []`

This preserves temporal gaps.

### PoseLandmark

```text
PoseLandmark
- index: Int
- x: Float
- y: Float
- z: Float
- visibility: Float
- presence: Float
- worldX: Float
- worldY: Float
- worldZ: Float
- worldVisibility: Float
- worldPresence: Float
```

## 9. Session state machine

```text
IDLE
  -> PREPARING
  -> RECORDING
  -> STOPPING
  -> COMPLETED

PREPARING -> FAILED
RECORDING -> FAILED
STOPPING  -> FAILED
```

Cancellation may end in `ABORTED`.

Final persistent states:
- `COMPLETED`
- `ABORTED`
- `FAILED`

Transitions must be explicit and centralized.

## 10. Storage architecture

Room stores session/search metadata only.

Dense pose data goes to:

```text
files/
  sessions/
    <session-uuid>/
      pose.pb
      metadata.json
```

`pose.pb` is canonical dense data.  
`metadata.json` is the human-readable manifest.

Writer pipeline:

```text
MediaPipe result
      |
      v
PoseFrame
      |
      v
bounded Channel / buffer
      |
      v
single background writer
      |
      v
pose.pb
```

Requirements:
- ordered writes;
- bounded memory;
- no disk writes on analyzer/UI thread;
- graceful flush on stop;
- no silent persistence loss.

If the writer cannot keep up, surface an explicit failure/data-loss state.

## 11. Capture screen

Display:
- rear camera preview;
- skeleton overlay;
- recording state;
- elapsed time;
- effective result FPS;
- latest end-to-end inference latency;
- persisted result count;
- pose detected/not detected state.

Actions:
- start
- stop

No pause/resume in V1.

## 12. Sessions

Each session item shows:
- date/time;
- duration;
- persisted frame count;
- average effective FPS;
- completion state.

Actions:
- details
- delete
- export

## 13. Session statistics

Recording:
- duration
- persisted result count
- pose-detected ratio
- effective result FPS

Latency:
- mean
- median
- P90
- P95
- P99
- maximum

Pipeline:
- analyzer frames
- MediaPipe submissions
- MediaPipe callbacks
- callback/submission ratio
- estimated skipped intervals where meaningful

Do not display an unqualified `Dropped Frames` metric.

## 14. Skeleton replay

Replay is generated exclusively from persisted pose data.

Requirements:
- play/pause
- timeline/scrubber
- timestamp-based playback
- skeleton rendering
- honest representation of missing-pose intervals

V1 uses normalized X/Y for the 2D replay.

## 15. Export

V1:
1. native PoseTrace ZIP
2. JSON
3. CSV

Native ZIP:
```text
metadata.json
pose.pb
```

Primary CSV format: long/tidy.

Suggested columns:
```text
session_id
analyzer_frame_index
camera_timestamp_ns
elapsed_ns
pose_detected
landmark_index
landmark_name
x
y
z
visibility
presence
world_x
world_y
world_z
world_visibility
world_presence
end_to_end_inference_ns
```

No-pose frames must remain representable.

## 16. V1 configuration

- model variant
- CPU/GPU delegate where stable
- detection confidence
- presence confidence
- tracking confidence
- overlay on/off

Segmentation remains disabled.

## 17. Performance principles

- no camera pixel persistence
- no analyzer-thread disk I/O
- avoid unnecessary frame copies
- current results over stale queued frames
- benchmark before optimizing
- physical-device testing is mandatory
- no unsupported performance claims

First implementation target:
- stable camera preview
- responsive sustained recording
- bounded memory
- no UI-thread disk I/O
- at least 10-minute sessions without corruption

No fixed latency target until the technical spike establishes a real baseline.

## 18. Error handling

Handle at least:
- camera permission denied
- camera unavailable
- MediaPipe initialization failure
- model asset failure
- inference error
- insufficient storage
- serialization/writer failure
- app backgrounding while recording
- process death / incomplete-session recovery

Incomplete sessions must never be silently labeled `COMPLETED`.

## 19. Tests

Unit tests:
- timestamp conversion
- elapsed-time calculations
- FPS
- percentiles
- state transitions
- frame ordering
- protobuf serialization/deserialization
- export conversion
- missing-pose handling
- metadata generation

Instrumented/manual:
- Room CRUD
- file lifecycle
- camera capture on physical device
- person enters/leaves frame
- rotation
- background/foreground
- low storage
- stop under load
- replay fidelity

## 20. Technical spike acceptance criteria

Before full architecture, prove:

1. rear CameraX preview works on a physical device;
2. ImageAnalysis continuously delivers frames;
3. Pose Landmarker runs in `LIVE_STREAM`;
4. 33-landmark pose overlay works;
5. CameraX nanosecond timestamps are captured;
6. result callbacks correlate with submitted frames;
7. end-to-end latency is measurable;
8. every `ImageProxy` is safely released;
9. no image/video file is created;
10. a 2–5 minute run has no unbounded memory growth or pipeline lockup.

Spike telemetry:

```text
analyzerFrameIndex
cameraTimestampNs
mediaPipeTimestampMs
resultFrameIndex
poseDetected
endToEndInferenceMs
analyzerFps
resultFps
submittedCount
resultCount
```

## 21. Definition of Done — V1

V1 is complete when:
- single-person rear-camera pose capture works;
- no camera pixels are persisted;
- pose results have canonical timing;
- all 33 normalized/world landmarks are preserved when available;
- no-pose intervals are represented;
- session history and statistics work;
- skeleton replay works without video;
- JSON/CSV/native export works;
- long recording does not corrupt sessions;
- failures do not masquerade as successful sessions;
- core timing/statistics/serialization logic has automated tests;
- CI builds the project;
- README describes only behavior we actually measured.

## 22. Deferred roadmap

V1.1:
- richer charts
- landmark trajectory visualization
- additional export layouts

V1.2:
- joint angles
- velocity
- acceleration
- optional smoothing

V2:
- labeled dataset/trial mode
- participant/trial metadata
- model/delegate benchmarks

Future:
- hands
- holistic capture
- gesture/action classification
