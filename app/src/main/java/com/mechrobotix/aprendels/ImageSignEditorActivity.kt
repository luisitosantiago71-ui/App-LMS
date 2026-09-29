package com.mechrobotix.aprendels

import android.content.Intent
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.mechrobotix.aprendels.databinding.ActivityImageSignEditorBinding
import java.io.File
import java.util.concurrent.Executors

class ImageSignEditorActivity:ComponentActivity() {
    private lateinit var b:ActivityImageSignEditorBinding
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var closing=false
    private var busy=false
    private var photo:Bitmap?=null
    private var editor:ImageMappingView?=null
    private var signId:String?=null
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null && !busy) importImage(uri) }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);b=ActivityImageSignEditorBinding.inflate(layoutInflater);setContentView(b.root);AppUi.insets(b.root);AppUi.styleButtons(b.root)
        listOf(b.importImage,b.mapImage,b.saveImageSign).forEach { AppUi.style(it) }
        listOf(b.removePoint,b.resetMapping,b.backImageEditor).forEach { AppUi.style(it,true) }
        listOf(b.pointsReviewed,b.outlineReviewed).forEach { it.buttonTintList=AppUi.checkTint(this);it.setOnCheckedChangeListener { _,_->controls() } }
        b.importImage.setOnClickListener { picker.launch(arrayOf("image/*")) }
        b.mapImage.setOnClickListener { detect() }
        b.traceOutline.setOnCheckedChangeListener { _,checked -> editor?.tracing=checked; b.mappingHint.text=editor?.instructions() }
        b.removePoint.setOnClickListener { editor?.removeLastPoint() }
        b.resetMapping.setOnClickListener { editor?.resetDraft() }
        b.backImageEditor.setOnClickListener { finish() }
        b.saveImageSign.setOnClickListener { save() }
        controls()
        signId=intent.getStringExtra("sign_id")
        signId?.let { id ->
            busy=true;controls()
            worker.execute {
                try {
                    val sign=ImageSignStore.load(this,id);val bitmap=requireNotNull(BitmapFactory.decodeFile(sign.image.absolutePath))
                    runOnUiThread { if(closing) bitmap.recycle() else { busy=false;b.signName.setText(sign.name);show(bitmap,sign.template);b.imageEditorStatus.text="Referencia guardada cargada. Revisa cualquier cambio antes de guardar." } }
                } catch(e:Exception) { fail("No se pudo abrir: ${e.message}") }
            }
        }
    }
    private fun controls() {
        b.importImage.isEnabled=!busy;b.mapImage.isEnabled=!busy && photo!=null
        b.removePoint.isEnabled=!busy && editor!=null;b.resetMapping.isEnabled=b.removePoint.isEnabled
        b.traceOutline.isEnabled=b.removePoint.isEnabled
        b.pointsReviewed.isEnabled=!busy && photo!=null;b.outlineReviewed.isEnabled=b.pointsReviewed.isEnabled
        b.saveImageSign.isEnabled=!busy && editor!=null && b.pointsReviewed.isChecked && b.outlineReviewed.isChecked
        editor?.isEnabled=!busy
    }
    private fun changed() { b.pointsReviewed.isChecked=false;b.outlineReviewed.isChecked=false;controls() }
    private fun show(bitmap:Bitmap,template:ReferenceTemplate?) {
        val old=photo;b.mappingArea.removeAllViews();photo=bitmap
        editor=ImageMappingView(this,bitmap,template).apply {
            onHint={ b.mappingHint.text=it };onChanged={ changed() }
            tracing=b.traceOutline.isChecked
        }.also { b.mappingArea.addView(it,FrameLayout.LayoutParams(-1,-1));b.mappingHint.text=it.instructions() }
        if(old!==bitmap) old?.recycle()
        changed()
    }
    private fun importImage(uri:Uri) {
        busy=true;controls();b.imageEditorStatus.text="Abriendo imagen…"
        worker.execute {
            val file=File(cacheDir,"image_import_${System.nanoTime()}")
            try {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input);file.outputStream().use { out ->
                        val buffer=ByteArray(8192);var total=0L
                        while(true) { val n=input.read(buffer);if(n<0) break;total+=n;require(total<=32L*1024*1024) { "Usa una imagen de hasta 32 MB" };out.write(buffer,0,n) }
                    }
                }
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.path,bounds)
                require(bounds.outWidth>0 && bounds.outHeight>0) { "Formato de imagen no compatible" }
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048) sample*=2
                var bitmap=requireNotNull(BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }))
                val orientation=runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)
                val matrix=Matrix().apply { when(orientation) { 2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f) } }
                val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
                if(rotated!==bitmap) bitmap.recycle();bitmap=rotated
                val ratio=minOf(1.0,1200.0/maxOf(bitmap.width,bitmap.height))
                val scaled=Bitmap.createScaledBitmap(bitmap,(bitmap.width*ratio).toInt().coerceAtLeast(1),(bitmap.height*ratio).toInt().coerceAtLeast(1),true)
                if(scaled!==bitmap) bitmap.recycle()
                runOnUiThread { if(closing) scaled.recycle() else { busy=false;show(scaled,null);b.imageEditorStatus.text="Pulsa Mapear. También puedes colocar los 21 puntos y trazar la silueta manualmente." } }
            } catch(e:Exception) { fail("No se pudo importar: ${e.message}") }
            finally { file.delete() }
        }
    }
    private fun detect() {
        val bitmap=photo ?: return;if(busy)return
        busy=true;controls();b.imageEditorStatus.text="Detectando puntos y contorno…"
        worker.execute {
            var model:HandLandmarker?=null
            try {
                model=HandLandmarker.createFromOptions(this,HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.IMAGE).setNumHands(2).build())
                val image=BitmapImageBuilder(requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888,false))).build()
                val result=try { model.detect(image) } finally { image.close() }
                require(result.landmarks().size==1) { "Se necesita una sola mano completa. Puedes corregir o colocar los puntos manualmente." }
                val points=result.landmarks()[0]
                require(points.size==21 && points.all { it.x().isFinite() && it.y().isFinite() && it.x() in 0f..1f && it.y() in 0f..1f }) { "La mano sale del encuadre" }
                val template=runCatching { ReferenceTemplate.automatic(bitmap,points) }.getOrElse { ReferenceTemplate(bitmap.width,bitmap.height,points,emptyList()) }
                runOnUiThread { if(!closing) { busy=false;show(bitmap,template);b.imageEditorStatus.text="Mapeo propuesto. Arrastra los puntos incorrectos y revisa la silueta. El contorno automático funciona mejor con fondo claro." } }
            } catch(e:Exception) { fail("Revisa el mapeo: ${e.message}") }
            finally { model?.close() }
        }
    }
    private fun save() {
        if(busy || !b.pointsReviewed.isChecked || !b.outlineReviewed.isChecked) return
        val name=b.signName.text.toString().trim()
        val template=try { require(name.length in 1..80) { "Escribe el nombre de la seña" };requireNotNull(editor).snapshot().also { ImageSignStore.validate(it) } }
            catch(e:Exception) { b.imageEditorStatus.text=e.message;return }
        val bitmap=photo ?: return;busy=true;controls()
        worker.execute {
            try {
                val id=ImageSignStore.save(this,signId,name,bitmap,template)
                runOnUiThread { if(!closing) {
                    busy=false;signId=id;controls()
                    android.app.AlertDialog.Builder(this).setTitle("Seña guardada")
                        .setMessage("$name ya está en Mis señas con imágenes.")
                        .setPositiveButton("Practicar") { _,_-> startActivity(Intent(this,ImageSignPracticeActivity::class.java).putExtra("sign_id",id));finish() }
                        .setNegativeButton("Volver a mis señas") { _,_->finish() }.show()
                } }
            } catch(e:Exception) { fail("No se guardó: ${e.message}") }
        }
    }
    private fun fail(message:String)=runOnUiThread { if(!closing) { busy=false;controls();b.imageEditorStatus.text=message } }
    override fun onDestroy() {
        closing=true;b.mappingArea.removeAllViews();val bitmap=photo;photo=null;editor=null
        worker.execute { bitmap?.let { if(!it.isRecycled) it.recycle() } };worker.shutdown();super.onDestroy()
    }
}
