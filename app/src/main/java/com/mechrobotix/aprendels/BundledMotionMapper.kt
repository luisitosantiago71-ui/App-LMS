package com.mechrobotix.aprendels

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.File

/**
 * Builds and persists a first-use motion reference from the bundled demonstration.
 * Previously saved references are loaded before this class is called.
 */
object BundledMotionMapper {
    fun prepare(context: Context, letter: String): MotionReferenceStore.Reference {
        require(MotionReferenceStore.isDynamic(letter)) { "Esta letra no usa una referencia en movimiento" }
        val video = File.createTempFile("lsm_${letter.lowercase()}_", ".mp4", context.cacheDir)
        var detector: HandLandmarker? = null
        val retriever = MediaMetadataRetriever()
        try {
            context.resources.openRawResource(MotionReferenceStore.bundled(letter)).use { input ->
                video.outputStream().use { output -> input.copyTo(output) }
            }
            retriever.setDataSource(video.absolutePath)
            val duration = requireNotNull(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ).toLong()
            require(duration in 1200L..30000L) { "El video debe durar entre 1.2 y 30 segundos" }

            detector = HandLandmarker.createFromOptions(
                context,
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(2)
                    .build()
            )

            val mapped = mutableListOf<JReferenceStore.Frame>()
            var time = 0L
            while (time < duration) {
                val original = requireNotNull(
                    retriever.getFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ) { "No se pudo leer el video de $letter" }
                val scale = minOf(1.0, 1080.0 / maxOf(original.width, original.height))
                val resized = if (scale < 1.0) {
                    Bitmap.createScaledBitmap(
                        original,
                        (original.width * scale).toInt().coerceAtLeast(1),
                        (original.height * scale).toInt().coerceAtLeast(1),
                        true
                    ).also { if (it !== original) original.recycle() }
                } else original
                val bitmap = if (resized.config == Bitmap.Config.ARGB_8888) resized
                    else requireNotNull(resized.copy(Bitmap.Config.ARGB_8888, false)).also { resized.recycle() }
                val image = BitmapImageBuilder(bitmap).build()
                val sample = try {
                    HandLandmarkSamples.sample(
                        detector.detectForVideo(image, time),
                        bitmap.width,
                        bitmap.height,
                        false
                    )?.takeIf { it.valid() }
                } finally {
                    image.close()
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                mapped.add(JReferenceStore.Frame(time, sample))
                time += JReferenceStore.STEP_MS
            }

            val usable = longestContinuousSegment(mapped, letter)
            val source = MotionReferenceStore.fingerprint(context, video)
            MotionReferenceStore.validate(usable, letter)
            MotionReferenceStore.save(
                context,
                letter,
                video,
                source,
                usable,
                JPracticeEngine.Tolerance.FLEXIBLE.ordinal,
                publish = true
            )
            return requireNotNull(MotionReferenceStore.load(context, letter)) {
                "No se pudo guardar la referencia de $letter"
            }
        } finally {
            detector?.close()
            retriever.release()
            video.delete()
        }
    }

    /**
     * Trims unreadable beginning/end frames and interpolates up to 200 ms of
     * isolated tracking loss. Larger gaps split the clip into separate runs.
     */
    private fun longestContinuousSegment(frames: List<JReferenceStore.Frame>, letter: String): List<JReferenceStore.Frame> {
        val segments = mutableListOf<MutableList<JReferenceStore.Frame>>()
        var segment = mutableListOf<JReferenceStore.Frame>()

        fun finish() {
            if (segment.isNotEmpty()) segments.add(segment)
            segment = mutableListOf()
        }

        for (frame in frames) {
            val current = frame.sample
            if (current == null) continue
            val previous = segment.lastOrNull()
            if (previous == null) {
                segment.add(frame)
                continue
            }
            val before = requireNotNull(previous.sample)
            val gap = frame.timeMs - previous.timeMs
            if (before.side != current.side || gap <= 0L || gap > 300L) {
                finish()
                segment.add(frame)
                continue
            }
            val missingCount = (gap / JReferenceStore.STEP_MS).toInt() - 1
            for (missing in 1..missingCount) {
                val ratio = missing.toDouble() / (missingCount + 1)
                val points = Array(21) { i ->
                    doubleArrayOf(
                        before.points[i][0] + (current.points[i][0] - before.points[i][0]) * ratio,
                        before.points[i][1] + (current.points[i][1] - before.points[i][1]) * ratio
                    )
                }
                val flex = DoubleArray(10) { i ->
                    before.flex[i] + (current.flex[i] - before.flex[i]) * ratio
                }
                val sample = JPracticeEngine.Sample(points, flex, before.side)
                segment.add(
                    JReferenceStore.Frame(
                        previous.timeMs + missing * JReferenceStore.STEP_MS,
                        sample
                    )
                )
            }
            segment.add(frame)
        }
        finish()

        val candidates = segments.sortedByDescending { it.size }
        var lastError: Exception? = null
        for (candidate in candidates) {
            if (candidate.size < 12) continue
            val offset = candidate.first().timeMs
            val normalized = candidate.map { it.copy(timeMs = it.timeMs - offset) }
            try {
                MotionReferenceStore.validate(normalized, letter)
                return normalized
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalStateException(
            lastError?.message ?: "No se detectó una secuencia continua de la mano en el video"
        )
    }
}
