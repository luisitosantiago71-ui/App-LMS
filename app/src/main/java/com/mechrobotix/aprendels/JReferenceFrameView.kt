package com.mechrobotix.aprendels

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.MotionEvent
import kotlin.math.roundToInt

/** El mismo ajuste se aplica al fotograma y a sus puntos: no deforma la imagen. */
class JReferenceFrameView(context:Context):View(context) {
    private var bitmap:Bitmap?=null
    private var sample:JPracticeEngine.Sample?=null
    private var selecting=false
    private var selection:RectF?=null
    private var downX=0f
    private var downY=0f
    fun hasFrame()=bitmap!=null
    fun isSelecting()=selecting
    fun beginSelection() { selecting=true; selection=null; invalidate() }
    fun cancelSelection() { selecting=false }
    fun selectedRegion():Rect? {
        val b=bitmap ?: return null
        val r=selection ?: return null
        if(r.width()<32 || r.height()<32) return null
        return Rect(r.left.roundToInt().coerceIn(0,b.width-1),r.top.roundToInt().coerceIn(0,b.height-1),
            r.right.roundToInt().coerceIn(1,b.width),r.bottom.roundToInt().coerceIn(1,b.height))
    }
    override fun performClick():Boolean { super.performClick(); return true }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        val b=bitmap ?: return super.onTouchEvent(event)
        if(!selecting) return super.onTouchEvent(event)
        val scale=minOf(width.toFloat()/b.width,height.toFloat()/b.height)
        if(scale<=0f) return false
        val x=((event.x-(width-b.width*scale)/2)/scale).coerceIn(0f,b.width.toFloat())
        val y=((event.y-(height-b.height*scale)/2)/scale).coerceIn(0f,b.height.toFloat())
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=x; downY=y; selection=null; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                selection=RectF(minOf(downX,x),minOf(downY,y),maxOf(downX,x),maxOf(downY,y))
                if(event.actionMasked==MotionEvent.ACTION_UP) {
                    parent.requestDisallowInterceptTouchEvent(false); performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> { selection=null; parent.requestDisallowInterceptTouchEvent(false) }
        }
        invalidate(); return true
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val edges=arrayOf(0 to 1,1 to 2,2 to 3,3 to 4,0 to 5,5 to 6,6 to 7,7 to 8,
        5 to 9,9 to 10,10 to 11,11 to 12,9 to 13,13 to 14,14 to 15,15 to 16,
        13 to 17,0 to 17,17 to 18,18 to 19,19 to 20)
    fun show(frame:Bitmap?,points:JPracticeEngine.Sample?) {
        val old=bitmap; bitmap=frame; sample=points; selecting=false; selection=null; invalidate()
        if(old!==frame) old?.recycle()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val b=bitmap ?: return
        val scale=minOf(width.toFloat()/b.width,height.toFloat()/b.height)
        val x=(width-b.width*scale)/2; val y=(height-b.height*scale)/2
        paint.color=Color.WHITE
        canvas.drawBitmap(b,null,RectF(x,y,x+b.width*scale,y+b.height*scale),paint)
        selection?.let { r ->
            paint.color=Color.YELLOW; paint.style=Paint.Style.STROKE; paint.strokeWidth=2*resources.displayMetrics.density
            canvas.drawRect(x+r.left*scale,y+r.top*scale,x+r.right*scale,y+r.bottom*scale,paint)
            paint.style=Paint.Style.FILL
        }
        val s=sample ?: return
        fun px(i:Int)=x+s.points[i][0].toFloat()*scale
        fun py(i:Int)=y+s.points[i][1].toFloat()*scale
        paint.color=Color.CYAN; paint.strokeWidth=2*resources.displayMetrics.density
        edges.forEach { (a,c)->canvas.drawLine(px(a),py(a),px(c),py(c),paint) }
        for(i in 0..20) canvas.drawCircle(px(i),py(i),3*resources.displayMetrics.density,paint)
        paint.color=Color.MAGENTA; canvas.drawCircle(px(20),py(20),5*resources.displayMetrics.density,paint)
    }
}
