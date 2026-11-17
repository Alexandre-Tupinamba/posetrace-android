# PoseTrace recording format

PoseTrace recordings are designed to preserve **raw pose-estimation outputs and acquisition timing metadata without persisting camera pixels**.

## Session layout

```text
recordings/
└── <session-id>/
    ├── pose.pb
    └── session.json
```

While a session is active, pose data is written to `pose.pb.partial`. The file is promoted to `pose.pb` only after the writer has drained the queue, flushed buffered bytes, and synchronized the file descriptor.

Failed or aborted sessions keep the partial data file and are never labeled `COMPLETED`.

## Protobuf stream

`pose.pb` is a sequence of **length-delimited** `PoseFrameRecord` messages. Each frame contains:

- analyzer frame index;
- CameraX timestamp in nanoseconds;
- MediaPipe timestamp in milliseconds;
- measured inference latency in nanoseconds;
- source width/height and rotation;
- explicit pose-present flag;
- normalized MediaPipe landmarks;
- world-coordinate MediaPipe landmarks.

A frame with no detected pose is still written with empty landmark lists. This preserves temporal gaps instead of silently removing them.

## Landmark integrity

The recording path receives raw MediaPipe coordinates directly from the inference callback. Preview-only fields such as transformed overlay coordinates are not part of the recording schema.

Future smoothing/filtering must remain downstream of the raw recording branch.

## Backpressure

The inference callback publishes into a bounded in-memory queue. A single writer thread serializes frames in order.

PoseTrace intentionally treats these conditions as recording failures rather than silently dropping data:

- queue overflow;
- non-monotonic camera timestamps;
- disk/serialization writer exceptions;
- loss of the CameraX metadata required to associate a MediaPipe result with its source frame.

## Privacy boundary

The recorder API accepts only `PoseFrame` values. Camera bitmaps, `ImageProxy` objects, JPEG/PNG images, and video streams never cross into the recording layer.
