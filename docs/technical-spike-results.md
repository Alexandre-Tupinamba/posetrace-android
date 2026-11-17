# Technical Spike — Results

Date: **2026-11-17**  
Status: **critical path validated on physical hardware**

## Purpose

The spike validates the highest-risk path before implementing storage and session architecture:

```text
rear CameraX preview
       ↓
ImageAnalysis
       ↓
MediaPipe Pose Landmarker (LIVE_STREAM)
       ↓
33 normalized + 33 world landmarks
       ↓
CameraX-aligned skeleton overlay
```

No camera image or video is intentionally persisted.

## Representative telemetry

A physical-device portrait run produced:

```text
MediaPipe: ready
Pose: detected
Analyzer FPS: 30.0
Result FPS: 29.5
End-to-end latency: 26.29 ms
Analyzer / submitted / results: 3008 / 3008 / 2999
Landmarks normalized / world: 33 / 33
Overlay transform: CameraX ✓
MediaPipe input: 296x640, rotation: 90°
Expired submissions (estimate): 0
```

These measurements are a **single observed snapshot**, not a benchmark guarantee across devices.

## Findings

### Coordinate mapping

An early implementation mapped normalized landmarks to the preview using a hand-written fit operation. This was incorrect because CameraX preview and analysis use cases can have distinct crop/rotation transforms.

The validated implementation now:

1. binds Preview and ImageAnalysis in the same CameraX `UseCaseGroup`/`ViewPort`;
2. obtains the PreviewView output transform on the Android main thread;
3. passes an immutable transform snapshot to the analysis engine;
4. uses CameraX `CoordinateTransform` for rendering coordinates.

This fixed the visible landmark displacement in portrait orientation.

### Threading

`PreviewView.outputTransform` is a main-thread-only API. The transform is therefore captured on the UI thread and published to the analyzer as immutable state. The camera executor no longer touches `PreviewView` directly.

### Landmark jitter

Small intermittent jitter was observed mainly around hands and face, particularly during occlusion and close-to-camera poses. This is treated as model/output behavior rather than a coordinate-transform bug.

The acquisition design will preserve **raw MediaPipe landmarks**. If smoothing is introduced, it will initially affect the display layer only so that recorded data remains faithful to the source model.

## Counters

PoseTrace deliberately distinguishes:

- frames delivered to the CameraX analyzer;
- frames submitted to MediaPipe;
- MediaPipe result callbacks.

It does not report a single misleading global `droppedFrames` metric because both CameraX backpressure and MediaPipe LIVE_STREAM can discard work at different stages.

## Remaining extended validation

The critical path is validated. Before declaring the entire V1 acquisition path production-ready, the checklist in [`spike-validation.md`](spike-validation.md) still covers:

- sustained multi-minute runs;
- landscape/rotation behavior;
- background/foreground lifecycle;
- memory observation;
- pose-loss/reacquisition behavior.

These checks are intentionally kept separate from the confirmed critical-path result above.
