package com.mechrobotix.aprendels

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.widget.*
import android.app.AlertDialog
import android.view.ViewGroup
import android.view.View
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
import com.mechrobotix.aprendels.databinding.ActivityPracticeSignBinding
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import java.util.concurrent.Executors

class PracticeSignActivity : ComponentActivity() {
    private lateinit var binding: ActivityPracticeSignBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var handLandmarker: HandLandmarker? = null // Solo se usa en cameraExecutor.
    private var provider: ProcessCameraProvider? = null
    private var analyzer: ImageAnalysis? = null
    @Volatile private var closing = false
    @Volatile private var generation = 0
    @Volatile private var session = 0
    private var active = false
    private var ready = false
    private var completed = false
    private var holdingSince = 0L
    private var previousFrame = 0L
    private var lessonIndex = 0
    private var reviewing = false
    private var resizing = false
    private var guideMirrored = false
    private val guidePreferences by lazy { getSharedPreferences("guide_transform_v3", MODE_PRIVATE) }
    private val legacySizePreferences by lazy { getSharedPreferences("guide_size_v2", MODE_PRIVATE) }

    private data class Lesson(val letter: String, val image: Int)
    // Catálogo único para practicar y revisar las 27 referencias.
    // El evaluador actual compara posturas 2D; no comprueba trayectorias de movimiento.
    private val lessons = listOf(
        Lesson("A", R.drawable.sign_a),
        Lesson("B", R.drawable.sign_b),
        Lesson("C", R.drawable.sign_c),
        Lesson("D", R.drawable.sign_d),
        Lesson("E", R.drawable.sign_e),
        Lesson("F", R.drawable.sign_f),
        Lesson("G", R.drawable.sign_g),
        Lesson("H", R.drawable.sign_h),
        Lesson("I", R.drawable.sign_i),
        Lesson("J", R.drawable.sign_j),
        Lesson("K", R.drawable.sign_k),
        Lesson("L", R.drawable.sign_l),
        Lesson("M", R.drawable.sign_m),
        Lesson("N", R.drawable.sign_n),
        Lesson("Ñ", R.drawable.sign_enye),
        Lesson("O", R.drawable.sign_o),
        Lesson("P", R.drawable.sign_p),
        Lesson("Q", R.drawable.sign_q),
        Lesson("R", R.drawable.sign_r),
        Lesson("S", R.drawable.sign_s),
        Lesson("T", R.drawable.sign_t),
        Lesson("U", R.drawable.sign_u),
        Lesson("V", R.drawable.sign_v),
        Lesson("W", R.drawable.sign_w),
        Lesson("X", R.drawable.sign_x),
        Lesson("Y", R.drawable.sign_y),
        Lesson("Z", R.drawable.sign_z)
    )

