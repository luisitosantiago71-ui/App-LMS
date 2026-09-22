package com.mechrobotix.aprendels

import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

object JLandmarkSamples {
    fun sample(result:HandLandmarkerResult,w:Int,h:Int,front:Boolean):JPracticeEngine.Sample? {
        if(result.landmarks().size!=1) return null
        val p=result.landmarks()[0]
        val world=result.worldLandmarks().firstOrNull() ?: return null
        if(p.size!=21 || world.size!=21 || p.any { !it.x().isFinite() || !it.y().isFinite() ||
                it.x() !in 0.005f..0.995f || it.y() !in 0.005f..0.995f }) return null
        val points=p.map { doubleArrayOf((if(front) 1.0-it.x() else it.x().toDouble())*w,it.y().toDouble()*h) }.toTypedArray()
        val worldPoints=world.map { doubleArrayOf(it.x().toDouble(),it.y().toDouble(),it.z().toDouble()) }.toTypedArray()
        val flex=fingerFlexions(worldPoints)
        val side=result.handedness().firstOrNull()?.firstOrNull()?.categoryName() ?: return null
        return JPracticeEngine.Sample(points,flex,side)
    }

    private fun fingerFlexions(p:Array<DoubleArray>):DoubleArray {
        val joints=arrayOf(intArrayOf(1,2,3),intArrayOf(2,3,4),intArrayOf(5,6,7),intArrayOf(6,7,8),
            intArrayOf(9,10,11),intArrayOf(10,11,12),intArrayOf(13,14,15),intArrayOf(14,15,16),
            intArrayOf(17,18,19),intArrayOf(18,19,20))
        return DoubleArray(10) { i ->
            val (a,b,c)=joints[i]
            var dot=0.0; var aa=0.0; var cc=0.0
            for(k in 0..2) { val u=p[a][k]-p[b][k]; val v=p[c][k]-p[b][k]; dot+=u*v; aa+=u*u; cc+=v*v }
            if(aa<1e-12 || cc<1e-12) Double.NaN
            else 180-Math.toDegrees(kotlin.math.acos((dot/kotlin.math.sqrt(aa*cc)).coerceIn(-1.0,1.0)))
        }
    }
}
