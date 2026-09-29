package com.mechrobotix.aprendels

/** Keeps J's approved A/curve/B implementation; other letters use ordered references. */
class LetterMotionEngine(letter:String,samples:List<JPracticeEngine.Sample>) {
    private val j=if(letter=="J") JPracticeEngine(samples) else null
    private val sequence=if(letter!="J") SequencePracticeEngine(samples) else null
    val stage get()=j?.stage ?: sequence!!.stage
    val message get()=j?.message ?: sequence!!.message
    val progress get()=j?.progress ?: sequence!!.progress
    val isTipReliable get()=j?.isTipReliable ?: true
    val trackingDescription get()=j?.trackingDescription ?: "Posturas y desplazamiento relativo en orden"
    fun setTolerance(value:JPracticeEngine.Tolerance){j?.setTolerance(value);sequence?.setTolerance(value)}
    fun setMirrored(value:Boolean){j?.setMirrored(value);sequence?.setMirrored(value)}
    fun reset(){j?.reset();sequence?.reset()}
    fun missing(time:Long,reason:String){j?.missing(time,reason);sequence?.missing(time,reason)}
    fun update(time:Long,sample:JPracticeEngine.Sample){j?.update(time,sample);sequence?.update(time,sample)}
}