    private val jPracticeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!closing && lessons[lessonIndex].letter == "J") {
            session++; ready=false; holdingSince=0L; previousFrame=0L
            binding.root.removeCallbacks(frameTimeout)
            completed=result.resultCode==android.app.Activity.RESULT_OK
            binding.progressBarMatch.progress=if(completed) 100 else 0
            binding.btnNextSign.isEnabled=true
            binding.btnNextSign.text=if(completed) "Siguiente" else "Practicar J"
            binding.tvMatchStatus.text=if(completed) "J completada según la referencia revisada"
                else "La J requiere movimiento. Pulsa Practicar J"
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Se requiere permiso de cámara.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Evita dejar progreso activo si dejan de llegar resultados.
    private val frameTimeout = Runnable {
        if (!completed && ready && !reviewing && !resizing && lessons[lessonIndex].letter!="J") {
            holdingSince = 0L; previousFrame = 0L
            binding.overlayCanvasView.resetAttempt()
            binding.progressBarMatch.progress = 0
            binding.tvMatchStatus.text = "Coloca una mano sobre la silueta"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPracticeSignBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Pantalla experimental independiente; conserva los ajustes del abecedario.
        binding.btnPrepareJ.setOnClickListener {
            jPracticeLauncher.launch(android.content.Intent(this,JReferenceEditorActivity::class.java)
                .putExtra("mirror",guideMirrored))
        }
        binding.btnMotionPractice.setOnClickListener {
            startActivity(android.content.Intent(this, MotionPracticeActivity::class.java))
        }
        binding.viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // Conserva el aumento de tu pantalla en AMBAS capas.
        binding.viewFinder.scaleX = 1.30f; binding.viewFinder.scaleY = 1.30f
        binding.overlayCanvasView.scaleX = 1.30f; binding.overlayCanvasView.scaleY = 1.30f
        binding.btnBackFromPractice.setOnClickListener { finish() }
        binding.btnNextSign.setOnClickListener {
            if (lessons[lessonIndex].letter=="J" && !completed) {
                jPracticeLauncher.launch(android.content.Intent(this,JReferenceEditorActivity::class.java)
                    .putExtra("mirror",guideMirrored))
                return@setOnClickListener
            }
            if (!completed) return@setOnClickListener
            if (lessonIndex < lessons.lastIndex) {
                lessonIndex++; loadLesson()
            } else finish()
        }
        val selectedLetter = savedInstanceState?.getString("practice_letter")
            ?: guidePreferences.getString("last_practice_letter", "A")
        lessonIndex = lessons.indexOfFirst { it.letter == selectedLetter }.coerceAtLeast(0)
        setupGuideControls()
        cameraExecutor.execute {
            try {
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder()
                        .setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.IMAGE).setNumHands(2).build()
                handLandmarker = HandLandmarker.createFromOptions(this, options)
            } catch (e: Exception) {
                runOnUiThread {
                    if (!closing) binding.tvMatchStatus.text = "No se pudo cargar el modelo: ${e.message}"
                }
            }
        }
        loadLesson() // Se encola después de crear el modelo.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun loadLesson() {
        val lesson = lessons[lessonIndex]
        val token = ++generation
        session++ // Descarta resultados pendientes de la letra anterior.
        guidePreferences.edit().putString("last_practice_letter", lesson.letter).apply()
        binding.btnChooseLetter.text = "Elegir letra: ${lesson.letter}"
        restoreGuideControls()
        ready = false; completed = false; holdingSince = 0L; previousFrame = 0L
        binding.root.removeCallbacks(frameTimeout)
        binding.overlayCanvasView.clearReference()
        binding.ivSignReference.setImageResource(lesson.image)
        binding.ivSignReference.contentDescription = "Referencia de la letra ${lesson.letter}"
        binding.tvInstruction.text = "Haz la seña: ${lesson.letter}"
        binding.tvMatchStatus.text = "Preparando la guía…"
        binding.progressBarMatch.progress = 0
        binding.btnNextSign.isEnabled = false
        binding.btnNextSign.text = if (lessonIndex == lessons.lastIndex) "Finalizar" else "Siguiente"
        if (lesson.letter=="J") {
            binding.btnNextSign.text="Practicar J"; binding.btnNextSign.isEnabled=true
            binding.tvMatchStatus.text="Prepara la referencia y practica el movimiento de J"
            return
        }
        cameraExecutor.execute {
            try {
                val bitmap = decodeReference(lesson.image)
                val template = try { prepareTemplate(lesson.letter, bitmap) } finally { bitmap.recycle() }
                runOnUiThread {
                    if (!closing && generation == token) {
                        binding.overlayCanvasView.setReference(template)
                        ready = true
                        binding.tvMatchStatus.text = "Coloca una mano sobre la silueta"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!closing && generation == token) {
                        ready = false
                        binding.tvMatchStatus.text = "No se pudo preparar la letra: ${e.message}"
                    }
                }
            }
        }
    }


    private fun decodeReference(id: Int): Bitmap = checkNotNull(BitmapFactory.decodeResource(
        resources,id,BitmapFactory.Options().apply { inScaled=false }))

    private class ReferenceDetectionException(message: String) : IllegalStateException(message)

    // Se conservan las dimensiones y coordenadas normalizadas de la foto original.
    // Inferencia y extracción de contorno trabajan con una copia de hasta 1024 px.
    private fun prepareTemplate(letter: String, photo: Bitmap, useSaved: Boolean = true): ReferenceTemplate {
        if(useSaved) ReferenceTemplate.load(this,letter,photo.width,photo.height)?.let { return it }
        val model=checkNotNull(handLandmarker) { "Modelo no disponible" }
        val factor=minOf(1f,1024f/maxOf(photo.width,photo.height))
        val work=if(factor<1f) Bitmap.createScaledBitmap(photo,
            (photo.width*factor).toInt().coerceAtLeast(1),
            (photo.height*factor).toInt().coerceAtLeast(1),true)
        else checkNotNull(photo.copy(Bitmap.Config.ARGB_8888,false))
        try {
            // MediaPipe puede liberar su Bitmap al cerrar: usar otra copia para inferencia.
            val image=BitmapImageBuilder(checkNotNull(work.copy(Bitmap.Config.ARGB_8888,false))).build()
            val result=try { model.detect(image) } finally { image.close() }
            val count=result.landmarks().size
            if(count!=1) throw ReferenceDetectionException(
                "MediaPipe devolvió $count manos; se necesita una. Abre Revisar imágenes para crear la referencia manualmente.")
            val points=result.landmarks()[0]
            if(points.size!=21) throw ReferenceDetectionException(
                "MediaPipe devolvió ${points.size} puntos; se necesitan 21. Abre Revisar imágenes.")
            val automatic=try { ReferenceTemplate.automatic(work,points) }
            catch(e:IllegalArgumentException) { throw ReferenceDetectionException("No se pudo extraer el contorno. Abre Revisar imágenes para trazarlo.") }
            catch(e:IllegalStateException) { throw ReferenceDetectionException("No se pudo separar el contorno. Abre Revisar imágenes para trazarlo.") }
            return automatic.copy(width=photo.width,height=photo.height)
        } finally { if(work !== photo) work.recycle() }
    }

    private fun resetValidation() {
        session++; completed=false; holdingSince=0L; previousFrame=0L
        binding.root.removeCallbacks(frameTimeout)
        binding.overlayCanvasView.resetAttempt()
        binding.progressBarMatch.progress=0
        binding.btnNextSign.isEnabled=false
        binding.tvMatchStatus.text="Coloca una mano sobre el contorno"
        if(lessons[lessonIndex].letter=="J") {
            binding.btnNextSign.isEnabled=true; binding.btnNextSign.text="Practicar J"
            binding.tvMatchStatus.text="La J se valida en la práctica de movimiento"
        }
    }

    // Un juego de ajustes por letra; editar A no sobrescribe B.
    private fun guideKey(name: String): String = "${lessons[lessonIndex].letter}_$name"

    private fun restoreGuideControls() {
        val oldWidth=legacySizePreferences.getInt("width_percent",28).coerceIn(15,60)
        binding.seekGuideSize.progress=guidePreferences.getInt(guideKey("width"),oldWidth).coerceIn(15,60)-15
        binding.seekGuideX.progress=guidePreferences.getInt(guideKey("x"),28).coerceIn(0,100)
        binding.seekGuideY.progress=guidePreferences.getInt(guideKey("y"),38).coerceIn(0,100)
        binding.seekGuideRotation.progress=guidePreferences.getInt(guideKey("rotation"),0).coerceIn(-180,180)+180
        guideMirrored=guidePreferences.getBoolean(guideKey("mirrored"),false)
        // Los callbacks con fromUser=false no sobrescriben ajustes durante esta carga.
        applyGuideControls(save=false)
    }

    private fun applyGuideControls(save: Boolean) {
        val width=binding.seekGuideSize.progress+15
        val x=binding.seekGuideX.progress
        val y=binding.seekGuideY.progress
        val rotation=binding.seekGuideRotation.progress-180
        binding.tvGuideSize.text="Ancho: $width%"
        binding.tvGuideX.text="Horizontal: $x%"
        binding.tvGuideY.text="Vertical: $y%"
        binding.tvGuideRotation.text="Rotación: $rotation°"
        binding.btnMirrorGuide.text=if(guideMirrored) "Espejo: activado" else "Espejo: desactivado"
        binding.ivSignReference.scaleX=if(guideMirrored) -1f else 1f
        binding.overlayCanvasView.setGuideTransform(width,x/100f,y/100f,rotation.toFloat(),guideMirrored)
        if(save) {
            // Guarda la transformación completa de esta letra, incluido el espejo.
            guidePreferences.edit()
                .putInt(guideKey("width"),width)
                .putInt(guideKey("x"),x)
                .putInt(guideKey("y"),y)
                .putInt(guideKey("rotation"),rotation)
                .putBoolean(guideKey("mirrored"),guideMirrored)
                .apply()
        }
        binding.tvGuideSaved.text=if(save) "Ajustes guardados para ${lessons[lessonIndex].letter}"
        else "Letra ${lessons[lessonIndex].letter} · guardado automático"
    }

    private fun setupGuideControls() {
        binding.btnChooseLetter.setOnClickListener { choosePracticeLetter() }
        binding.btnMirrorGuide.setOnClickListener {
            guideMirrored=!guideMirrored
            applyGuideControls(save=true)
            resetValidation()
            if(resizing) binding.tvMatchStatus.text="Cierra los ajustes para practicar"
        }
        binding.seekGuideSize.max=45
        binding.seekGuideX.max=100
        binding.seekGuideY.max=100
        binding.seekGuideRotation.max=360
        val listener=object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress:Int, fromUser:Boolean) {
                if(!fromUser) return
                applyGuideControls(save=true)
                resetValidation()
                binding.tvMatchStatus.text="Ajusta la guía y pulsa Cerrar ajustes para practicar"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { resizing=true; resetValidation() }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                resizing=binding.guideAdjustmentPanel.visibility==View.VISIBLE
                resetValidation()
                if(resizing) binding.tvMatchStatus.text="Cierra los ajustes para practicar"
            }
        }
        listOf(binding.seekGuideSize,binding.seekGuideX,binding.seekGuideY,binding.seekGuideRotation)
            .forEach { it.setOnSeekBarChangeListener(listener) }
        binding.btnToggleGuideControls.setOnClickListener {
            val open=binding.guideAdjustmentPanel.visibility!=View.VISIBLE
            binding.guideAdjustmentPanel.visibility=if(open) View.VISIBLE else View.GONE
            binding.btnToggleGuideControls.text=if(open) "Cerrar ajustes" else "Ajustar guía"
            binding.btnReviewReference.isEnabled=!open
            resizing=open
            resetValidation()
            if(open) binding.tvMatchStatus.text="Mueve o gira la guía; los cambios se guardan solos"
        }
        binding.btnResetGuideControls.setOnClickListener {
            binding.seekGuideSize.progress=28-15
            binding.seekGuideX.progress=28
            binding.seekGuideY.progress=38
            binding.seekGuideRotation.progress=180
            guideMirrored=false
            applyGuideControls(save=true)
            resetValidation()
            binding.tvMatchStatus.text="Valores iniciales restaurados. Cierra los ajustes para practicar"
        }
        binding.btnReviewReference.setOnClickListener { chooseReference() }
    }

    private fun choosePracticeLetter() {
        if(reviewing) return
        reviewing=true
        resetValidation()
        AlertDialog.Builder(this).setTitle("Elige una letra para practicar")
            .setSingleChoiceItems(lessons.map { it.letter }.toTypedArray(),lessonIndex) { dialog,index ->
                lessonIndex=index
                resizing=false
                binding.guideAdjustmentPanel.visibility=View.GONE
                binding.btnToggleGuideControls.text="Ajustar guía"
                binding.btnReviewReference.isEnabled=true
                dialog.dismiss()
                loadLesson()
            }
            .setNegativeButton("Cancelar",null)
            .setOnDismissListener { reviewing=false; resetValidation() }
            .show()
    }

    private fun chooseReference() {
        if(reviewing) return
        reviewing=true; resetValidation()
        val letters=lessons.map { it.letter }
        var selected=false
        AlertDialog.Builder(this).setTitle("Revisar imagen")
            .setItems(letters.toTypedArray()) { _,index ->
                selected=true
                val lesson=lessons[index]
                openReferenceEditor(lesson.letter,lesson.image)
            }.setOnDismissListener { if(!selected) { reviewing=false; resetValidation() } }.show()
    }

    private fun openReferenceEditor(letter: String, resource: Int, useSaved: Boolean = true) {
        binding.tvMatchStatus.text="Preparando revisión de $letter…"
        cameraExecutor.execute {
            var photo: Bitmap?=null
            try {
                val decoded=decodeReference(resource); photo=decoded
                var manualReason: String?=null
                val template=try { prepareTemplate(letter,decoded,useSaved) }
                catch(e:ReferenceDetectionException) { manualReason=e.message; null }
                runOnUiThread {
                    if(closing) { decoded.recycle(); return@runOnUiThread }
                    showReferenceEditor(letter,resource,decoded,template)
                    manualReason?.let { Toast.makeText(this,it,Toast.LENGTH_LONG).show() }
                }
            } catch(e:Exception) {
                photo?.recycle()
                runOnUiThread {
                    if(!closing) {
                        reviewing=false; resetValidation()
                        Toast.makeText(this,"No se pudo revisar $letter: ${e.message}",Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showReferenceEditor(letter: String, resource: Int, photo: Bitmap, template: ReferenceTemplate?) {
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(12,8,12,8) }
        val hint=TextView(this).apply { text="Arrastra los puntos numerados para corregirlos."; setTextColor(Color.BLACK) }
        val editor=ReferenceEditorView(this,photo,template)
        editor.onHint={ hint.text=it }
        hint.text=editor.instructions()
        val mode=Switch(this).apply {
            text="Trazar contorno a mano"; setTextColor(Color.BLACK)
            setOnCheckedChangeListener { _,checked ->
                editor.tracing=checked
                hint.text=if(checked) "Rodea la mano en un solo trazo y vuelve al inicio. No incluyas el antebrazo."
                else editor.instructions()
            }
        }
        val undo=Button(this).apply { text="Deshacer cambios de esta revisión"; setOnClickListener { editor.resetDraft() } }
        layout.setBackgroundColor(Color.WHITE)
        layout.addView(hint); layout.addView(mode)
        layout.addView(editor,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels*.42f).toInt()))
        layout.addView(undo)
        if(template==null) {
            layout.addView(Button(this).apply {
                text="Quitar último punto"
                setOnClickListener { editor.removeLastPoint() }
            })
        }
        var reload=false
        val dialog=AlertDialog.Builder(this).setTitle("Referencia $letter")
            .setView(ScrollView(this).apply { addView(layout) }).setPositiveButton("Guardar",null).setNegativeButton("Cancelar",null)
            .setNeutralButton("Automática") { _,_ -> reload=true; openReferenceEditor(letter,resource,false) }
            .create()
        dialog.setOnDismissListener {
            // La vista puede seguir dibujándose durante la animación de cierre; GC libera la foto.
            if(!reload) { reviewing=false; resetValidation() }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val edited=try { editor.snapshot() } catch(e:IllegalArgumentException) {
                    hint.text=e.message
                    return@setOnClickListener
                }
                val palm=hypot((edited.landmarks[0].x()-edited.landmarks[9].x())*edited.width,
                    (edited.landmarks[0].y()-edited.landmarks[9].y())*edited.height)
                if(palm<5f) { hint.text="Separa correctamente muñeca (0) y base del dedo medio (9)."; return@setOnClickListener }
                val outline=edited.outline
                var area=0f
                for(i in outline.indices) {
                    val a=outline[i]; val b=outline[(i+1)%outline.size]
                    area+=a.x*b.y-b.x*a.y
                }
                if(kotlin.math.abs(area)<.005f) {
                    hint.text="El contorno es demasiado pequeño o se cruza. Vuelve a rodear la mano."
                    return@setOnClickListener
                }
                edited.save(this,letter)
                if(letter==lessons[lessonIndex].letter) {
                    binding.overlayCanvasView.setReference(edited)
                    ready=true
                }
                dialog.dismiss()
                Toast.makeText(this,"Referencia $letter guardada",Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun startCamera() {
        binding.viewFinder.post {
            if (closing) return@post
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                if (closing) return@addListener
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val rotation = binding.viewFinder.display.rotation
                    val preview = Preview.Builder().setTargetRotation(rotation)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                    preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    val analysis = ImageAnalysis.Builder().setTargetRotation(rotation)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analyzer = analysis
                    analysis.setAnalyzer(cameraExecutor) { processImageProxy(it) }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    binding.tvMatchStatus.text = "No se pudo iniciar la cámara: ${e.message}"
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun processImageProxy(proxy: ImageProxy) {
        val token = generation
        val frameSession = session
        val time = SystemClock.elapsedRealtime()
        try {
            if (closing) return
            val model = handLandmarker ?: return
            val bitmap = imageProxyToBitmap(proxy)
            val w = bitmap.width; val h = bitmap.height
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = try { model.detect(mpImage) } finally { mpImage.close() }
            runOnUiThread {
                if (!closing && active && token == generation && frameSession == session) {
                    binding.overlayCanvasView.setResults(result, w, h)
                    if (ready && !completed && !reviewing && !resizing && lessons[lessonIndex].letter!="J") {
                        val match = binding.overlayCanvasView.evaluate()
                        // Una pausa o un frame incorrecto rompe la continuidad.
                        if (previousFrame == 0L || time-previousFrame > 350L) holdingSince = 0L
                        previousFrame = time
                        if (match.correct) {
                            if (holdingSince == 0L) holdingSince = time
                            if (time-holdingSince >= 900L) {
                                completed = true
                                binding.overlayCanvasView.setValidated()
                                binding.tvMatchStatus.text = "Palabra formada exitosamente"
                                binding.progressBarMatch.progress = 100
                                binding.btnNextSign.isEnabled = true
                            } else {
                                binding.tvMatchStatus.text = "¡Coincide! Mantén la posición"
                                binding.progressBarMatch.progress = match.progress
                            }
                        } else {
                            holdingSince = 0L
                            binding.progressBarMatch.progress = match.progress
                            binding.tvMatchStatus.text = when {
                                result.landmarks().size > 1 -> "Usa una sola mano para esta letra"
                                result.landmarks().isEmpty() -> "Coloca una mano sobre la silueta"
                                match.close -> "Casi: ajusta dedos, orientación o distancia"
                                else -> "Reproduce la postura y cubre la silueta"
                            }
                        }
                        binding.root.removeCallbacks(frameTimeout)
                        if (!completed) binding.root.postDelayed(frameTimeout, 500L)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PracticeSign", "No se pudo procesar el frame", e)
        } finally { proxy.close() }
    }

    /** Respeta rowStride y pixelStride; concatenar Y+V+U no funciona en todos los equipos. */
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("practice_letter",lessons[lessonIndex].letter)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); active = true }
    override fun onPause() {
        active = false; session++; holdingSince = 0L; previousFrame = 0L
        binding.root.removeCallbacks(frameTimeout)
        if (!completed) {
            binding.overlayCanvasView.resetAttempt()
            binding.progressBarMatch.progress = 0
        }
        super.onPause()
    }
    override fun onDestroy() {
        closing = true
        binding.root.removeCallbacks(frameTimeout)
        analyzer?.clearAnalyzer()
        provider?.unbindAll()
        // Cerrar el modelo después de terminar cualquier inferencia pendiente.
        cameraExecutor.execute { handLandmarker?.close(); handLandmarker = null }
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
