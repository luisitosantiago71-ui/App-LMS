package com.mechrobotix.aprendels

import android.content.Context
import org.json.JSONObject

data class WordLesson(val id:String,val title:String,val videoName:String,val video:Int,val tolerance:WordTolerance)
data class LearningModule(val id:String,val title:String,val lessons:List<WordLesson>)

/** Orden y contenido editables en assets/word_lessons.json; sin menús de edición en la app. */
object WordCatalog {
    fun load(context:Context):List<LearningModule> {
        val root=JSONObject(context.assets.open("word_lessons.json").bufferedReader().use{it.readText()})
        val modules=root.getJSONArray("modules")
        val ids=mutableSetOf<String>()
        return (0 until modules.length()).map { i ->
            val m=modules.getJSONObject(i);val list=m.getJSONArray("lessons")
            LearningModule(m.getString("id"),m.getString("title"),(0 until list.length()).map{n ->
                val row=list.getJSONObject(n);val id=row.getString("id");val name=row.getString("video")
                require(ids.add(id)){"Identificador de palabra repetido: $id"}
                require(name.matches(Regex("[a-z][a-z0-9_]*"))){"Nombre de video inválido: $name"}
                val res=context.resources.getIdentifier(name,"raw",context.packageName)
                require(res!=0){"Falta el video raw/$name"}
                val t=WordTolerance()
                row.optJSONObject("tolerance")?.let{o ->
                    t.shape=o.optDouble("shape",t.shape);t.meanFlex=o.optDouble("meanFlex",t.meanFlex)
                    t.maxFlex=o.optDouble("maxFlex",t.maxFlex);t.travel=o.optDouble("travel",t.travel)
                    t.travelRelative=o.optDouble("travelRelative",t.travelRelative)
                    t.minMotionRatio=o.optDouble("minMotionRatio",t.minMotionRatio)
                    t.minMotion=o.optDouble("minMotion",t.minMotion)
                    t.directionCosine=o.optDouble("directionCosine",t.directionCosine)
                    t.minTravelProgress=o.optDouble("minTravelProgress",t.minTravelProgress)
                    t.stability=o.optDouble("stability",t.stability)
                    t.startHoldMs=o.optLong("startHoldMs",t.startHoldMs);t.endHoldMs=o.optLong("endHoldMs",t.endHoldMs)
                    t.lostGraceMs=o.optLong("lostGraceMs",t.lostGraceMs)
                    t.maxAttemptMs=o.optLong("maxAttemptMs",t.maxAttemptMs);t.minAttemptMs=o.optLong("minAttemptMs",t.minAttemptMs)
                };t.validate()
                WordLesson(id,row.getString("title"),name,res,t)
            })
        }
    }
}
