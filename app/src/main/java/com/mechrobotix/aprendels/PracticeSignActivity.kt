package com.mechrobotix.aprendels

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.widget.*
import android.app.AlertDialog
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
import java.util.concurrent.Executors

open class PracticeSignActivity : ComponentActivity() {
    protected open val customImagePractice=false
    private lateinit var binding: ActivityPracticeSignBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var handLandmarker: HandLandmarker? = null // Solo se usa en cameraExecutor.
    private var provider: ProcessCameraProvider? = null
    private var analyzer: ImageAnalysis? = null
    @Volatile private var closing = false
    @Volatile private var generation = 0
    @Volatile private var session = 0
    @Volatile private var active = false
    @Volatile private var ready = false
    private var completed = false
    private var holdingSince = 0L
    private var previousFrame = 0L
    private var lessonIndex = 0
    private var reviewing = false
    private var resizing = false
    private var guideMirrored = false
    private val guidePreferences by lazy { getSharedPreferences(if(customImagePractice) "image_sign_guides_v1" else "guide_transform_v3", MODE_PRIVATE) }
    private val legacySizePreferences by lazy { getSharedPreferences("guide_size_v2", MODE_PRIVATE) }

    @Volatile private var motionMode=false
    private var motionDetector:HandLandmarker?=null // cameraExecutor only
    private var motionEngine:LetterMotionEngine?=null
    private var motionReference:MotionReferenceStore.Reference?=null
    private var motionUri:android.net.Uri?=null
    private var motionLetter=""
    private var motionPlayback=0
    private val motionFlow=MotionLessonFlow()
    private var motionTolerance=JPracticeEngine.Tolerance.FLEXIBLE
    private var lastMotionFrame=0L
    private var motionWidth=0
    private var motionHeight=0
    private val motionClipEnd=object:Runnable {
        override fun run() {
            if(closing || !active || !motionFlow.isDemonstrating) return
            val end=motionReference?.frames?.lastOrNull()?.timeMs ?: 0
            if(end>0 && binding.motionDemo.isPlaying && binding.motionDemo.currentPosition>=end) finishMotionDemo()
            else binding.root.postDelayed(this,50)
        }
    }
    private val motionWatchdog=object:Runnable {
        override fun run() {
            if(closing || !active) return
            if(motionMode && motionFlow.isPracticing && !reviewing && SystemClock.elapsedRealtime()-lastMotionFrame>500) {
                motionEngine?.missing(SystemClock.elapsedRealtime(),"No hay seguimiento reciente")
                binding.motionOverlayView.clear();renderMotion()
            }
            binding.root.postDelayed(this,200)
        }
    }

