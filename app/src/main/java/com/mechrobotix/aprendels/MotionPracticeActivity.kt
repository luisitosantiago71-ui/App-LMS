package com.mechrobotix.aprendels

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
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
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

class MotionPracticeActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var preview: PreviewView
    private lateinit var overlay: MotionPracticeView
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var progress: ProgressBar

    // ---> NUEVO: El reproductor de video
    private lateinit var videoReference: VideoView

    private val engine = MotionPracticeEngine()
    private val executor = Executors.newSingleThreadExecutor()
    private var detector: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null
    private var analysis: ImageAnalysis? = null

    @Volatile private var closing = false
    @Volatile private var active = false
    @Volatile private var session = 0
    private var ready = false
    private var lastW = 0
    private var lastH = 0
    private var lastResultAt = 0L
    private var haveResult = false
    private var diagnosticAt = 0L

    private var currentTrajectory: TrajectoryTemplate? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) startCamera() else status.text = "Se necesita permiso de cámara. Regresa para salir."
    }

    private val timeout = object: Runnable {
        override fun run() {
            if(!active || closing) return
            if(haveResult && SystemClock.elapsedRealtime() - lastResultAt > MotionPracticeEngine.MAX_GAP_MS) {
                haveResult = false
                overlay.clear()
                show(engine.missing("No llegan imágenes recientes"))
                diagnostics.text = "Sin lectura reciente de ángulos"
            }
            root.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        loadTrajectory("J")

        executor.execute {
            try {
                detector = HandLandmarker.createFromOptions(this,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                        .setRunningMode(RunningMode.IMAGE).setNumHands(2).build())
                runOnUiThread {
                    if(!closing) {
                        ready = true
                        status.text = "Prepara la postura y colócate en INICIO"
                        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                            startCamera()
                        else permission.launch(Manifest.permission.CAMERA)
                    }
                }
            } catch(e: Exception) {
                runOnUiThread { if(!closing) status.text = "No se pudo abrir el modelo: ${e.message}" }
            }
        }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        ViewCompat.requestApplyInsets(root)
        fun label(textValue: String, size: Float) = TextView(this).apply {
            text = textValue; textSize = size; setTextColor(Color.WHITE); setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        root.addView(label("Práctica de Movimiento", 20f))
        root.addView(label("Realiza la seña manteniendo la postura de los dedos firme mientras sigues la trayectoria punteada.", 13f))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val btnChooseLetter = Button(this).apply {
            text = "Elegir Seña"
            textSize = 12f
            setOnClickListener {
                val letters = TrajectoryCatalog.trajectories.keys.toTypedArray()
                if (letters.isNotEmpty()) {
                    android.app.AlertDialog.Builder(this@MotionPracticeActivity)
                        .setTitle("Practicar seña con movimiento")
                        .setItems(letters) { _, which ->
                            loadTrajectory(letters[which])
                        }.show()
                }
            }
        }

        val angles = Button(this).apply {
            text = "Ver ángulos"
            textSize = 12f
            setOnClickListener {
                val visible = diagnostics.visibility != View.VISIBLE
                diagnostics.visibility = if(visible) View.VISIBLE else View.GONE
                text = if(visible) "Ocultar ángulos" else "Ver ángulos"
            }
        }
        controls.addView(btnChooseLetter, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(angles, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(controls)

        val cameraArea = FrameLayout(this)
        root.addView(cameraArea, LinearLayout.LayoutParams(-1, 0, 1f))
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = MotionPracticeView(this)

        // --- INICIO NUEVO: Configuración del Reproductor de Video ---
        videoReference = VideoView(this).apply {
            visibility = View.GONE
        }
        val videoParams = FrameLayout.LayoutParams(dp(120), dp(160)).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(dp(16), dp(16), dp(16), dp(16))
        }
        // --- FIN NUEVO ---

        cameraArea.addView(preview, FrameLayout.LayoutParams(-1, -1))
        cameraArea.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        cameraArea.addView(videoReference, videoParams) // Añadir el video arriba de todo

        diagnostics = label("Esperando ángulos 3D…", 12f).apply {
            visibility = View.GONE; setBackgroundColor(0xD9000000.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cameraArea.addView(diagnostics, FrameLayout.LayoutParams(-1, -2))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        status = label("Preparando cámara y modelo…", 15f).apply { minHeight = dp(60) }
        root.addView(status)

        val bottom = LinearLayout(this)
        bottom.addView(Button(this).apply { text = "Regresar"; setOnClickListener { finish() } },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(Button(this).apply {
            text = "Repetir"
            setOnClickListener { session++; overlay.clear(); show(engine.reset()) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(bottom)
    }

    private fun loadTrajectory(letter: String) {
        val template = TrajectoryCatalog.trajectories[letter] ?: return
        currentTrajectory = template
        overlay.currentTrajectory = template

        val wpArray = template.waypoints.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
        engine.setWaypoints(wpArray)

        // --- INICIO NUEVO: Cargar el video asignado ---
        val videoId = template.videoResId
        if (videoId != null) {
            videoReference.visibility = View.VISIBLE
            val uri = Uri.parse("android.resource://$packageName/$videoId")
            videoReference.setVideoURI(uri)
            videoReference.setOnPreparedListener { mp ->
                mp.isLooping = true
                videoReference.start()
            }
        } else {
            videoReference.visibility = View.GONE
            videoReference.stopPlayback()
        }
        // --- FIN NUEVO ---

        session++
        overlay.clear()
        show(engine.reset())
        status.text = "Mantén la forma de la letra $letter en el punto de INICIO"
    }

    private fun show(f: MotionPracticeEngine.Feedback) {
        status.text = f.message; progress.progress = f.progress; overlay.showFeedback(f)
    }

    private fun startCamera() {
        preview.post {
            if(closing || !ready || analysis != null) return@post
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                if(closing || analysis != null) return@addListener
                try {
                    provider = future.get()
                    val rotation = preview.display?.rotation ?: Surface.ROTATION_0
                    val pv = Preview.Builder().setTargetRotation(rotation)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                    val an = ImageAnalysis.Builder().setTargetRotation(rotation)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    cameraPreview = pv; analysis = an
                    pv.setSurfaceProvider(preview.surfaceProvider)
                    an.setAnalyzer(executor) { process(it) }
                    provider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, pv, an)
                } catch(e: Exception) {
                    analysis?.clearAnalyzer()
                    analysis?.let { provider?.unbind(it) }
                    cameraPreview?.let { provider?.unbind(it) }
                    analysis = null; cameraPreview = null
                    status.text = "No se pudo abrir la cámara: ${e.message}"
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun process(proxy: ImageProxy) {
        val token = session
        val captured = SystemClock.elapsedRealtime()
        try {
            if(closing || !active) return
            val model = detector ?: return
            val bitmap = imageProxyToBitmap(proxy)
            val w = bitmap.width; val h = bitmap.height
            val image = BitmapImageBuilder(bitmap).build()
            val result = try { model.detect(image) } finally { image.close() }

            var problem: String? = null
            var sample: MotionPracticeEngine.Sample? = null
            var draw = emptyList<PointF>()

            if(result.landmarks().size != 1) problem = if(result.landmarks().isEmpty()) "Muestra una mano completa" else "Usa una sola mano"
            else {
                val landmarks = result.landmarks()[0]
                val world = result.worldLandmarks().firstOrNull()
                if(landmarks.size != 21 || world?.size != 21) problem = "Faltan puntos para calcular la flexión"
                else if(landmarks.any { !it.x().isFinite() || !it.y().isFinite() || it.x() !in 0.01f..0.99f || it.y() !in 0.01f..0.99f })
                    problem = "Mantén todos los dedos dentro de la imagen"
                else {
                    draw = landmarks.map { PointF(1f - it.x(), it.y()) }
                    val p = world.map { doubleArrayOf(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }.toTypedArray()
                    val flex = MotionPracticeEngine.fingerFlexions(p)

                    fun distance(a: Int, b: Int): Double {
                        var sum = 0.0
                        for(i in 0..2) { val d = p[a][i] - p[b][i]; sum += d * d }
                        return sqrt(sum)
                    }
                    val palm3D = distance(0, 9)

                    val targetX: Double
                    val targetY: Double
                    val template = currentTrajectory

                    if (template != null) {
                        val node = template.trackedNode
                        targetX = draw[node].x.toDouble()
                        targetY = draw[node].y.toDouble()
                    } else {
                        val centerIds = listOf(0, 5, 9, 13, 17)
                        targetX = centerIds.map { draw[it].x.toDouble() }.average()
                        targetY = centerIds.map { draw[it].y.toDouble() }.average()
                    }

                    val dx = (draw[9].x - draw[0].x).toDouble()
                    val dy = (draw[9].y - draw[0].y).toDouble() * h / w
                    val side = result.handedness().firstOrNull()?.firstOrNull()?.categoryName() ?: ""

                    sample = MotionPracticeEngine.Sample(targetX, targetY, hypot(dx, dy), atan2(dy, dx), side, flex,
                        if(palm3D > 1e-6) distance(4, 5) / palm3D else Double.NaN)
                }
            }

            val finalSample = sample
            val finalDraw = draw
            val finalProblem = problem

            runOnUiThread {
                if(closing || !active || token != session) return@runOnUiThread
                val now = SystemClock.elapsedRealtime()
                if(now - captured > MotionPracticeEngine.MAX_GAP_MS) {
                    overlay.clear(); show(engine.missing("El procesamiento va demasiado lento")); return@runOnUiThread
                }
                if(lastW != w || lastH != h) { engine.reset(); overlay.clear(); lastW = w; lastH = h }
                lastResultAt = now; haveResult = true

                val f = if(finalSample != null) engine.update(captured, finalSample)
                else engine.missing(finalProblem ?: "Sin lectura")

                overlay.update(w, h, finalDraw, finalSample, f)
                show(f)

                if(finalSample == null) diagnostics.text = "Sin lectura válida de ángulos"
                else if(now - diagnosticAt > 150) {
                    diagnosticAt = now
                    val a = finalSample.flex
                    fun pair(i: Int) = String.format(Locale.getDefault(), "%.0f° / %.0f°", a[i], a[i+1])
                    diagnostics.text = "Flexión 3D estimada (0° ≈ extendido)\n" +
                            "Pulgar MCP/IP: ${pair(0)}\nÍndice PIP/DIP: ${pair(2)}\n" +
                            "Medio PIP/DIP: ${pair(4)}\nAnular PIP/DIP: ${pair(6)}\n" +
                            "Meñique PIP/DIP: ${pair(8)}"
                }
            }
        } catch(e: Exception) {
            android.util.Log.e("MotionPractice", "Error de detección", e)
            runOnUiThread {
                if(!closing && active && token == session) {
                    overlay.clear(); show(engine.missing("Error de detección"))
                }
            }
        } finally { proxy.close() }
    }

    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap {
        val w = proxy.width; val h = proxy.height
        require(w % 2 == 0 && h % 2 == 0) { "Resolución YUV no compatible" }
        val nv21 = ByteArray(w * h * 3 / 2)
        fun sample(plane: ImageProxy.PlaneProxy, x: Int, y: Int): Byte =
            plane.buffer.get(plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride)
        for (y in 0 until h) for (x in 0 until w) nv21[y * w + x] = sample(proxy.planes[0], x, y)
        var offset = w * h
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            nv21[offset++] = sample(proxy.planes[2], x, y)
            nv21[offset++] = sample(proxy.planes[1], x, y)
        }
        val out = ByteArrayOutputStream()
        check(YuvImage(nv21, ImageFormat.NV21, w, h, null)
            .compressToJpeg(Rect(0, 0, w, h), 90, out))
        val bytes = out.toByteArray()
        val base = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val angle = proxy.imageInfo.rotationDegrees
        if (angle == 0) return base
        val rotated = Bitmap.createBitmap(base, 0, 0, w, h,
            Matrix().apply { postRotate(angle.toFloat()) }, true)
        if (rotated !== base) base.recycle()
        return rotated
    }

    override fun onResume() {
        super.onResume(); active = true; session++
        haveResult = false
        if(::root.isInitialized) {
            engine.reset(); overlay.clear()
            if(ready) show(engine.feedback)
            root.post(timeout)
        }
    }

    override fun onPause() {
        active = false; session++
        root.removeCallbacks(timeout)
        engine.reset(); overlay.clear()

        // --- INICIO NUEVO ---
        if (::videoReference.isInitialized && videoReference.isPlaying) {
            videoReference.pause()
        }
        // --- FIN NUEVO ---

        super.onPause()
    }

    override fun onDestroy() {
        closing = true; active = false; session++
        root.removeCallbacks(timeout)
        analysis?.clearAnalyzer()
        analysis?.let { provider?.unbind(it) }
        cameraPreview?.let { provider?.unbind(it) }
        executor.execute { detector?.close(); detector = null }
        executor.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}