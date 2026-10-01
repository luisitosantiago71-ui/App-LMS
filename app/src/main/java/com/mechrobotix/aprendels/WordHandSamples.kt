package com.mechrobotix.aprendels

import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.*

/** Usa ambas manos. Asocia por proximidad, no por el orden de los resultados de MediaPipe.
 * Todas las coordenadas usan altura=1000 y conservan la relación de aspecto.
 * Debe utilizarse exclusivamente desde el ejecutor de cámara.
 */
class WordHandSamples(private val mirrorX:Boolean=true) {
    private val previous=mutableMapOf<String,Pair<Long,JPracticeEngine.Sample>>()
    fun reset(){previous.clear()}
    fun read(result:HandLandmarkerResult,width:Int,height:Int,time:Long):WordPracticeEngine.Frame {
        val detected=mutableListOf<Pair<String,JPracticeEngine.Sample>>()
        result.landmarks().take(2).forEachIndexed { index,p ->
            val world=result.worldLandmarks().getOrNull(index) ?: return@forEachIndexed
            if(p.size!=21 || world.size!=21)return@forEachIndexed
            if(p.any{!it.x().isFinite() || !it.y().isFinite() || it.x() !in 0f..1f || it.y() !in 0f..1f})return@forEachIndexed
            val points=p.map{doubleArrayOf((if(mirrorX)1.0-it.x() else it.x().toDouble())*width/height*1000,it.y().toDouble()*1000)}.toTypedArray()
            val joints=arrayOf(intArrayOf(1,2,3),intArrayOf(2,3,4),intArrayOf(5,6,7),intArrayOf(6,7,8),
                intArrayOf(9,10,11),intArrayOf(10,11,12),intArrayOf(13,14,15),intArrayOf(14,15,16),intArrayOf(17,18,19),intArrayOf(18,19,20))
            val flex=DoubleArray(10){n ->
                val (a,b,c)=joints[n]
                val u=doubleArrayOf((world[a].x()-world[b].x()).toDouble(),(world[a].y()-world[b].y()).toDouble(),(world[a].z()-world[b].z()).toDouble())
                val v=doubleArrayOf((world[c].x()-world[b].x()).toDouble(),(world[c].y()-world[b].y()).toDouble(),(world[c].z()-world[b].z()).toDouble())
                val norm=sqrt(u.sumOf{it*it}*v.sumOf{it*it})
                if(norm<1e-12)Double.NaN else 180-Math.toDegrees(acos((u.indices.sumOf{u[it]*v[it]}/norm).coerceIn(-1.0,1.0)))
            }
            val label=result.handedness().getOrNull(index)?.firstOrNull()?.categoryName() ?: "Right"
            val sample=JPracticeEngine.Sample(points,flex,label)
            if(sample.valid() && WordPracticeEngine.palmSize(sample)>=8)detected.add(label to sample)
        }
        val recent=previous.filterValues{time-it.first<=1100}
        val assignments=if(detected.size==2)listOf(listOf("Left","Right"),listOf("Right","Left"))
            else if(detected.size==1)listOf(listOf("Left"),listOf("Right")) else listOf(emptyList())
        val chosen=assignments.minByOrNull{assignment ->
            detected.indices.sumOf { i ->
                val (label,current)=detected[i];val side=assignment[i];val old=recent[side]?.second
                if(old==null)12.0+(if(label==side)0.0 else .8)
                else min(8.0,JPracticeEngine.distance(old.points[0],current.points[0])/max(8.0,WordPracticeEngine.palmSize(old)))+
                    old.flex.indices.sumOf{abs(old.flex[it]-current.flex[it])}/1800.0
            }
        }.orEmpty()
        val hands=linkedMapOf<String,JPracticeEngine.Sample>()
        detected.indices.forEach{i ->
            val side=chosen[i];val source=detected[i].second
            val sample=JPracticeEngine.Sample(source.points,source.flex,side)
            hands[side]=sample;previous[side]=time to sample
        }
        return WordPracticeEngine.Frame(time,hands)
    }
}
