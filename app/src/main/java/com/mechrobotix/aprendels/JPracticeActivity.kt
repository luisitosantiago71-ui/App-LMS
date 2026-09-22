package com.mechrobotix.aprendels

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Demostración y evaluación experimental de J. Reutiliza el video del usuario.
 * La referencia se calcula con el mismo modelo usado para la cámara.
 */
class JPracticeActivity:ComponentActivity() {
    private lateinit var bmiPanel:Bmi160Panel
    private val bmiPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(::bmiPanel.isInitialized) bmiPanel.permissionResult(granted)
    }
    private lateinit var root:LinearLayout
    private lateinit var status:TextView
    private lateinit var progress:ProgressBar
    private lateinit var video:VideoView
    private lateinit var preview:PreviewView
    private lateinit var guide:JPracticeView
    private lateinit var practice:Button
    private lateinit var accept:Button
    private lateinit var mirrorButton:Button
    private var engine:JPracticeEngine?=null
    private var tolerance=JPracticeEngine.Tolerance.NORMAL
    private val executor=Executors.newSingleThreadExecutor()
    private var detector:HandLandmarker?=null
    private var provider:ProcessCameraProvider?=null
    private var cameraPreview:Preview?=null
    private var analysis:ImageAnalysis?=null
    @Volatile private var closing=false
    @Volatile private var active=false
    @Volatile private var practicing=false
    @Volatile private var generation=0
    private var mirrored=false
    private var demoFinished=false
    private var referenceError:String?=null
    private var lastResultAt=0L
    private var lastW=0
    private var lastH=0
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok) startPractice() else status.text="Permite la cámara para practicar; puedes volver a ver el video."
    }
    private val timeout=object:Runnable {
        override fun run() {
            if(closing || !active) return
            if(practicing && SystemClock.elapsedRealtime()-lastResultAt>500) {
                engine?.missing(SystemClock.elapsedRealtime(),"No hay seguimiento reciente"); guide.clear(); render()
            }
            root.postDelayed(this,200)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mirrored=savedInstanceState?.getBoolean("j_mirror") ?: intent.getBooleanExtra("mirror",false)
        val storedTolerance=getSharedPreferences("j_practice_tolerance",MODE_PRIVATE).getInt("level",1)
        tolerance=JPracticeEngine.Tolerance.values().getOrElse(storedTolerance) { JPracticeEngine.Tolerance.NORMAL }
        buildUi()
        loadReference()
    }
    private fun buildUi() {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val b=insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(b.left,b.top,b.right,b.bottom); insets
        }
        ViewCompat.requestApplyInsets(root)
        fun label(value:String,size:Float)=TextView(this).apply {
            text=value; textSize=size; setTextColor(Color.WHITE); setPadding(dp(12),dp(4),dp(12),dp(4))
        }
        root.addView(label("Letra J · demostración y práctica",20f))
        root.addView(label("Forma A, realiza la curva y termina en B. Sin seguir un dibujo. Mantén la mano visible.",13f))
        val area=FrameLayout(this)
        root.addView(area,LinearLayout.LayoutParams(-1,0,1f))
        preview=PreviewView(this).apply {
            scaleType=PreviewView.ScaleType.FIT_CENTER
            implementationMode=PreviewView.ImplementationMode.COMPATIBLE
        }
        guide=JPracticeView(this)
        video=VideoView(this).apply { setBackgroundColor(Color.BLACK) }
        area.addView(preview,FrameLayout.LayoutParams(-1,-1))
        area.addView(guide,FrameLayout.LayoutParams(-1,-1))
        area.addView(video,FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER))
        video.setOnPreparedListener { player ->
            player.setVolume(0f,0f)
            if(active && !practicing) {
                if(android.os.Build.VERSION.SDK_INT>=26) player.seekTo(clipStartMs.toLong(),android.media.MediaPlayer.SEEK_CLOSEST)
                else video.seekTo(clipStartMs)
                video.setBackgroundColor(Color.TRANSPARENT); video.start()
                root.removeCallbacks(clipEnd); root.post(clipEnd)
            }
        }
        video.setOnCompletionListener {
            demoFinished=true; refreshPracticeButton()
            status.text=if(engine!=null) "Ahora imita la demostración. Pulsa Practicar J." else referenceError ?: "Preparando la referencia del video…"
        }
        video.setOnErrorListener { _,_,_ ->
            demoFinished=false; refreshPracticeButton()
            status.text="No se pudo reproducir letra_j.mp4. Comprueba el archivo en res/raw."; true
        }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(8)))
        status=label("Preparando la referencia…",15f).apply { minHeight=dp(66) }
        root.addView(status)
        val row=LinearLayout(this)
        row.addView(Button(this).apply {
            text="Ver demostración"; textSize=12f; setOnClickListener { showDemo() }
        },LinearLayout.LayoutParams(0,dp(48),1f))
        mirrorButton=Button(this).apply {
            text=if(mirrored) "Espejo: sí" else "Espejo: no"; textSize=12f
            setOnClickListener {
                mirrored=!mirrored
                text=if(mirrored) "Espejo: sí" else "Espejo: no"
                engine?.setMirrored(mirrored)
                showDemo()
            }
        }
        row.addView(mirrorButton,LinearLayout.LayoutParams(0,dp(48),1f)); root.addView(row)
        val toleranceButton=Button(this).apply {
            text="Tolerancia: ${tolerance.label}"; textSize=12f
            setOnClickListener {
                val choices=JPracticeEngine.Tolerance.values()
                android.app.AlertDialog.Builder(this@JPracticeActivity)
                    .setTitle("Margen para practicar J")
                    .setSingleChoiceItems(arrayOf("Precisa · postura y curva", "Normal · postura y curva", "Flexible · postura y curva"),tolerance.ordinal) { dialog,which ->
                        tolerance=choices[which]
                        getSharedPreferences("j_practice_tolerance",MODE_PRIVATE).edit().putInt("level",which).apply()
                        generation++; engine?.setTolerance(tolerance); guide.clear()
                        lastResultAt=SystemClock.elapsedRealtime()
                        text="Tolerancia: ${tolerance.label}"
                        if(practicing) render()
                        else { accept.isEnabled=false; progress.progress=0 }
                        dialog.dismiss()
                    }.setNegativeButton("Cancelar",null).show()
            }
        }
        root.addView(toleranceButton,LinearLayout.LayoutParams(-1,dp(48)))
        bmiPanel=Bmi160Panel(this) { bmiPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        root.addView(bmiPanel.button,LinearLayout.LayoutParams(-1,dp(48)))
        val bottom=LinearLayout(this)
        bottom.addView(Button(this).apply { text="Regresar"; textSize=12f; setOnClickListener { finish() } },
            LinearLayout.LayoutParams(0,dp(48),1f))
        practice=Button(this).apply {
            text="Practicar J"; textSize=12f; isEnabled=false
            setOnClickListener {
                if(ContextCompat.checkSelfPermission(this@JPracticeActivity,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
                    startPractice()
                else permission.launch(Manifest.permission.CAMERA)
            }
        }
        bottom.addView(practice,LinearLayout.LayoutParams(0,dp(48),1f))
        accept=Button(this).apply {
            text="Continuar"; textSize=12f; isEnabled=false
            setOnClickListener {
                if(engine?.stage==JPracticeEngine.Stage.SUCCESS) {
                    setResult(Activity.RESULT_OK); finish()
                }
            }
        }
        bottom.addView(accept,LinearLayout.LayoutParams(0,dp(48),1f)); root.addView(bottom)
    }
    private fun refreshPracticeButton() { practice.isEnabled=engine!=null && demoFinished }
    private fun showDemo() {
        if(engine==null) return
        practicing=false; generation++; root.removeCallbacks(clipEnd)
        engine?.reset(); guide.clear(); demoFinished=false
        practice.text="Practicar J"; refreshPracticeButton(); accept.isEnabled=false; progress.progress=0
        video.visibility=View.VISIBLE; guide.visibility=View.GONE
        video.scaleX=if(mirrored) -1f else 1f
        video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.letra_j}"))
        status.text="Observa cómo se realiza la J. Después te tocará imitarla."
    }
    private var clipStartMs=0
    private var clipEndMs=0
    private val clipEnd=object:Runnable {
        override fun run() {
            if(closing || !active || practicing) return
            if(video.isPlaying && video.currentPosition>=clipEndMs && clipEndMs>clipStartMs) {
                video.pause(); demoFinished=true; refreshPracticeButton()
                status.text="Ahora imita el tramo revisado. Pulsa Practicar J."
                return
            }
            root.postDelayed(this,40)
        }
    }
    private fun loadReference() {
        executor.execute {
            try {
                val source=JReferenceStore.fingerprint(this)
                val stored=requireNotNull(JReferenceStore.load(this,source)) { "Primero prepara y guarda la referencia de J" }
                val built=JPracticeEngine(JReferenceStore.validate(stored.frames))
                // VIDEO mantiene el seguimiento entre imágenes; se usa en este único ejecutor.
                detector=HandLandmarker.createFromOptions(this,HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumHands(2).build())
                runOnUiThread {
                    if(!closing) {
                        engine=built; built.setTolerance(tolerance); built.setMirrored(mirrored); guide.engine=built
                        clipStartMs=stored.frames.first().timeMs.toInt()
                        clipEndMs=stored.frames.last().timeMs.toInt()
                        showDemo()
                    }
                }
            } catch(e:Exception) {
                runOnUiThread { if(!closing) {
                    referenceError="No se pudo abrir la referencia: ${e.message}"
                    status.text=referenceError; practice.isEnabled=false
                } }
            }
        }
    }
    private fun startPractice() {
        if(engine==null || !demoFinished || closing) return
        generation++; practicing=true; engine?.reset()
        video.pause(); video.visibility=View.GONE; guide.visibility=View.VISIBLE; guide.clear()
        practice.text="Reintentar"; lastResultAt=SystemClock.elapsedRealtime(); render()
        if(analysis==null) startCamera()
    }
    private fun startCamera() {
        preview.post {
            if(closing || analysis!=null) return@post
            val future=ProcessCameraProvider.getInstance(this)
            future.addListener({
                if(closing || analysis!=null) return@addListener
                try {
                    provider=future.get()
                    val rotation=preview.display?.rotation ?: Surface.ROTATION_0
                    val pv=Preview.Builder().setTargetRotation(rotation).setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                    val an=ImageAnalysis.Builder().setTargetRotation(rotation).setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    cameraPreview=pv; analysis=an
                    pv.setSurfaceProvider(preview.surfaceProvider)
                    an.setAnalyzer(executor) { process(it) }
                    provider?.bindToLifecycle(this,CameraSelector.DEFAULT_FRONT_CAMERA,pv,an)
                } catch(e:Exception) {
                    analysis?.clearAnalyzer(); analysis?.let { provider?.unbind(it) }; cameraPreview?.let { provider?.unbind(it) }
                    analysis=null; cameraPreview=null; practicing=false
                    status.text="No se pudo abrir la cámara: ${e.message}"
                }
            },ContextCompat.getMainExecutor(this))
        }
    }
    private fun render() {
        val e=engine ?: return
        status.text=e.message+"\nSeguimiento: "+e.trackingDescription; progress.progress=e.progress
        accept.isEnabled=e.stage==JPracticeEngine.Stage.SUCCESS; guide.invalidate()
    }
    private fun process(proxy:ImageProxy) {
        val token=generation; val captured=SystemClock.elapsedRealtime()
        try {
            if(closing || !active || !practicing) return
            val model=detector ?: return
            val bitmap=imageProxyToBitmap(proxy)
            val w=bitmap.width; val h=bitmap.height
            val image=BitmapImageBuilder(bitmap).build()
            val result=try { model.detectForVideo(image,captured) } finally { image.close() }
            val actual=JLandmarkSamples.sample(result,w,h,true)
            runOnUiThread {
                if(closing || !active || !practicing || token!=generation) return@runOnUiThread
                val e=engine ?: return@runOnUiThread
                val now=SystemClock.elapsedRealtime()
                if(now-captured>500) { e.missing(now,"La cámara procesa demasiado lento"); guide.clear(); render(); return@runOnUiThread }
                if(w!=lastW || h!=lastH) { e.reset(); lastW=w; lastH=h }
                lastResultAt=now
                if(actual==null) e.missing(captured,"Muestra una sola mano completa")
                else if(actual.palm/w<.055) e.missing(captured,"Acerca un poco la mano")
                else e.update(captured,actual)
                guide.update(actual,w,h); render()
            }
        } catch(e:Exception) {
            android.util.Log.e("JPractice","Fallo en detección",e)
            runOnUiThread {
                if(!closing && active && practicing && token==generation) {
                    engine?.missing(SystemClock.elapsedRealtime(),"No se pudo leer la mano"); guide.clear(); render()
                }
            }
        } finally { proxy.close() }
    }

    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap {
        val w = proxy.width; val h = proxy.height
        require(w % 2 == 0 && h % 2 == 0) { "Resolución YUV no compatible" }
        val nv21 = ByteArray(w*h*3/2)
        fun sample(plane: ImageProxy.PlaneProxy, x: Int, y: Int): Byte =
            plane.buffer.get(plane.buffer.position() + y*plane.rowStride + x*plane.pixelStride)
        for (y in 0 until h) for (x in 0 until w) nv21[y*w+x] = sample(proxy.planes[0], x, y)
        var offset = w*h
        for (y in 0 until h/2) for (x in 0 until w/2) {
            nv21[offset++] = sample(proxy.planes[2], x, y)
            nv21[offset++] = sample(proxy.planes[1], x, y)
        }
        val out = ByteArrayOutputStream()
        check(YuvImage(nv21, ImageFormat.NV21, w, h, null)
            .compressToJpeg(Rect(0,0,w,h), 90, out))
        val bytes = out.toByteArray()
        val base = checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        val angle = proxy.imageInfo.rotationDegrees
        if (angle == 0) return base
        val rotated = Bitmap.createBitmap(base,0,0,w,h,
            Matrix().apply { postRotate(angle.toFloat()) }, true)
        if (rotated !== base) base.recycle()
        return rotated
    }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("j_mirror",mirrored); super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume(); active=true; generation++; bmiPanel.resume()
        lastResultAt=SystemClock.elapsedRealtime(); root.post(timeout)
        if(practicing) { engine?.reset(); guide.clear(); render() }
        else if(!demoFinished && engine!=null) { video.start(); root.removeCallbacks(clipEnd); root.post(clipEnd) }
    }
    override fun onPause() {
        bmiPanel.pause()
        active=false; generation++; root.removeCallbacks(timeout); root.removeCallbacks(clipEnd); video.pause()
        if(practicing) { engine?.reset(); guide.clear() }
        super.onPause()
    }
    override fun onDestroy() {
        bmiPanel.destroy()
        closing=true; active=false; generation++; root.removeCallbacks(timeout)
        root.removeCallbacks(clipEnd); video.stopPlayback(); analysis?.clearAnalyzer()
        analysis?.let { provider?.unbind(it) }; cameraPreview?.let { provider?.unbind(it) }
        executor.execute { detector?.close(); detector=null }; executor.shutdown()
        super.onDestroy()
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
