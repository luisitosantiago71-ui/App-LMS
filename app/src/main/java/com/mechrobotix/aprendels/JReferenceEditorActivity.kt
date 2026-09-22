package com.mechrobotix.aprendels

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Preparación explícita: ver, mapear, revisar, recortar y guardar antes de practicar. */
class JReferenceEditorActivity:ComponentActivity() {
    private lateinit var root:LinearLayout
    private lateinit var video:VideoView
    private lateinit var frameView:JReferenceFrameView
    private lateinit var timeline:SeekBar
    private lateinit var status:TextView
    private lateinit var position:TextView
    private lateinit var trim:TextView
    private lateinit var reviewed:CheckBox
    private lateinit var mapButton:Button
    private lateinit var saveButton:Button
    private lateinit var practiceButton:Button
    private lateinit var startButton:Button
    private lateinit var endButton:Button
    private lateinit var badButton:Button
    private lateinit var cropButton:Button
    private lateinit var retryButton:Button
    private lateinit var rejectButton:Button
    private lateinit var undoButton:Button
    private lateinit var saveHint:TextView
    private val originals=mutableListOf<JReferenceStore.Frame>()
    private val executor=Executors.newSingleThreadExecutor()
    private val frames=mutableListOf<JReferenceStore.Frame>()
    private val images=mutableListOf<File>()
    private var startIndex=0
    private var endIndex=0
    private var source=""
    private var saved=false
    private var busy=false
    @Volatile private var closing=false
    private var active=false
    @Volatile private var frameRequest=0
    private lateinit var temp:File
    private val practiceResult=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK) { setResult(Activity.RESULT_OK); finish() }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        temp=File(cacheDir,"j_review_${System.nanoTime()}").apply { mkdirs() }
        buildUi()
        executor.execute {
            try {
                val hash=JReferenceStore.fingerprint(this)
                val stored=runCatching { JReferenceStore.load(this,hash) }.getOrNull()
                runOnUiThread { if(!closing) {
                    source=hash; saved=stored!=null; controls()
                    status.text=if(saved) "Hay una referencia guardada. Puedes practicar o preparar otra."
                        else "Mira el video y pulsa Mapear video. Después revisa el tramo completo."
                } }
            } catch(e:Exception) { error("No se pudo abrir el video o el modelo: ${e.message}") }
        }
    }
    private fun buildUi() {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val b=insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(b.left,b.top,b.right,b.bottom); insets
        }
        ViewCompat.requestApplyInsets(root)
        root.addView(label("Preparar referencia de J",20f))
        val area=FrameLayout(this)
        root.addView(area,LinearLayout.LayoutParams(-1,0,1f))
        video=VideoView(this)
        frameView=JReferenceFrameView(this).apply { visibility=View.GONE }
        area.addView(video,FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER))
        area.addView(frameView,FrameLayout.LayoutParams(-1,-1))
        video.setOnErrorListener { _,_,_ -> status.text="No se pudo reproducir res/raw/letra_j.mp4"; true }
        video.setOnPreparedListener { it.setVolume(0f,0f); if(active && !closing && video.visibility==View.VISIBLE) video.start() }
        // Los controles se desplazan en pantallas pequeñas, sin tapar el fotograma.
        val scroll=ScrollView(this)
        root.addView(scroll,LinearLayout.LayoutParams(-1,dp(320)))
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; scroll.addView(panel)
        val top=row(panel)
        top.addView(button("Ver video") { showVideo() },weight())
        mapButton=button("Mapear video") { extract() }; top.addView(mapButton,weight())
        position=label("Fotograma: pendiente",13f); panel.addView(position)
        timeline=SeekBar(this).apply {
            isEnabled=false
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,p:Int,user:Boolean) { if(frames.isNotEmpty()) showFrame(p) }
                override fun onStartTrackingTouch(bar:SeekBar?) {}
                override fun onStopTrackingTouch(bar:SeekBar?) {}
            })
        }; panel.addView(timeline)
        val steps=row(panel)
        steps.addView(button("Anterior") { if(frames.isNotEmpty()) { timeline.progress=(timeline.progress-1).coerceAtLeast(0); showFrame(timeline.progress) } },weight())
        steps.addView(button("Siguiente") { if(frames.isNotEmpty()) { timeline.progress=(timeline.progress+1).coerceAtMost(frames.lastIndex); showFrame(timeline.progress) } },weight())
        badButton=button("Sin lectura") {
            val candidates=frames.indices.filter { frames[it].sample==null }
            val i=candidates.firstOrNull { it>timeline.progress } ?: candidates.firstOrNull()
            if(i!=null) { timeline.progress=i; showFrame(i) }
            else status.text="Todos los fotogramas tienen lectura; revisa también que los puntos sean correctos."
        }; steps.addView(badButton,weight())
        val repair=row(panel)
        cropButton=button("Seleccionar mano") {
            if(!busy && frames.isNotEmpty()) {
                frameView.beginSelection()
                status.text="Arrastra un rectángulo alrededor de TODA la mano y la muñeca, con margen. Después pulsa Reintentar cuadro."
            }
        }; repair.addView(cropButton,weight())
        retryButton=button("Reintentar cuadro") { retryFrame() }; repair.addView(retryButton,weight())
        val reviewRow=row(panel)
        rejectButton=button("Rechazar cuadro") {
            if(!busy && timeline.progress in frames.indices) {
                val i=timeline.progress
                frames[i]=frames[i].copy(sample=null); dirty(); showFrame(i)
                status.text="Cuadro rechazado. Vuelve a detectarlo o restaura el original; no se guardan huecos."
            }
        }; reviewRow.addView(rejectButton,weight())
        undoButton=button("Restaurar cuadro") {
            if(!busy && timeline.progress in originals.indices) {
                val i=timeline.progress
                frames[i]=originals[i]; dirty(); showFrame(i)
                status.text="Lectura del primer mapeo restaurada; comprueba sus puntos."
            }
        }; reviewRow.addView(undoButton,weight())
        val range=row(panel)
        startButton=button("Marcar inicio") { startIndex=timeline.progress; dirty(); rangeText() }
        endButton=button("Marcar final") { endIndex=timeline.progress; dirty(); rangeText() }
        range.addView(startButton,weight()); range.addView(endButton,weight())
        trim=label("Selecciona una sola ejecución completa.",13f); panel.addView(trim)
        reviewed=CheckBox(this).apply {
            text="Revisé los puntos de todo el tramo y la postura inicial y final"; setTextColor(Color.WHITE); textSize=13f
            buttonTintList=ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_checked),intArrayOf()),
                intArrayOf(Color.GRAY,Color.CYAN,Color.WHITE))
            setOnCheckedChangeListener { _,_->controls() }
        }; panel.addView(reviewed)
        saveHint=label("Mapea el video para preparar una referencia.",13f); panel.addView(saveHint)
        val actions=row(panel)
        saveButton=button("Guardar referencia") { save() }; actions.addView(saveButton,weight())
        practiceButton=button("Practicar J") {
            if(saved && !busy) practiceResult.launch(Intent(this,JPracticeActivity::class.java)
                .putExtra("mirror",intent.getBooleanExtra("mirror",false)))
        }; actions.addView(practiceButton,weight())
        status=label("Abriendo referencia…",14f); panel.addView(status)
        root.addView(button("Regresar") { finish() })
        controls(); showVideo()
    }
    private fun showVideo() {
        frameRequest++; frameView.visibility=View.GONE; video.visibility=View.VISIBLE
        controls()
        video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.letra_j}"))
    }
    private fun controls() {
        mapButton.isEnabled=!busy && source.isNotEmpty()
        timeline.isEnabled=!busy && frames.isNotEmpty()
        startButton.isEnabled=timeline.isEnabled; endButton.isEnabled=timeline.isEnabled; badButton.isEnabled=timeline.isEnabled
        reviewed.isEnabled=timeline.isEnabled
        val canRepair=timeline.isEnabled && frameView.visibility==View.VISIBLE && frameView.hasFrame()
        cropButton.isEnabled=canRepair; retryButton.isEnabled=canRepair
        rejectButton.isEnabled=canRepair; undoButton.isEnabled=canRepair
        val reason=when {
            frames.isEmpty() -> "Mapea el video para preparar una referencia."
            startIndex>=endIndex -> "El final debe estar después del inicio."
            (startIndex..endIndex).any { frames[it].sample==null } -> {
                val missing=(startIndex..endIndex).filter { frames[it].sample==null }
                "Faltan ${missing.size} lecturas en el tramo. Primer cuadro: ${missing.first()+1}. Usa Sin lectura y Reintentar cuadro."
            }
            else -> runCatching { JReferenceStore.validate(frames.subList(startIndex,endIndex+1)) }
                .exceptionOrNull()?.message
        }
        saveHint.text=if(busy) "Procesando…" else reason ?: if(!reviewed.isChecked)
            "Falta confirmar la casilla de revisión." else "Tramo listo para guardar tras tu revisión visual."
        saveHint.setTextColor(if(reason!=null && frames.isNotEmpty()) Color.YELLOW else Color.WHITE)
        saveButton.isEnabled=!busy && reason==null && reviewed.isChecked
        practiceButton.isEnabled=!busy && saved
    }
    private fun dirty() { saved=false; reviewed.isChecked=false; controls() }
    private fun rangeText() {
        trim.text="Inicio: ${seconds(frames[startIndex].timeMs)} s · Final: ${seconds(frames[endIndex].timeMs)} s"
        if(startIndex>=endIndex) trim.append(" · El final debe estar después del inicio")
    }
    private fun extract() {
        if(busy || source.isEmpty()) return
        busy=true; dirty(); frames.clear(); originals.clear(); images.clear(); startIndex=0; endIndex=0; frameRequest++
        frameView.show(null,null); video.pause(); controls()
        status.text="Mapeando el video. No cierres esta pantalla…"
        executor.execute {
            var detector:HandLandmarker?=null
            val reader=MediaMetadataRetriever()
            try {
                resources.openRawResourceFd(R.raw.letra_j).use { fd -> reader.setDataSource(fd.fileDescriptor,fd.startOffset,fd.length) }
                val duration=requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                require(duration in 1200L..30000L) { "Usa un video entre 1.2 y 30 segundos" }
                val model=HandLandmarker.createFromOptions(this,HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumHands(2).build())
                detector=model
                val result=mutableListOf<JReferenceStore.Frame>(); val files=mutableListOf<File>()
                var t=0L
                while(t<duration) {
                    if(closing) return@execute
                    val original=requireNotNull(reader.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST))
                    val ratio=minOf(1.0,1080.0/maxOf(original.width,original.height))
                    val resized=Bitmap.createScaledBitmap(original,(original.width*ratio).toInt().coerceAtLeast(1),(original.height*ratio).toInt().coerceAtLeast(1),true)
                    if(resized!==original) original.recycle()
                    val bitmap=if(resized.config==Bitmap.Config.ARGB_8888) resized else requireNotNull(resized.copy(Bitmap.Config.ARGB_8888,false)).also { resized.recycle() }
                    val file=File(temp,"frame_${t}.png")
                    file.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    val image=BitmapImageBuilder(bitmap).build()
                    val sample=try { JLandmarkSamples.sample(model.detectForVideo(image,t),bitmap.width,bitmap.height,false)?.takeIf { it.valid() } }
                        finally { image.close(); if(!bitmap.isRecycled) bitmap.recycle() }
                    result.add(JReferenceStore.Frame(t,sample)); files.add(file)
                    val pct=(100*t/duration).toInt()
                    if(result.size%5==0) runOnUiThread { if(!closing) status.text="Mapeando video: $pct%" }
                    t+=JReferenceStore.STEP_MS
                }
                runOnUiThread { if(!closing) {
                    frames.addAll(result); originals.addAll(result); images.addAll(files); busy=false; startIndex=0; endIndex=frames.lastIndex
                    timeline.max=frames.lastIndex; timeline.progress=0; controls(); rangeText(); showFrame(0)
                    val missing=frames.count { it.sample==null }
                    status.text="Mapeo listo: ${frames.size-missing}/${frames.size} lecturas. Revisa cuadro a cuadro, marca inicio y final y confirma la revisión."
                } }
            } catch(e:Exception) { error("No se pudo mapear: ${e.message}") }
            finally { reader.release(); detector?.close() }
        }
    }
    private fun showFrame(index:Int) {
        if(busy || index !in frames.indices) return
        video.pause(); video.visibility=View.GONE; frameView.visibility=View.VISIBLE
        frameView.show(null,null); controls()
        val frame=frames[index]; val file=images[index]; val token=++frameRequest
        position.text="${index+1}/${frames.size} · ${seconds(frame.timeMs)} s · ${if(frame.sample==null) "SIN LECTURA" else "Puntos detectados; verifica su posición"}"
        executor.execute {
            if(closing || token!=frameRequest) return@execute
            val bitmap=BitmapFactory.decodeFile(file.absolutePath)
            runOnUiThread {
                if(closing || token!=frameRequest) bitmap?.recycle() else { frameView.show(bitmap,frame.sample); controls() }
            }
        }
    }
    /** Fresh IMAGE detection avoids inheriting a bad VIDEO tracking state.
     * Cropped 2D coordinates return to the full frame; model-derived 3D flexions stay intact. */
    private fun retryFrame() {
        if(busy || timeline.progress !in frames.indices || !frameView.hasFrame()) return
        val index=timeline.progress
        val crop=frameView.selectedRegion()
        if(frameView.isSelecting() && crop==null) {
            status.text="Dibuja un rectángulo más amplio alrededor de toda la mano y la muñeca."
            return
        }
        val file=images[index]
        busy=true; frameView.cancelSelection(); frameRequest++; controls()
        status.text="Detectando de nuevo el cuadro ${index+1}${if(crop!=null) " en la zona seleccionada" else " completo"}…"
        executor.execute {
            var detector:HandLandmarker?=null
            var full:Bitmap?=null
            var input:Bitmap?=null
            try {
                val decoded=requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
                val bitmap=if(decoded.config==Bitmap.Config.ARGB_8888) decoded
                    else requireNotNull(decoded.copy(Bitmap.Config.ARGB_8888,false)).also { decoded.recycle() }
                full=bitmap
                val box=crop ?: Rect(0,0,bitmap.width,bitmap.height)
                val region=Bitmap.createBitmap(bitmap,box.left,box.top,box.width(),box.height())
                input=region
                val model=HandLandmarker.createFromOptions(this,HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.IMAGE).setNumHands(2).build())
                detector=model
                val image=BitmapImageBuilder(region).build()
                val local=try { JLandmarkSamples.sample(model.detect(image),region.width,region.height,false) }
                    finally { image.close() }
                val candidate=local?.let { sample ->
                    JPracticeEngine.Sample(sample.points.map { p ->
                        doubleArrayOf(p[0]+box.left,p[1]+box.top)
                    }.toTypedArray(),sample.flex,sample.side)
                }?.takeIf { it.valid() }
                runOnUiThread { if(!closing) {
                    busy=false
                    if(candidate==null) {
                        controls()
                        status.text="No se obtuvo una lectura válida; se conserva la anterior. Amplía la selección para incluir mano y muñeca o cambia el video."
                    } else {
                        frames[index]=frames[index].copy(sample=candidate)
                        dirty(); showFrame(index)
                        status.text="Nueva detección en el cuadro ${index+1}. Comprueba TODOS los puntos; si empeoró, pulsa Restaurar cuadro."
                    }
                } }
            } catch(e:Exception) { error("No se pudo reintentar: ${e.message}") }
            finally {
                detector?.close()
                input?.let { if(it!==full && !it.isRecycled) it.recycle() }
                full?.let { if(!it.isRecycled) it.recycle() }
            }
        }
    }
    private fun save() {
        if(busy || !reviewed.isChecked || startIndex>=endIndex) return
        val selected=frames.subList(startIndex,endIndex+1).toList()
        try { JReferenceStore.validate(selected) }
        catch(e:Exception) { status.text="No se guardó: ${e.message}"; return }
        busy=true; controls(); status.text="Guardando referencia…"
        executor.execute {
            try {
                JReferenceStore.save(this,source,selected)
                runOnUiThread { if(!closing) { busy=false; saved=true; controls(); status.text="Referencia guardada. Pulsa Practicar J para imitar el tramo elegido." } }
            } catch(e:Exception) { error("No se pudo guardar: ${e.message}") }
        }
    }
    private fun error(message:String) { runOnUiThread { if(!closing) { busy=false; controls(); status.text=message } } }
    override fun onResume() { super.onResume(); active=true; if(video.visibility==View.VISIBLE) video.start() }
    override fun onPause() { active=false; video.pause(); super.onPause() }
    override fun onDestroy() {
        closing=true; frameRequest++; video.stopPlayback(); frameView.show(null,null)
        executor.execute { temp.deleteRecursively() }; executor.shutdown(); super.onDestroy()
    }
    private fun label(value:String,size:Float)=TextView(this).apply { text=value; textSize=size; setTextColor(Color.WHITE); setPadding(dp(8),dp(3),dp(8),dp(3)) }
    private fun button(value:String,action:()->Unit)=Button(this).apply { text=value; textSize=12f; setOnClickListener { action() } }
    private fun row(parent:LinearLayout)=LinearLayout(this).also { parent.addView(it) }
    private fun weight()=LinearLayout.LayoutParams(0,dp(48),1f)
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun seconds(t:Long)=String.format(Locale.getDefault(),"%.1f",t/1000.0)
}
