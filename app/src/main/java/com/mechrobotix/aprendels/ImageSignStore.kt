package com.mechrobotix.aprendels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.AtomicFile
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/** Named static signs have stable IDs: renaming never loses per-sign guide settings. */
object ImageSignStore {
    data class Sign(val id:String,val name:String,val image:File,val template:ReferenceTemplate)
    private fun root(c:Context)=File(c.filesDir,"image_signs_v1")
    private fun folder(c:Context,id:String):File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Identificador inválido" }
        return File(root(c),id)
    }
    fun validate(t:ReferenceTemplate) {
        require(t.width>0 && t.height>0 && t.landmarks.size==21) { "Se requieren 21 puntos" }
        require(t.landmarks.all { it.x().isFinite() && it.y().isFinite() && it.z().isFinite() && it.x() in 0f..1f && it.y() in 0f..1f }) { "Puntos fuera de la imagen" }
        require(hypot((t.landmarks[0].x()-t.landmarks[9].x())*t.width,(t.landmarks[0].y()-t.landmarks[9].y())*t.height)>=5f) { "Separa muñeca (0) y base del medio (9)" }
        require(t.outline.size in 3..2000 && t.outline.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }) { "Traza un contorno válido" }
        var area=0f
        t.outline.indices.forEach { i -> val a=t.outline[i];val b=t.outline[(i+1)%t.outline.size];area+=a.x*b.y-b.x*a.y }
        require(abs(area)>.005f) { "El contorno es demasiado pequeño" }
    }
    @Synchronized fun save(c:Context,id:String?,name:String,bitmap:Bitmap,t:ReferenceTemplate):String {
        val clean=name.trim();require(clean.length in 1..80) { "Escribe un nombre de 1 a 80 caracteres" };validate(t)
        require(bitmap.width==t.width && bitmap.height==t.height)
        val key=id ?: UUID.randomUUID().toString();val base=folder(c,key);check(base.mkdirs() || base.isDirectory)
        val version=UUID.randomUUID().toString();val image=File(base,"$version.png")
        val json=File(base,"$version.json");var committed=false
        try {
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val points=JSONArray(t.landmarks.map { JSONArray(listOf(it.x(),it.y(),it.z())) })
            val outline=JSONArray(t.outline.map { JSONArray(listOf(it.x,it.y)) })
            json.writeText(JSONObject().put("version",1).put("name",clean).put("width",t.width).put("height",t.height)
                .put("points",points).put("outline",outline).toString())
            val af=AtomicFile(File(base,"active.json"));val output=af.startWrite()
            try { output.write(JSONObject().put("id",version).toString().toByteArray());af.finishWrite(output);committed=true }
            catch(e:Exception) { af.failWrite(output);throw e }
        } finally { if(!committed) { image.delete();json.delete() } }
        return key
    }
    fun load(c:Context,id:String):Sign {
        val base=folder(c,id);val af=AtomicFile(File(base,"active.json"))
        val v=JSONObject(af.openRead().bufferedReader().use { it.readText() }).getString("id")
        require(v.matches(Regex("[a-f0-9-]{36}")))
        val data=JSONObject(File(base,"$v.json").readText());require(data.getInt("version")==1)
        val p=data.getJSONArray("points");val o=data.getJSONArray("outline")
        require(p.length()==21 && o.length() in 3..2000)
        val points=(0 until p.length()).map { i ->val a=p.getJSONArray(i);NormalizedLandmark.create(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat()) }
        val outline=(0 until o.length()).map { i -> val a=o.getJSONArray(i);PointF(a.getDouble(0).toFloat(),a.getDouble(1).toFloat()) }
        val t=ReferenceTemplate(data.getInt("width"),data.getInt("height"),points,outline);validate(t)
        val image=File(base,"$v.png");require(image.isFile) { "No se encontró la imagen" }
        return Sign(id,data.getString("name"),image,t)
    }
    fun ids(c:Context)=root(c).listFiles().orEmpty().filter { it.isDirectory && File(it,"active.json").isFile }.map { it.name }.sorted()
}
