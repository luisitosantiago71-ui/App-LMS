package com.mechrobotix.aprendels

import android.content.Context
import android.graphics.*
import android.view.View

/** Solo muestra la mano observada: no dibuja rutas, objetivos ni siluetas de referencia. */
class MotionHandOverlayView @JvmOverloads constructor(context:Context,attrs:android.util.AttributeSet?=null):View(context,attrs) {
    var engine:LetterMotionEngine?=null
    private var live:JPracticeEngine.Sample?=null
    private var frameW=0
    private var frameH=0
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val edges=arrayOf(0 to 1,1 to 2,2 to 3,3 to 4,0 to 5,5 to 6,6 to 7,7 to 8,
        5 to 9,9 to 10,10 to 11,11 to 12,9 to 13,13 to 14,14 to 15,15 to 16,
        13 to 17,0 to 17,17 to 18,18 to 19,19 to 20)
    fun update(sample:JPracticeEngine.Sample?,w:Int,h:Int){live=sample;frameW=w;frameH=h;invalidate()}
    fun clear(){live=null;invalidate()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val s=live ?: return
        if(width<=0 || height<=0 || frameW<=0 || frameH<=0 || !s.valid())return
        val scale=minOf(width.toFloat()/frameW,height.toFloat()/frameH)
        val left=(width-frameW*scale)/2;val top=(height-frameH*scale)/2
        val points=s.points.map { PointF(left+it[0].toFloat()*scale,top+it[1].toFloat()*scale) }
        val goodTip=engine?.isTipReliable ?: true
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2*resources.displayMetrics.density
        paint.color=if(engine?.stage==JPracticeEngine.Stage.SUCCESS) Color.GREEN else Color.WHITE
        edges.forEach { (a,b)->
            if(goodTip || (a!=20 && b!=20))canvas.drawLine(points[a].x,points[a].y,points[b].x,points[b].y,paint)
        }
        // No presenta la punta errónea como si fuera una medición válida.
        if(goodTip){paint.style=Paint.Style.FILL;paint.color=Color.MAGENTA
            canvas.drawCircle(points[20].x,points[20].y,5*resources.displayMetrics.density,paint)}
    }
}
