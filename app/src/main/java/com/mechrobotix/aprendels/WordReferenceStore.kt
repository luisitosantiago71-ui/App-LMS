package com.mechrobotix.aprendels

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Caché privada por video. Nunca reutiliza referencias de otro contenido, modelo o generador. */
object WordReferenceStore {
    data class Reference(val keys:List<WordPracticeEngine.Frame>,val startMs:Int,val endMs:Int)
    private var modelHash:String?=null

    @Synchronized
    fun loadOrGenerate(context:Context,lesson:WordLesson,cancelled:()->Boolean={false},
                       onProgress:(Int)->Unit={}):Reference {
        WordVideoReferenceGenerator.checkCancelled(cancelled)
        val folder=File(context.noBackupFilesDir,"word_references_auto").apply{mkdirs()}
        val cache=AtomicFile(File(folder,"${lesson.videoName}.json"))
        val video=File.createTempFile("word_reference_",".mp4",context.cacheDir)
        try {
            val hash=MessageDigest.getInstance("SHA-256")
            context.resources.openRawResource(lesson.video).use { input ->
                video.outputStream().use { output ->
                    val bytes=ByteArray(65536)
                    while(true) {
                        WordVideoReferenceGenerator.checkCancelled(cancelled)
                        val n=input.read(bytes);if(n<0)break
                        hash.update(bytes,0,n);output.write(bytes,0,n)
                    }
                }
            }
            val videoHash=hex(hash.digest())
            val model=modelHash ?: context.assets.open(WordVideoReferenceGenerator.MODEL_ASSET).use{digest(it)}.also{modelHash=it}
            WordVideoReferenceGenerator.checkCancelled(cancelled)
            val saved=try {
                cache.openRead().bufferedReader().use{JSONObject(it.readText())}.let { json ->
                    if(matches(json,lesson.videoName,videoHash,model))decode(json) else null
                }
            }catch(_:Exception){null}
            if(saved!=null)return saved
            onProgress(0)
            val result=WordVideoReferenceGenerator.generate(context,video,cancelled,onProgress)
            WordVideoReferenceGenerator.checkCancelled(cancelled)
            val ref=Reference(result.keys,result.startMs,result.endMs)
            val json=encode(ref,lesson.videoName,videoHash,model)
                .put("sampledFrames",result.sampledFrames).put("largestGapMs",result.largestGapMs)
            // Un cierre durante la escritura no deja medio JSON publicado.
            val bytes=json.toString().toByteArray(Charsets.UTF_8)
            try {
                val stream=cache.startWrite()
                try {stream.write(bytes);cache.finishWrite(stream)}
                catch(e:Exception){cache.failWrite(stream);throw e}
            }catch(e:Exception){Log.w("WordReference","No se pudo conservar la caché; se usará la referencia en memoria",e)}
            return ref
        }finally{video.delete()}
    }

    private fun matches(json:JSONObject,name:String,videoHash:String,model:String)=
        json.optInt("schema")==2 && json.optInt("generator")==WordReferenceBuilder.VERSION &&
            json.optString("video")==name && json.optString("sha256")==videoHash && json.optString("modelSha256")==model

    private fun decode(json:JSONObject):Reference {
        val rows=json.getJSONArray("keys")
        require(rows.length() in 4..(WordReferenceBuilder.MAX_VIDEO_MS/WordReferenceBuilder.STEP_MS)){"Número de hitos inválido"}
        val keys=(0 until rows.length()).map { i ->
            val f=rows.getJSONObject(i);val source=f.getJSONObject("hands")
            val primary=f.getString("primary")
            require(primary=="Left" || primary=="Right")
            require(source.length() in 1..2 && source.has(primary))
            val hands=linkedMapOf<String,JPracticeEngine.Sample>()
            listOf(primary,if(primary=="Left")"Right" else "Left").forEach { side ->
                if(source.has(side)) {
                    val h=source.getJSONObject(side);val p=h.getJSONArray("points");val flex=h.getJSONArray("flex")
                    require(p.length()==21 && flex.length()==10)
                    hands[side]=JPracticeEngine.Sample(Array(21){n ->
                        val xy=p.getJSONArray(n);require(xy.length()==2);doubleArrayOf(xy.getDouble(0),xy.getDouble(1))
                    },DoubleArray(10){n->flex.getDouble(n)},side)
                }
            }
            WordPracticeEngine.Frame(f.getLong("timeMs"),hands).also{require(it.valid())}
        }
        require(keys.zipWithNext().all{it.first.timeMs<it.second.timeMs})
        val start=json.getInt("startMs");val end=json.getInt("endMs")
        require(start>=0 && end>start && end<=WordReferenceBuilder.MAX_VIDEO_MS &&
            keys.first().timeMs==start.toLong() && keys.last().timeMs==end.toLong())
        return Reference(keys,start,end)
    }

    private fun encode(ref:Reference,name:String,videoHash:String,model:String):JSONObject {
        val keys=JSONArray()
        ref.keys.forEach { f ->
            val hands=JSONObject()
            f.hands.forEach { (side,h) ->
                val points=JSONArray();h.points.forEach{points.put(JSONArray().put(it[0]).put(it[1]))}
                val flex=JSONArray();h.flex.forEach{flex.put(it)}
                hands.put(side,JSONObject().put("points",points).put("flex",flex))
            }
            keys.put(JSONObject().put("timeMs",f.timeMs).put("primary",f.hands.keys.first()).put("hands",hands))
        }
        return JSONObject().put("schema",2).put("generator",WordReferenceBuilder.VERSION)
            .put("video",name).put("sha256",videoHash).put("modelSha256",model)
            .put("startMs",ref.startMs).put("endMs",ref.endMs).put("keys",keys)
    }
    private fun digest(input:InputStream):String {
        val hash=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(65536)
        while(true){val n=input.read(bytes);if(n<0)break;hash.update(bytes,0,n)}
        return hex(hash.digest())
    }
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
}
