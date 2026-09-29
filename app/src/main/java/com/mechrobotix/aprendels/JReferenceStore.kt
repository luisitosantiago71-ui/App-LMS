package com.mechrobotix.aprendels

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Referencia local revisada por el usuario; no modifica las guías del abecedario. */
object JReferenceStore {
    const val STEP_MS = 100L
    data class Frame(val timeMs:Long, val sample:JPracticeEngine.Sample?)
    data class Reference(val source:String, val frames:List<Frame>)
    private val hashes=java.util.concurrent.ConcurrentHashMap<Int,String>()
    fun fingerprint(context:Context,raw:Int=R.raw.letra_j):String {
        hashes[raw]?.let { return it }
        val md=MessageDigest.getInstance("SHA-256")
        context.resources.openRawResource(raw).use { input ->
            val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer); if(n<0) break; md.update(buffer,0,n) }
        }
        context.assets.open("hand_landmarker.task").use { input ->
            val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer); if(n<0) break; md.update(buffer,0,n) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }.also { hashes[raw]=it }
    }
    private fun file(context:Context)=AtomicFile(File(context.filesDir,"j_reference_v1.json"))
    /** Only pair an older mapping with the exact video/model used to create it. */
    fun legacyVideoResource(context:Context):Int? = runCatching {
        val af=file(context)
        if(!af.baseFile.exists()) return@runCatching null
        val source=JSONObject(af.openRead().bufferedReader().use { it.readText() }).getString("source")
        listOf(R.raw.letra_j,R.raw.letra_j).firstOrNull { fingerprint(context,it)==source }
    }.getOrNull()
    fun validate(frames:List<Frame>):List<JPracticeEngine.Sample> {
        require(frames.size>=12) { "Selecciona al menos 1.1 segundos de recorrido" }
        require(frames.zipWithNext().all { (a,b)->b.timeMs-a.timeMs in 1L..150L }) { "Hay huecos en la referencia" }
        val samples=frames.map { requireNotNull(it.sample) { "Hay fotogramas sin lectura. Revisa el tramo" } }
        require(samples.all { it.valid() }) { "Hay ángulos o puntos no válidos" }
        require(samples.map { it.side }.distinct().size==1) { "La identificación de la mano cambia. Revisa el tramo" }
        JPracticeEngine(samples) // Comprueba también que exista recorrido.
        return samples
    }
    fun load(context:Context,source:String):Reference? {
        val af=file(context)
        if(!af.baseFile.exists()) return null
        val obj=JSONObject(af.openRead().bufferedReader().use { it.readText() })
        require(obj.getInt("version")==1 && obj.getString("source")==source) {
            "El video o el modelo cambió. Prepara de nuevo la referencia"
        }
        val rows=obj.getJSONArray("frames")
        val frames=(0 until rows.length()).map { i ->
            val row=rows.getJSONObject(i); val points=row.getJSONArray("p"); val flex=row.getJSONArray("f")
            require(points.length()==21 && flex.length()==10)
            val p=Array(21) { j -> val a=points.getJSONArray(j); require(a.length()==2); doubleArrayOf(a.getDouble(0),a.getDouble(1)) }
            Frame(row.getLong("t"),JPracticeEngine.Sample(p,DoubleArray(10) { flex.getDouble(it) },row.getString("side")))
        }
        validate(frames)
        return Reference(source,frames)
    }
}
