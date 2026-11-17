package com.alexandretupinamba.posetrace.recording

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface PoseSessionWriter {
    fun append(frame: PoseFrame)
    fun complete(endedAtEpochMs: Long)
    fun abort(finalState: RecordingState, endedAtEpochMs: Long, reason: String?)
}

internal class FilePoseSessionWriter(
    private val sessionDirectory: File,
    private val sessionId: String,
    private val startedAtEpochMs: Long,
) : PoseSessionWriter {
    private val partialDataFile = File(sessionDirectory, PARTIAL_DATA_FILE)
    private val completedDataFile = File(sessionDirectory, COMPLETED_DATA_FILE)
    private val manifestFile = File(sessionDirectory, MANIFEST_FILE)
    private val fileOutput: FileOutputStream
    private val output: BufferedOutputStream

    private var framesWritten = 0L
    private var firstCameraTimestampNs: Long? = null
    private var lastCameraTimestampNs: Long? = null
    private var finalized = false

    init {
        check(sessionDirectory.mkdirs() || sessionDirectory.isDirectory) {
            "Unable to create recording directory: ${sessionDirectory.absolutePath}"
        }
        fileOutput = FileOutputStream(partialDataFile, false)
        output = BufferedOutputStream(fileOutput)
    }

    override fun append(frame: PoseFrame) {
        check(!finalized) { "Session writer is already finalized" }
        val previousTimestamp = lastCameraTimestampNs
        check(previousTimestamp == null || frame.cameraTimestampNs > previousTimestamp) {
            "Camera timestamps must be strictly increasing: " +
                "$previousTimestamp -> ${frame.cameraTimestampNs}"
        }

        PoseProtoCodec.writeDelimited(frame, output)
        if (firstCameraTimestampNs == null) firstCameraTimestampNs = frame.cameraTimestampNs
        lastCameraTimestampNs = frame.cameraTimestampNs
        framesWritten++
    }

    override fun complete(endedAtEpochMs: Long) {
        check(!finalized) { "Session writer is already finalized" }
        syncAndClose()
        moveAtomicallyWhenPossible(partialDataFile, completedDataFile)
        writeManifest(RecordingState.COMPLETED, endedAtEpochMs, null, COMPLETED_DATA_FILE)
        finalized = true
    }

    override fun abort(finalState: RecordingState, endedAtEpochMs: Long, reason: String?) {
        require(finalState == RecordingState.ABORTED || finalState == RecordingState.FAILED)
        if (finalized) return
        runCatching { syncAndClose() }
        writeManifest(
            finalState,
            endedAtEpochMs,
            reason,
            if (partialDataFile.exists()) PARTIAL_DATA_FILE else null,
        )
        finalized = true
    }

    private fun syncAndClose() {
        output.flush()
        fileOutput.fd.sync()
        output.close()
    }

    private fun writeManifest(
        finalState: RecordingState,
        endedAtEpochMs: Long,
        reason: String?,
        dataFileName: String?,
    ) {
        val json = buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"sessionId\": \"${escapeJson(sessionId)}\",\n")
            append("  \"state\": \"${finalState.name}\",\n")
            append("  \"startedAtEpochMs\": $startedAtEpochMs,\n")
            append("  \"endedAtEpochMs\": $endedAtEpochMs,\n")
            append("  \"frameCount\": $framesWritten,\n")
            append("  \"firstCameraTimestampNs\": ${firstCameraTimestampNs ?: "null"},\n")
            append("  \"lastCameraTimestampNs\": ${lastCameraTimestampNs ?: "null"},\n")
            append("  \"dataFile\": ")
            if (dataFileName == null) append("null,\n") else append("\"${escapeJson(dataFileName)}\",\n")
            append("  \"reason\": ")
            if (reason == null) append("null\n") else append("\"${escapeJson(reason)}\"\n")
            append("}\n")
        }
        manifestFile.writeText(json, Charsets.UTF_8)
    }

    private fun moveAtomicallyWhenPossible(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    companion object {
        const val COMPLETED_DATA_FILE = "pose.pb"
        const val PARTIAL_DATA_FILE = "pose.pb.partial"
        const val MANIFEST_FILE = "session.json"
    }
}
