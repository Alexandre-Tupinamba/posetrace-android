package com.alexandretupinamba.posetrace.recording

import com.alexandretupinamba.posetrace.recording.proto.PoseFrameRecord
import com.alexandretupinamba.posetrace.recording.proto.PoseLandmarkRecord
import java.io.InputStream
import java.io.OutputStream

object PoseProtoCodec {
    fun encode(frame: PoseFrame): ByteArray = toProto(frame).toByteArray()

    fun decode(bytes: ByteArray): PoseFrame = fromProto(PoseFrameRecord.parseFrom(bytes))

    fun writeDelimited(frame: PoseFrame, output: OutputStream) {
        toProto(frame).writeDelimitedTo(output)
    }

    fun readDelimited(input: InputStream): PoseFrame? {
        val record = PoseFrameRecord.parseDelimitedFrom(input) ?: return null
        return fromProto(record)
    }

    private fun toProto(frame: PoseFrame): PoseFrameRecord =
        PoseFrameRecord.newBuilder()
            .setAnalyzerFrameIndex(frame.analyzerFrameIndex)
            .setCameraTimestampNs(frame.cameraTimestampNs)
            .setMediapipeTimestampMs(frame.mediaPipeTimestampMs)
            .setInferenceLatencyNs(frame.inferenceLatencyNs)
            .setSourceWidth(frame.sourceWidth)
            .setSourceHeight(frame.sourceHeight)
            .setRotationDegrees(frame.rotationDegrees)
            .setPoseDetected(frame.poseDetected)
            .addAllNormalizedLandmarks(frame.normalizedLandmarks.map(::toProto))
            .addAllWorldLandmarks(frame.worldLandmarks.map(::toProto))
            .build()

    private fun fromProto(record: PoseFrameRecord): PoseFrame =
        PoseFrame(
            analyzerFrameIndex = record.analyzerFrameIndex,
            cameraTimestampNs = record.cameraTimestampNs,
            mediaPipeTimestampMs = record.mediapipeTimestampMs,
            inferenceLatencyNs = record.inferenceLatencyNs,
            sourceWidth = record.sourceWidth,
            sourceHeight = record.sourceHeight,
            rotationDegrees = record.rotationDegrees,
            poseDetected = record.poseDetected,
            normalizedLandmarks = record.normalizedLandmarksList.map(::fromProto),
            worldLandmarks = record.worldLandmarksList.map(::fromProto),
        )

    private fun toProto(landmark: PoseLandmarkSample): PoseLandmarkRecord {
        val builder = PoseLandmarkRecord.newBuilder()
            .setIndex(landmark.index)
            .setX(landmark.x)
            .setY(landmark.y)
            .setZ(landmark.z)
        landmark.visibility?.let(builder::setVisibility)
        landmark.presence?.let(builder::setPresence)
        return builder.build()
    }

    private fun fromProto(record: PoseLandmarkRecord): PoseLandmarkSample =
        PoseLandmarkSample(
            index = record.index,
            x = record.x,
            y = record.y,
            z = record.z,
            visibility = if (record.hasVisibility()) record.visibility else null,
            presence = if (record.hasPresence()) record.presence else null,
        )
}
