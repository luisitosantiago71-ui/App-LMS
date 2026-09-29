package com.mechrobotix.aprendels

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Immutable video+landmarks versions; the active pointer changes only after a complete save. */
object MotionReferenceStore {
    val letters=listOf("J","K","Ñ","Q","X","Z")
    fun bundled(letter:String):Int=when(letter) {
        "J"->R.raw.letra_j;"K"->R.raw.letra_k;"Ñ"->R.raw.letra_enye
        "Q"->R.raw.letra_q;"X"->R.raw.letra_x;"Z"->R.raw.letra_z
        else->throw IllegalArgumentException("Letra no dinámica")
    }
    fun isDynamic(letter:String)=letter in letters
    data class Reference(val video:File,val frames:List<JReferenceStore.Frame>,val source:String,val tolerance:Int=2,val id:String?=null)
    private fun root(context:Context,letter:String):File {
        require(letter in letters) { "Letra no editable" }
        return File(context.filesDir,"motion_references/$letter")
    }
    private fun active(context:Context,letter:String)=AtomicFile(File(root(context,letter),"active.json"))
    fun video(context:Context,letter:String):File? {
        val pointer=active(context,letter)
        if(!pointer.baseFile.exists()) return null
        val id=JSONObject(pointer.openRead().bufferedReader().use { it.readText() }).getString("id")
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Referencia dañada" }
        return File(root(context,letter),"$id/video.mp4").takeIf { it.isFile }
    }
    fun uri(context:Context,letter:String):Uri? {
        if(!isDynamic(letter)) return null
        return runCatching { video(context,letter)?.let { Uri.fromFile(it) } }.getOrNull()
            ?: Uri.parse("android.resource://${context.packageName}/${if(letter=="J") JReferenceStore.legacyVideoResource(context) ?: bundled(letter) else bundled(letter)}")
    }
    fun fingerprint(context:Context,video:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        fun feed(input:java.io.InputStream)=input.use {
            val bytes=ByteArray(8192)
            while(true) { val n=it.read(bytes); if(n<0) break; digest.update(bytes,0,n) }
        }
        feed(video.inputStream()); feed(context.assets.open("hand_landmarker.task"))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun validate(frames:List<JReferenceStore.Frame>,letter:String=""):List<JPracticeEngine.Sample> {
        require(frames.size>=12) { "Selecciona al menos 1.1 segundos de recorrido" }
        require(frames.first().timeMs>=0 && frames.zipWithNext().all { (a,b)-> b.timeMs-a.timeMs in 1L..150L }) { "Hay huecos en el tramo" }
        val samples=frames.map { requireNotNull(it.sample) { "Hay cuadros sin lectura" } }
        require(samples.all { it.valid() }) { "Hay puntos o ángulos no válidos" }
        require(samples.map { it.side }.distinct().size==1) { "Cambió la mano detectada. Revisa el tramo" }
        if(letter=="J") JPracticeEngine(samples) else SequencePracticeEngine(samples)
        return samples
    }
    @Synchronized
    fun save(context:Context,letter:String,video:File,source:String,frames:List<JReferenceStore.Frame>,tolerance:Int=2,publish:Boolean=true):String {
        validate(frames,letter)
        require(tolerance in 0..2)
        require(video.isFile && video.length() in 1..134217728L) { "Video no disponible o demasiado grande" }
        require(fingerprint(context,video)==source) { "El video cambió; vuelve a mapearlo" }
        val folder=File(root(context,letter),UUID.randomUUID().toString())
        check(folder.mkdirs()) { "No se pudo crear la referencia" }
        var committed=false
        try {
            video.copyTo(File(folder,"video.mp4"))
            val rows=JSONArray()
            frames.forEach { f ->
                val s=requireNotNull(f.sample)
                rows.put(JSONObject().put("t",f.timeMs).put("side",s.side)
                    .put("p",JSONArray(s.points.map { JSONArray(it.toList()) }))
                    .put("f",JSONArray(s.flex.toList())))
            }
            File(folder,"reference.json").writeText(JSONObject().put("version",1).put("letter",letter)
                .put("source",source).put("tolerance",tolerance).put("frames",rows).toString())
            if(publish) activate(context,letter,folder.name)
            committed=true
        } finally { if(!committed) folder.deleteRecursively() }
        return folder.name
    }
    private fun activate(context:Context,letter:String,id:String) {
        val pointer=active(context,letter);val out=pointer.startWrite()
        try { out.write(JSONObject().put("id",id).toString().toByteArray());pointer.finishWrite(out) }
        catch(e:Exception) { pointer.failWrite(out);throw e }
    }
    fun load(context:Context,letter:String,id:String?=null):Reference? {
        val video=if(id==null) video(context,letter) else {
            require(id.matches(Regex("[a-f0-9-]{36}"))) { "Borrador no válido" }
            File(root(context,letter),"$id/video.mp4")
        }
        if(video==null) {
            if(letter!="J") return null
            val raw=JReferenceStore.legacyVideoResource(context) ?: return null
            val legacy=JReferenceStore.load(context,JReferenceStore.fingerprint(context,raw)) ?: return null
            val file=File(context.cacheDir,"legacy_j_${legacy.source}.mp4")
            if(!file.isFile) {
                val candidate=File.createTempFile("legacy_j_", ".mp4",context.cacheDir)
                try {
                    context.resources.openRawResource(raw).use { input ->candidate.outputStream().use { input.copyTo(it) } }
                    check(candidate.renameTo(file)) { "No se pudo abrir el video anterior de J" }
                } finally { candidate.delete() }
            }
            return Reference(file,legacy.frames,legacy.source)
        }
        val obj=JSONObject(File(video.parentFile,"reference.json").readText())
        require(obj.getInt("version")==1 && obj.getString("letter")==letter) { "Referencia incompatible" }
        val source=obj.getString("source")
        require(source==fingerprint(context,video)) { "El video o modelo cambió; prepara otra referencia" }
        val rows=obj.getJSONArray("frames")
        val frames=(0 until rows.length()).map { i ->
            val row=rows.getJSONObject(i); val points=row.getJSONArray("p"); val flex=row.getJSONArray("f")
            require(points.length()==21 && flex.length()==10)
            val p=Array(21) { j -> val a=points.getJSONArray(j); require(a.length()==2); doubleArrayOf(a.getDouble(0),a.getDouble(1)) }
            JReferenceStore.Frame(row.getLong("t"),JPracticeEngine.Sample(p,DoubleArray(10) { flex.getDouble(it) },row.getString("side")))
        }
        validate(frames,letter)
        return Reference(video,frames,source,obj.optInt("tolerance",2).coerceIn(0,2),video.parentFile?.name)
    }
}
