package com.mechrobotix.aprendels

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.mechrobotix.aprendels.databinding.ActivityWordPracticeBinding
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException

/** Práctica lista para usar. Referencias automáticas por video; no hay editor ni mapeo manual. */
class WordPracticeActivity:ComponentActivity() {
    private lateinit var b:ActivityWordPracticeBinding
    private var module:LearningModule?=null
    private var index=0
    private var reference:WordReferenceStore.Reference?=null
    private var engine:WordPracticeEngine?=null // Hilo principal exclusivamente.
    private val worker=Executors.newSingleThreadExecutor()
    private var detector:HandLandmarker?=null // worker exclusivamente.
    private val samples=WordHandSamples()
    private var provider:ProcessCameraProvider?=null
    private var analysis:ImageAnalysis?=null
    @Volatile private var closing=false
    @Volatile private var active=false
    @Volatile private var ready=false
    @Volatile private var generation=0
    @Volatile private var session=0
    @Volatile private var debugOn=false
    private var dropped=0 // frames descartados por llegar tarde (>600 ms)
    private var preparing=false
    private var videoReady=false
    private var cameraReady=false
    private var completed=false
    private var lastResult=0L
    private var lastInference=0L // worker exclusivamente.
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted)startCamera() else {b.wordStatus.text="Permite la cámara para practicar. Toca Reintentar cuando estés listo.";b.wordReplay.text="Reintentar"}
    }
    private val tick=object:Runnable {
        override fun run(){
            if(closing || !active)return
            val ref=reference
            if(videoReady && ref!=null && b.wordVideo.isPlaying && b.wordVideo.currentPosition>=ref.endMs){
                b.wordVideo.seekTo(ref.startMs);b.wordVideo.start()
            }
            if(ready && videoReady && cameraReady && !completed && SystemClock.elapsedRealtime()-lastResult>600){
                engine?.missing(SystemClock.elapsedRealtime(),"Coloca las manos dentro de la cámara");render()
                if(debugOn){val now=SystemClock.elapsedRealtime();b.wordDebug.text="Cámara sin resultados hace ${now-lastResult}ms (descartados: $dropped)\n"+(engine?.debug(now,null) ?: "")}
            }
            b.root.postDelayed(this,100)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        b=ActivityWordPracticeBinding.inflate(layoutInflater);setContentView(b.root);AppUi.insets(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.wordCamera.scaleType=PreviewView.ScaleType.FIT_CENTER
        b.wordCamera.implementationMode=PreviewView.ImplementationMode.COMPATIBLE
        // No ampliar 1.30: las palabras necesitan ver ambos brazos completos.
        b.wordBack.setOnClickListener{finish()}
        b.wordTitle.setOnLongClickListener{
            debugOn=!debugOn;b.wordDebug.visibility=if(debugOn)android.view.View.VISIBLE else android.view.View.GONE;true
        }
        b.wordNext.setOnClickListener{
            val m=module ?: return@setOnClickListener
            if(!completed)return@setOnClickListener
            if(index<m.lessons.lastIndex){index++;loadLesson()}else finish()
        }
        b.wordReplay.setOnClickListener{
            if(preparing)return@setOnClickListener
            if(!hasCameraPermission()){permission.launch(Manifest.permission.CAMERA);return@setOnClickListener}
            if(!cameraReady)startCamera()
            if(!ready || !videoReady){loadLesson();return@setOnClickListener}
            session++;engine?.reset();completed=false;b.wordNext.isEnabled=false
            b.wordVideo.seekTo(reference?.startMs ?: 0);b.wordVideo.start();render()
        }
        try {
            val id=intent.getStringExtra("module_id") ?: "saludos_despedidas"
            module=WordCatalog.load(this).first{it.id==id}
            require(module!!.lessons.isNotEmpty()){"Este módulo todavía no tiene videos"}
            index=(savedInstanceState?.getInt("word_index") ?: 0).coerceIn(0,module!!.lessons.lastIndex)
            loadLesson()
            if(hasCameraPermission())startCamera() else permission.launch(Manifest.permission.CAMERA)
        }catch(e:Exception){b.wordStatus.text="No se pudo abrir el módulo: ${e.message}"}
    }
    private fun hasCameraPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    private fun loadLesson() {
        val m=module ?: return
        val lesson=m.lessons[index];val token=++generation;session++
        ready=false;preparing=true;completed=false;videoReady=false;reference=null;engine=null
        b.wordReplay.isEnabled=false;b.wordStatus.setTextColor(Color.WHITE)
        b.wordVideo.stopPlayback();b.wordProgress.progress=0;b.wordNext.isEnabled=false
        b.wordTitle.text="Haz la seña: ${lesson.title}"
        b.wordCounter.text="${m.title} · ${index+1}/${m.lessons.size}"
        b.wordStatus.text="Preparando ${lesson.title}…";b.wordReplay.text="Repetir ejemplo"
        b.wordNext.text=if(index==m.lessons.lastIndex)"Finalizar" else "Siguiente"
        worker.execute {
            try {
                val ref=WordReferenceStore.loadOrGenerate(applicationContext,lesson,
                    cancelled={closing || generation!=token},onProgress={percent ->
                        runOnUiThread{if(!closing && generation==token){
                            b.wordStatus.text="Preparando ejemplo automáticamente… $percent%"
                        }}
                    })
                if(closing || generation!=token)return@execute
                if(detector==null)detector=HandLandmarker.createFromOptions(this,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                        .setRunningMode(RunningMode.VIDEO).setNumHands(2)
                        .setMinHandDetectionConfidence(.30f).setMinHandPresenceConfidence(.30f).setMinTrackingConfidence(.30f).build())
                samples.reset()
                val prepared=WordPracticeEngine(ref.keys,lesson.tolerance,true)
                runOnUiThread {
                    if(closing || generation!=token)return@runOnUiThread
                    reference=ref;engine=prepared;ready=true;preparing=false;b.wordReplay.isEnabled=true;lastResult=SystemClock.elapsedRealtime()
                    b.wordVideo.setOnPreparedListener{player ->
                        if(!closing && generation==token){
                            player.setVolume(0f,0f);player.isLooping=false;videoReady=true
                            b.wordVideo.seekTo(ref.startMs);if(active)b.wordVideo.start();render()
                        }
                    }
                    b.wordVideo.setOnCompletionListener{if(active && generation==token){b.wordVideo.seekTo(ref.startMs);b.wordVideo.start()}}
                    b.wordVideo.setOnErrorListener{_,_,_ ->
                        if(generation==token){videoReady=false;ready=false;engine?.reset();b.wordNext.isEnabled=false
                            b.wordStatus.text="No se pudo reproducir el ejemplo. Toca Reintentar.";b.wordReplay.text="Reintentar"};true
                    }
                    b.wordVideo.setVideoURI(Uri.parse("android.resource://$packageName/${lesson.video}"))
                }
            }catch(_:CancellationException){
                // Otra palabra o actividad reemplazó esta tarea.
            }catch(e:Exception){runOnUiThread{
                if(!closing && generation==token){ready=false;preparing=false;b.wordReplay.isEnabled=true;b.wordStatus.text="No se pudo preparar esta palabra: ${e.message}";b.wordReplay.text="Reintentar"}
            }}
        }
    }
    private fun render() {
        if(!hasCameraPermission() || !cameraReady || !videoReady)return
        val e=engine ?: return
        completed=e.stage==WordPracticeEngine.Stage.SUCCESS
        b.wordProgress.progress=e.progress;b.wordNext.isEnabled=completed
        b.wordStatus.text=if(completed)"¡${module!!.lessons[index].title}: palabra completada!" else e.message
        b.wordStatus.setTextColor(if(completed)Color.rgb(185,246,202) else Color.WHITE)
    }
    private fun startCamera() {
        b.wordCamera.post {
            if(closing)return@post
            val future=ProcessCameraProvider.getInstance(this)
            future.addListener({
                if(closing)return@addListener
                try {
                    val p=future.get();provider=p
                    val rotation=b.wordCamera.display?.rotation ?: 0
                    val preview=Preview.Builder().setTargetRotation(rotation).setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                    preview.setSurfaceProvider(b.wordCamera.surfaceProvider)
                    val a=ImageAnalysis.Builder().setTargetRotation(rotation).setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis=a;a.setAnalyzer(worker){process(it)};p.unbindAll()
                    p.bindToLifecycle(this,CameraSelector.DEFAULT_FRONT_CAMERA,preview,a);cameraReady=true;render()
                }catch(e:Exception){cameraReady=false;b.wordStatus.text="No se pudo iniciar la cámara: ${e.message}";b.wordReplay.text="Reintentar"}
            },ContextCompat.getMainExecutor(this))
        }
    }
    private fun process(proxy:ImageProxy) {
        val token=generation;val attempt=session
        var bitmap:Bitmap?=null
        try {
            if(closing || !active || !ready)return
            val model=detector ?: return
            val time=maxOf(SystemClock.elapsedRealtime(),lastInference+1);lastInference=time
            val frame=toBitmap(proxy);bitmap=frame
            val image=BitmapImageBuilder(frame).build()
            val result=try{model.detectForVideo(image,time)}finally{image.close()}
            val sample=samples.read(result,frame.width,frame.height,time)
            val info=if(debugOn){
                val conf=result.handedness().joinToString(" "){h -> h.firstOrNull()?.let{"${it.categoryName()} ${(it.score()*100).toInt()}%"} ?: "?"}
                "MediaPipe: ${result.landmarks().size} mano(s) [$conf] -> válidas: ${sample.hands.size} (descartados: $dropped)\n"
            } else ""
            runOnUiThread {
                if(closing || !active || token!=generation || attempt!=session || !ready || !videoReady || completed)return@runOnUiThread
                // No utilizar un resultado atrasado después de una pausa/carga fuerte de UI.
                if(SystemClock.elapsedRealtime()-time>600){dropped++;return@runOnUiThread}
                lastResult=time;engine?.update(time,sample);render()
                if(debugOn)b.wordDebug.text=info+(engine?.debug(time,sample) ?: "")
            }
        }catch(e:Exception){
            android.util.Log.e("WordPractice","Error procesando cámara",e)
            runOnUiThread{if(!closing && active && token==generation && attempt==session && !completed){
                engine?.missing(SystemClock.elapsedRealtime(),"No se pudo leer la mano");render()
            }}
        }finally{bitmap?.recycle();proxy.close()}
    }
    private fun toBitmap(proxy:ImageProxy):Bitmap {
        val w=proxy.width;val h=proxy.height;require(w%2==0 && h%2==0)
        val nv21=ByteArray(w*h*3/2)
        fun at(p:ImageProxy.PlaneProxy,x:Int,y:Int)=p.buffer.get(p.buffer.position()+y*p.rowStride+x*p.pixelStride)
        for(y in 0 until h)for(x in 0 until w)nv21[y*w+x]=at(proxy.planes[0],x,y)
        var n=w*h
        for(y in 0 until h/2)for(x in 0 until w/2){nv21[n++]=at(proxy.planes[2],x,y);nv21[n++]=at(proxy.planes[1],x,y)}
        val stream=ByteArrayOutputStream();check(YuvImage(nv21,ImageFormat.NV21,w,h,null).compressToJpeg(Rect(0,0,w,h),85,stream))
        val bytes=stream.toByteArray();val base=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        val rotation=proxy.imageInfo.rotationDegrees
        if(rotation==0)return base
        return Bitmap.createBitmap(base,0,0,w,h,Matrix().apply{postRotate(rotation.toFloat())},true).also{if(it!==base)base.recycle()}
    }
    override fun onResume(){
        super.onResume();active=true;session++;engine?.reset();completed=false;b.wordNext.isEnabled=false;b.wordProgress.progress=0
        if(videoReady){b.wordVideo.seekTo(reference?.startMs ?: 0);b.wordVideo.start()}
        if(ready)render();b.root.removeCallbacks(tick);b.root.post(tick)
    }
    override fun onPause(){active=false;session++;b.root.removeCallbacks(tick);b.wordVideo.pause();engine?.reset();super.onPause()}
    override fun onSaveInstanceState(outState:Bundle){outState.putInt("word_index",index);super.onSaveInstanceState(outState)}
    override fun onDestroy(){
        closing=true;generation++;b.root.removeCallbacks(tick);b.wordVideo.stopPlayback()
        analysis?.clearAnalyzer();provider?.unbindAll()
        worker.execute{detector?.close();detector=null};worker.shutdown();super.onDestroy()
    }
}