    private data class Lesson(val letter:String,val image:Int,val id:String=letter,val custom:ImageSignStore.Sign?=null)
    // Las letras en movimiento usan su video y evaluador de secuencias.
    private var lessons = listOf(
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

    private fun isDynamic(lesson:Lesson)=!customImagePractice && MotionReferenceStore.isDynamic(lesson.letter)

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
        if (!completed && ready && !reviewing && !resizing && !isDynamic(lessons[lessonIndex])) {
            holdingSince = 0L; previousFrame = 0L
            binding.overlayCanvasView.resetAttempt()
            binding.progressBarMatch.progress = 0
            binding.tvMatchStatus.text = "Coloca una mano sobre la silueta"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(customImagePractice) {
            setContentView(R.layout.activity_image_sign_practice)
            binding=ActivityPracticeSignBinding.bind(findViewById(R.id.imagePracticeContent))
            lessons=ImageSignStore.ids(this).mapNotNull { id -> runCatching { ImageSignStore.load(this,id) }.getOrNull() }
                .sortedBy { it.name.lowercase() }.map { Lesson(it.name,0,it.id,it) }
            if(lessons.isEmpty()) { Toast.makeText(this,"Guarda primero una imagen revisada",Toast.LENGTH_LONG).show();finish();return }
        } else {
            binding=ActivityPracticeSignBinding.inflate(layoutInflater)
            setContentView(binding.root)
        }
        if(lessons.isEmpty()) { finish();return }
        AppUi.styleButtons(binding.root)
        binding.motionOverlayView.scaleX=1.30f;binding.motionOverlayView.scaleY=1.30f
        binding.referenceTap.setOnClickListener {
            val lesson=lessons[lessonIndex]
            if(isDynamic(lesson)) { playMotionDemo();return@setOnClickListener }
            startActivity(android.content.Intent(this,ReferenceViewerActivity::class.java)
                .putExtra("letter",lesson.letter).putExtra("image",lesson.image).putExtra("sign_id",lesson.custom?.id)
                .putExtra("video",isDynamic(lesson) && MotionReferenceStore.uri(this,lesson.letter)!=null)
                .putExtra("mirror",guideMirrored))
        }
        binding.videoSignReference.setOnPreparedListener { player ->
            player.setVolume(0f,0f); player.isLooping=false
            binding.videoSignReference.seekTo(motionReference?.frames?.firstOrNull()?.timeMs?.toInt() ?: 1)
        }
        binding.videoSignReference.setOnErrorListener { _,_,_ -> true }
        binding.viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // Conserva el aumento de tu pantalla en AMBAS capas.
        binding.viewFinder.scaleX = 1.30f; binding.viewFinder.scaleY = 1.30f
        binding.overlayCanvasView.scaleX = 1.30f; binding.overlayCanvasView.scaleY = 1.30f
        binding.btnBackFromPractice.setOnClickListener { finish() }
        binding.btnNextSign.setOnClickListener {
            if (!completed) return@setOnClickListener
            if (lessonIndex < lessons.lastIndex) {
                lessonIndex++; loadLesson()
            } else {
                finish()
            }
        }
        val selectedLetter = savedInstanceState?.getString("practice_letter")
            ?: intent.getStringExtra(if(customImagePractice) "sign_id" else "letter")
            ?: guidePreferences.getString("last_practice_letter", "A")
        lessonIndex = lessons.indexOfFirst { it.id == selectedLetter }.coerceAtLeast(0)
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
        stopMotionLesson()
        resizing=false;binding.guideAdjustmentPanel.visibility=View.GONE
        val token = ++generation
        session++ // Descarta resultados pendientes de la letra anterior.
        guidePreferences.edit().putString("last_practice_letter", lesson.id).apply()
        binding.btnChooseLetter.text = if(customImagePractice) "Elegir seña: ${lesson.letter}" else "Elegir letra: ${lesson.letter}"
        restoreGuideControls()
        ready = false; completed = false; holdingSince = 0L; previousFrame = 0L
        binding.root.removeCallbacks(frameTimeout)
        binding.overlayCanvasView.clearReference()
        val dynamic=isDynamic(lesson)
        binding.overlayCanvasView.visibility=if(dynamic) View.GONE else View.VISIBLE
        binding.btnToggleGuideControls.isEnabled=true
        binding.btnToggleGuideControls.text="Ajustar guía"
        binding.tvMotionStage.visibility=if(dynamic) View.VISIBLE else View.GONE
        binding.videoSignReference.stopPlayback()
        binding.videoSignReference.visibility=View.GONE
        binding.ivSignReference.visibility=View.VISIBLE
        binding.referenceTap.text="Ampliar"
        if(lesson.custom==null) binding.ivSignReference.setImageResource(lesson.image)
        else binding.ivSignReference.setImageURI(android.net.Uri.fromFile(lesson.custom.image))
        binding.ivSignReference.contentDescription = "Referencia de la letra ${lesson.letter}"
        binding.tvInstruction.text = "Haz la seña: ${lesson.letter}"
        binding.tvMatchStatus.text = "Preparando la guía…"
        binding.progressBarMatch.progress = 0
        binding.btnNextSign.isEnabled = false
        binding.btnNextSign.text = if (lessonIndex == lessons.lastIndex) "Finalizar" else "Siguiente"
        if(dynamic) {
            loadMotionLesson(lesson.letter,token)
            return
        }
        cameraExecutor.execute {
            try {
                val template=lesson.custom?.template ?: run {
                    val bitmap=decodeReference(lesson.image)
                    try { prepareTemplate(lesson.letter,bitmap) } finally { bitmap.recycle() }
                }
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


    private fun stopMotionLesson() {
        motionPlayback++;ready=false;motionMode=false;motionFlow.loading();motionEngine=null;motionReference=null;motionUri=null;motionLetter=""
        binding.root.removeCallbacks(motionClipEnd);binding.motionDemo.stopPlayback()
        binding.motionDemoArea.visibility=View.GONE;binding.motionOverlayView.visibility=View.GONE
        binding.motionOverlayView.engine=null;binding.motionOverlayView.clear()
        cameraExecutor.execute { motionDetector?.close();motionDetector=null }
    }

    private fun loadMotionLesson(letter:String,token:Int) {
        motionMode=true;motionLetter=letter;binding.referenceTap.text="↻ Ver guía"
        binding.ivSignReference.visibility=View.GONE;binding.videoSignReference.visibility=View.VISIBLE
        binding.tvMotionStage.text="Preparando demostración de $letter…"
        binding.tvMatchStatus.text="Cargando la referencia de movimiento…"
        cameraExecutor.execute {
            try {
                // Reusa una referencia guardada; si no existe, la crea desde el video incluido.
                val saved=runCatching { MotionReferenceStore.load(this,letter) }.getOrNull()
                if(saved==null) runOnUiThread { if(!closing && token==generation)
                    binding.tvMatchStatus.text="Analizando el video de $letter por primera vez. La referencia se guardará automáticamente…" }
                val stored=saved ?: BundledMotionMapper.prepare(this,letter)
                val engine=LetterMotionEngine(letter,MotionReferenceStore.validate(stored.frames,letter))
                val uri=android.net.Uri.fromFile(stored.video)
                if(engine!=null) motionDetector=HandLandmarker.createFromOptions(this,HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumHands(2).build())
                runOnUiThread { if(!closing && token==generation) {
                    motionReference=stored;motionEngine=engine;motionUri=uri
                    val prefs=getSharedPreferences(if(letter=="J") "j_practice_tolerance" else "motion_tolerance_$letter",MODE_PRIVATE)
                    val key=stored.id ?: stored.source
                    val previous=prefs.getString("reference_key",null)
                    val same=previous==key || (stored.id==null && previous==null && prefs.getString("source",stored.source)==stored.source)
                    val level=if(same) prefs.getInt("level",stored.tolerance) else stored.tolerance
                    motionTolerance=JPracticeEngine.Tolerance.values().getOrElse(level) { JPracticeEngine.Tolerance.FLEXIBLE }
                    engine.setTolerance(motionTolerance)
                    binding.motionOverlayView.engine=engine
                    binding.videoSignReference.setVideoURI(uri)
                    playMotionDemo()
                } }
            } catch(e:Exception) {
                runOnUiThread { if(!closing && token==generation) {
                    motionFlow.fail();ready=false
                    binding.tvMotionStage.text="Referencia pendiente"
                    binding.tvMatchStatus.text="No se pudo preparar el video de $letter: ${e.message}. Verifica que el video esté integrado y muestre la mano completa."
                } }
            }
        }
    }

    private fun playMotionDemo() {
        val uri=motionUri ?: return
        if(!motionMode || reviewing || closing) return
        session++;ready=false;completed=false;motionFlow.demonstrate(motionEngine!=null)
        motionEngine?.setTolerance(motionTolerance);motionEngine?.setMirrored(guideMirrored)
        binding.progressBarMatch.progress=0;binding.btnNextSign.isEnabled=false
        binding.motionOverlayView.clear();binding.motionOverlayView.visibility=View.GONE
        binding.motionDemoArea.visibility=View.VISIBLE
        binding.motionDemo.scaleX=if(guideMirrored) -1f else 1f
        binding.tvMotionStage.text="Demostración · $motionLetter"
        binding.tvMotionStage.setTextColor(Color.parseColor("#0B2545"))
        binding.tvMatchStatus.text="Observa la guía. Al terminar, haz la seña frente a la cámara."
        val token=++motionPlayback
        binding.motionDemo.setOnPreparedListener { player ->
            if(token==motionPlayback && motionFlow.isDemonstrating && !closing) {
                player.setVolume(0f,0f);player.isLooping=false
                val start=motionReference?.frames?.firstOrNull()?.timeMs ?: 0L
                player.setOnSeekCompleteListener {
                    if(token==motionPlayback && active && !reviewing && motionFlow.isDemonstrating) binding.motionDemo.start()
                }
                if(android.os.Build.VERSION.SDK_INT>=26) player.seekTo(start,android.media.MediaPlayer.SEEK_CLOSEST)
                else binding.motionDemo.seekTo(start.toInt())
            }
        }
        binding.motionDemo.setOnCompletionListener { if(token==motionPlayback && active && !reviewing) finishMotionDemo() }
        binding.motionDemo.setOnErrorListener { _,_,_ ->
            if(token==motionPlayback) { motionFlow.fail();ready=false;binding.root.removeCallbacks(motionClipEnd)
                binding.tvMatchStatus.text="No se pudo reproducir la guía. Revisa el video de $motionLetter antes de practicar." }
            true
        }
        binding.root.removeCallbacks(motionClipEnd);binding.motionDemo.setVideoURI(uri)
        if(active) binding.root.post(motionClipEnd)
    }

    private fun finishMotionDemo() {
        if(!active || reviewing || !motionFlow.demoFinished()) return
        binding.root.removeCallbacks(motionClipEnd);binding.motionDemo.pause()
        if(!motionFlow.isPracticing) {
            binding.tvMotionStage.text="Referencia no disponible"
            binding.tvMatchStatus.text="No se pudo preparar la referencia de $motionLetter. Cierra e inténtalo de nuevo."
            return
        }
        session++;motionEngine?.reset();motionWidth=0;motionHeight=0
        binding.motionDemoArea.visibility=View.GONE;binding.motionOverlayView.visibility=View.VISIBLE
        binding.motionOverlayView.clear();lastMotionFrame=SystemClock.elapsedRealtime();ready=true
        renderMotion()
    }

    private fun processMotionSample(sample:JPracticeEngine.Sample?,w:Int,h:Int,time:Long) {
        if(!motionFlow.isPracticing || reviewing || !ready) return
        val engine=motionEngine ?: return
        val now=SystemClock.elapsedRealtime();lastMotionFrame=now
        if(now-time>500) { engine.missing(now,"La cámara procesa demasiado lento");binding.motionOverlayView.clear();renderMotion();return }
        if(w!=motionWidth || h!=motionHeight) { engine.reset();motionWidth=w;motionHeight=h }
        if(sample==null) engine.missing(time,"Muestra una sola mano completa")
        else if(sample.palm/w<.055) engine.missing(time,"Acerca un poco la mano")
        else engine.update(time,sample)
        binding.motionOverlayView.update(sample,w,h);renderMotion()
    }

    private fun renderMotion() {
        val engine=motionEngine ?: return
        binding.tvMotionStage.text=when(engine.stage) {
            JPracticeEngine.Stage.START,JPracticeEngine.Stage.HOLD->"1 · Inicio   →   2 · Recorrido   →   3 · Final"
            JPracticeEngine.Stage.MOVE->"✓ Inicio   →   2 · Recorrido   →   3 · Final"
            JPracticeEngine.Stage.END->"✓ Inicio   →   ✓ Recorrido   →   3 · Mantén el final"
            JPracticeEngine.Stage.SUCCESS->"✓ Inicio   →   ✓ Recorrido   →   ✓ Final"
        }
        binding.tvMotionStage.setTextColor(if(engine.stage==JPracticeEngine.Stage.SUCCESS) Color.parseColor("#008577") else Color.parseColor("#0B2545"))
        binding.tvMatchStatus.text=engine.message+"\nTolerancia: ${motionTolerance.label}"
        binding.progressBarMatch.progress=engine.progress
        if(engine.stage==JPracticeEngine.Stage.SUCCESS && motionFlow.complete()) {
            completed=true;ready=false;binding.btnNextSign.isEnabled=true
            binding.tvMatchStatus.text="¡Letra $motionLetter completada!"
        }
        binding.motionOverlayView.invalidate()
    }

    private fun showMotionOptions() {
        AlertDialog.Builder(this).setTitle("Movimiento de ${lessons[lessonIndex].letter}")
            .setItems(arrayOf("Volver a ver la guía","Tolerancia: ${motionTolerance.label}")) { _,which ->
                if(which==0) playMotionDemo() else {
                    reviewing=true;session++;binding.motionDemo.pause();motionEngine?.reset()
                    AlertDialog.Builder(this).setTitle("Tolerancia de práctica")
                        .setSingleChoiceItems(arrayOf("Precisa","Normal","Flexible"),motionTolerance.ordinal) { dialog,index ->
                            motionTolerance=JPracticeEngine.Tolerance.values()[index]
                            motionReference?.let { ref ->
                                getSharedPreferences(if(motionLetter=="J") "j_practice_tolerance" else "motion_tolerance_$motionLetter",MODE_PRIVATE)
                                    .edit().putInt("level",index).putString("reference_key",ref.id ?: ref.source).apply()
                            }
                            dialog.dismiss()
                        }.setNegativeButton("Cancelar",null)
                        .setOnDismissListener { reviewing=false;resetValidation() }.show()
                }
            }.setNegativeButton("Cerrar",null).show()
    }

    private fun decodeReference(id: Int): Bitmap = checkNotNull(BitmapFactory.decodeResource(
        resources,id,BitmapFactory.Options().apply { inScaled=false }))

    private class ReferenceDetectionException(message: String) : IllegalStateException(message)

    // Se conservan las dimensiones y coordenadas normalizadas de la foto original.
    // Inferencia y extracción de contorno trabajan con una copia de hasta 1024 px.
    private fun prepareTemplate(letter: String, photo: Bitmap): ReferenceTemplate {
        ReferenceTemplate.load(this,letter,photo.width,photo.height)?.let { return it }
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
                "MediaPipe devolvió $count manos; se necesita una. La imagen de esta letra necesita una referencia válida.")
            val points=result.landmarks()[0]
            if(points.size!=21) throw ReferenceDetectionException(
                "MediaPipe devolvió ${points.size} puntos; se necesitan 21. La imagen de esta letra necesita una referencia válida.")
            val automatic=try { ReferenceTemplate.automatic(work,points) }
            catch(e:IllegalArgumentException) { throw ReferenceDetectionException("No se pudo extraer el contorno. La imagen de esta letra necesita una referencia válida.") }
            catch(e:IllegalStateException) { throw ReferenceDetectionException("No se pudo separar el contorno. La imagen de esta letra necesita una referencia válida.") }
            return automatic.copy(width=photo.width,height=photo.height)
        } finally { if(work !== photo) work.recycle() }
    }

    private fun resetValidation() {
        session++;completed=false;holdingSince=0L;previousFrame=0L
        binding.root.removeCallbacks(frameTimeout)
        binding.overlayCanvasView.resetAttempt()
        binding.progressBarMatch.progress=0;binding.btnNextSign.isEnabled=false
        if(isDynamic(lessons[lessonIndex])) {
            motionEngine?.reset();binding.motionOverlayView.clear()
            if(reviewing) { binding.motionDemo.pause();return }
            if(motionLetter==lessons[lessonIndex].letter) playMotionDemo()
        } else binding.tvMatchStatus.text="Coloca una mano sobre el contorno"
    }

    // Un juego de ajustes por letra; editar A no sobrescribe B.
    private fun guideKey(name: String): String = "${lessons[lessonIndex].id}_$name"

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
        binding.btnMirrorGuide.text=if(guideMirrored) "Modo espejo: sí" else "Modo espejo: no"
        binding.ivSignReference.scaleX=if(guideMirrored) -1f else 1f
        binding.videoSignReference.scaleX=if(guideMirrored) -1f else 1f
        binding.motionDemo.scaleX=if(guideMirrored) -1f else 1f
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
            if(isDynamic(lessons[lessonIndex])) { showMotionOptions();return@setOnClickListener }
            val open=binding.guideAdjustmentPanel.visibility!=View.VISIBLE
            binding.guideAdjustmentPanel.visibility=if(open) View.VISIBLE else View.GONE
            binding.btnToggleGuideControls.text=if(open) "Cerrar ajustes" else "Ajustar guía"
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
    }

    private fun choosePracticeLetter() {
        if(reviewing) return
        reviewing=true
        resetValidation()
        AlertDialog.Builder(this).setTitle(if(customImagePractice) "Elige una seña para practicar" else "Elige una letra para practicar")
            .setSingleChoiceItems(lessons.map { it.letter }.toTypedArray(),lessonIndex) { dialog,index ->
                lessonIndex=index
                resizing=false
                binding.guideAdjustmentPanel.visibility=View.GONE
                binding.btnToggleGuideControls.text="Ajustar guía"
                dialog.dismiss()
                loadLesson()
            }
            .setNegativeButton("Cancelar",null)
            .setOnDismissListener { reviewing=false; resetValidation() }
            .show()
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
            if (closing || !active || !ready) return
            val dynamic=motionMode
            val model = (if(dynamic) motionDetector else handLandmarker) ?: return
            val bitmap = imageProxyToBitmap(proxy)
            val w = bitmap.width; val h = bitmap.height
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = try { if(dynamic) model.detectForVideo(mpImage,time) else model.detect(mpImage) } finally { mpImage.close() }
            val sample=if(dynamic) HandLandmarkSamples.sample(result,w,h,true) else null
            runOnUiThread {
                if (!closing && active && token == generation && frameSession == session) {
                    if(dynamic) { processMotionSample(sample,w,h,time);return@runOnUiThread }
                    binding.overlayCanvasView.setResults(result, w, h)
                    if (ready && !completed && !reviewing && !resizing && !isDynamic(lessons[lessonIndex])) {
                        val match = binding.overlayCanvasView.evaluate()
                        // Una pausa o un frame incorrecto rompe la continuidad.
                        if (previousFrame == 0L || time-previousFrame > 350L) holdingSince = 0L
                        previousFrame = time
                        if (match.correct) {
                            if (holdingSince == 0L) holdingSince = time
                            if (time-holdingSince >= 900L) {
                                completed = true
                                binding.overlayCanvasView.setValidated()
                                binding.tvMatchStatus.text = "¡Postura correcta!"
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
        if(lessons.isNotEmpty()) outState.putString("practice_letter",lessons[lessonIndex].id)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume();active=true
        if(motionMode && motionUri!=null && !completed) playMotionDemo()
        binding.root.removeCallbacks(motionWatchdog);binding.root.post(motionWatchdog)
    }
    override fun onPause() {
        motionPlayback++
        binding.root.removeCallbacks(motionWatchdog);binding.root.removeCallbacks(motionClipEnd)
        binding.motionDemo.pause();motionEngine?.reset();binding.motionOverlayView.clear()
        binding.videoSignReference.pause()
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
        binding.videoSignReference.stopPlayback();binding.motionDemo.stopPlayback()
        binding.root.removeCallbacks(motionWatchdog);binding.root.removeCallbacks(motionClipEnd)
        binding.root.removeCallbacks(frameTimeout)
        analyzer?.clearAnalyzer()
        provider?.unbindAll()
        // Cerrar el modelo después de terminar cualquier inferencia pendiente.
        cameraExecutor.execute { handLandmarker?.close(); handLandmarker = null;motionDetector?.close();motionDetector=null }
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
