package com.mechrobotix.aprendels

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.File
import java.util.concurrent.CancellationException

/** Se ejecuta en el worker, nunca en el hilo de interfaz ni con el detector de cámara. */
object WordVideoReferenceGenerator {
    const val MODEL_ASSET="hand_landmarker.task"
    const val CONFIDENCE=.45f
    fun generate(context:Context,video:File,cancelled:()->Boolean,onProgress:(Int)->Unit):WordReferenceBuilder.Result {
        val retriever=MediaMetadataRetriever()
        var detector:HandLandmarker?=null
        try {
            retriever.setDataSource(video.absolutePath)
            val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("No se pudo leer la duración del video")
            require(duration in 1000L..WordReferenceBuilder.MAX_VIDEO_MS.toLong()){"Usa videos de 1 a 60 segundos"}
            checkCancelled(cancelled)
            val model=HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.VIDEO).setNumHands(2)
                .setMinHandDetectionConfidence(CONFIDENCE).setMinHandPresenceConfidence(CONFIDENCE)
                .setMinTrackingConfidence(CONFIDENCE).build())
            detector=model
            val samples=WordHandSamples(mirrorX=false)
            val frames=mutableListOf<WordPracticeEngine.Frame>()
            var time=0L
            while(time<duration) {
                checkCancelled(cancelled)
                // OPTION_CLOSEST incluye fotogramas intermedios, no solo keyframes del códec.
                var bitmap:Bitmap?=null
                try {
                    val original=if(Build.VERSION.SDK_INT>=27)
                        retriever.getScaledFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST,960,960)
                    else retriever.getFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST)
                    bitmap=requireNotNull(original){"No se pudo decodificar el video en $time ms"}
                    val scale=minOf(1.0,960.0/maxOf(original.width,original.height))
                    if(scale<1) {
                        val small=Bitmap.createScaledBitmap(original,(original.width*scale).toInt().coerceAtLeast(1),
                            (original.height*scale).toInt().coerceAtLeast(1),true)
                        bitmap=small;if(small!==original)original.recycle()
                    }
                    val resized=requireNotNull(bitmap)
                    if(resized.config!=Bitmap.Config.ARGB_8888) {
                        val argb=requireNotNull(resized.copy(Bitmap.Config.ARGB_8888,false))
                        bitmap=argb;if(argb!==resized)resized.recycle()
                    }
                    val frame=requireNotNull(bitmap)
                    val image=BitmapImageBuilder(frame).build()
                    val result=try{model.detectForVideo(image,time)}finally{image.close()}
                    frames.add(samples.read(result,frame.width,frame.height,time))
                } finally {bitmap?.let{if(!it.isRecycled)it.recycle()}}
                onProgress(((time+WordReferenceBuilder.STEP_MS)*100/duration).toInt().coerceAtMost(100))
                time+=WordReferenceBuilder.STEP_MS
            }
            checkCancelled(cancelled)
            return WordReferenceBuilder.build(frames,duration)
        } finally {
            try{detector?.close()}finally{retriever.release()}
        }
    }
    fun checkCancelled(cancelled:()->Boolean){if(cancelled() || Thread.currentThread().isInterrupted)throw CancellationException()}
}
